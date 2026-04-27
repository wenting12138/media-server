package com.wenting.mediaserver.core.hls;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.RtpH264AccessUnitNormalizer;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

final class HlsStreamSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HlsStreamSession.class);
    private static final double MIN_SEGMENT_DURATION_SEC = 0.2d;

    private final StreamKey key;
    private final Path rootDir;
    private final Path streamDir;
    private final Path playlistPath;
    private final int segmentDuration90k;
    private final int listSize;
    private final boolean deleteSegments;
    private final RtpH264AccessUnitNormalizer normalizer = new RtpH264AccessUnitNormalizer();
    private final Deque<HlsSegmentInfo> segments = new ArrayDeque<HlsSegmentInfo>();

    private HlsAacConfig activeAacConfig;
    private HlsSegmentWriter currentSegment;
    private int nextSequence;
    private int currentSegmentStartPts90k;
    private int lastMuxPts90k;
    private int firstVideoRtpTs90k = Integer.MIN_VALUE;
    private int firstVideoRtmpTimestampMs = Integer.MIN_VALUE;
    private long videoStartNano = -1L;
    private int audioBaseRtpTs = Integer.MIN_VALUE;
    private long audioBasePts90k = Long.MIN_VALUE;
    private int rtmpAvcNalLengthSize = 4;
    private byte[] rtmpSps;
    private byte[] rtmpPps;

    HlsStreamSession(
            StreamKey key,
            String sdpText,
            Path rootDir,
            int segmentDuration90k,
            int listSize,
            boolean deleteSegments) {
        this.key = key;
        this.rootDir = rootDir;
        this.segmentDuration90k = segmentDuration90k;
        this.listSize = listSize;
        this.deleteSegments = deleteSegments;
        this.streamDir = rootDir
                .resolve(HlsCodecUtil.safePathPart(key.app()))
                .resolve(HlsCodecUtil.safePathPart(key.stream()))
                .normalize();
        if (!streamDir.startsWith(rootDir)) {
            throw new IllegalStateException("Invalid stream dir for HLS: " + streamDir);
        }
        this.playlistPath = streamDir.resolve("index.m3u8");
        this.nextSequence = 0;
        this.currentSegmentStartPts90k = Integer.MIN_VALUE;
        this.lastMuxPts90k = Integer.MIN_VALUE;
        prepareStreamDirectory();
        HlsSdpHints sdpHints = HlsCodecUtil.parseSdpHints(sdpText);
        if (sdpHints != null && (sdpHints.sps() != null || sdpHints.pps() != null)) {
            normalizer.seedParameterSets(sdpHints.sps(), sdpHints.pps());
            log.info("HLS seeded H264 parameter sets stream={} sps={}B pps={}B",
                    key.path(),
                    sdpHints.sps() == null ? 0 : sdpHints.sps().length,
                    sdpHints.pps() == null ? 0 : sdpHints.pps().length);
        }
        this.activeAacConfig = sdpHints == null ? null : sdpHints.aacConfig();
        if (activeAacConfig != null) {
            log.info("HLS AAC enabled stream={} pt={} clock={} ch={}",
                    key.path(),
                    activeAacConfig.payloadType(),
                    activeAacConfig.clockRate(),
                    activeAacConfig.channelConfig());
        }
    }

    Path streamDir() {
        return streamDir;
    }

    synchronized void onVideoRtpPacket(ByteBuf rtpPacket) {
        List<RtpH264AccessUnitNormalizer.AccessUnit> accessUnits = normalizer.ingest(rtpPacket);
        if (accessUnits == null || accessUnits.isEmpty()) {
            return;
        }
        for (RtpH264AccessUnitNormalizer.AccessUnit accessUnit : accessUnits) {
            if (accessUnit == null) {
                continue;
            }
            try {
                onAccessUnit(accessUnit);
            } finally {
                accessUnit.release();
            }
        }
    }

    synchronized void onAudioRtpPacket(ByteBuf rtpPacket, long arrivalNano) {
        if (activeAacConfig == null || currentSegment == null || rtpPacket == null || !rtpPacket.isReadable()) {
            return;
        }
        int payloadType = HlsCodecUtil.readRtpPayloadType(rtpPacket);
        if (payloadType >= 0 && activeAacConfig.payloadType() >= 0 && payloadType != activeAacConfig.payloadType()) {
            return;
        }
        int rtpTs = HlsCodecUtil.readRtpTimestamp(rtpPacket);
        if (rtpTs == Integer.MIN_VALUE) {
            return;
        }
        List<byte[]> frames = HlsCodecUtil.extractAacAccessUnits(rtpPacket, activeAacConfig);
        if (frames.isEmpty()) {
            return;
        }
        long basePts90k = mapAudioPts90k(rtpTs, arrivalNano);
        int frameDuration90k = activeAacConfig.frameDuration90k();
        for (int i = 0; i < frames.size(); i++) {
            long pts = (basePts90k + (long) i * frameDuration90k) & 0xFFFFFFFFL;
            currentSegment.writeAudioFrame(frames.get(i), (int) pts);
            lastMuxPts90k = (int) pts;
        }
    }

    synchronized void onRtmpVideoTag(ByteBuf payload, int timestampMs) {
        if (payload == null || payload.readableBytes() < 5) {
            return;
        }
        int ri = payload.readerIndex();
        int first = payload.getUnsignedByte(ri);
        int frameType = (first >> 4) & 0x0F;
        int codecId = first & 0x0F;
        if (codecId != 7) {
            return;
        }
        int avcPacketType = payload.getUnsignedByte(ri + 1);
        if (avcPacketType == 0) {
            parseRtmpAvcSequenceHeader(payload);
            return;
        }
        if (avcPacketType != 1) {
            return;
        }
        int compositionTime = HlsCodecUtil.readSigned24(
                payload.getUnsignedByte(ri + 2),
                payload.getUnsignedByte(ri + 3),
                payload.getUnsignedByte(ri + 4));
        int ptsMs = timestampMs + compositionTime;
        if (ptsMs < 0) {
            ptsMs = 0;
        }
        HlsRtmpAnnexbFrame frame = decodeRtmpAvccFrame(payload, frameType == 1);
        if (frame == null || frame.annexb() == null || frame.annexb().length == 0) {
            return;
        }
        int pts90k = mapVideoPts90kFromMs(ptsMs);
        boolean keyFrame = frameType == 1 && frame.hasIdr();
        ingestVideoAccessUnit(frame.annexb(), pts90k, keyFrame);
    }

    synchronized void onRtmpAudioTag(ByteBuf payload, int timestampMs) {
        if (payload == null || payload.readableBytes() < 2) {
            return;
        }
        int ri = payload.readerIndex();
        int soundHeader = payload.getUnsignedByte(ri);
        int soundFormat = (soundHeader >> 4) & 0x0F;
        if (soundFormat != 10) {
            return;
        }
        int aacPacketType = payload.getUnsignedByte(ri + 1);
        if (aacPacketType == 0) {
            HlsAacConfig parsed = HlsCodecUtil.parseAacConfigFromAsc(payload, ri + 2, payload.readableBytes() - 2);
            if (parsed != null) {
                activeAacConfig = parsed;
                log.info("HLS AAC enabled(stream={} source=rtmp) clock={} ch={}",
                        key.path(), activeAacConfig.clockRate(), activeAacConfig.channelConfig());
            }
            return;
        }
        if (aacPacketType != 1 || activeAacConfig == null || currentSegment == null) {
            return;
        }
        int dataOffset = ri + 2;
        int dataLen = payload.readableBytes() - 2;
        if (dataLen <= 0) {
            return;
        }
        byte[] raw = new byte[dataLen];
        payload.getBytes(dataOffset, raw);
        int pts90k = mapAudioPts90kFromMs(timestampMs);
        currentSegment.writeAudioFrame(raw, pts90k);
        lastMuxPts90k = pts90k;
    }

    synchronized void onNoViewer() {
        if (currentSegment == null) {
            return;
        }
        int closingPts = lastMuxPts90k;
        if (closingPts == Integer.MIN_VALUE) {
            closingPts = currentSegmentStartPts90k + segmentDuration90k;
        }
        finalizeCurrentSegment(closingPts);
    }

    @Override
    public synchronized void close() {
        try {
            RtpH264AccessUnitNormalizer.AccessUnit tail = normalizer.flush();
            if (tail != null) {
                try {
                    onAccessUnit(tail);
                } finally {
                    tail.release();
                }
            }
        } finally {
            normalizer.close();
        }
        if (currentSegment != null) {
            int closingPts = lastMuxPts90k;
            if (closingPts == Integer.MIN_VALUE) {
                closingPts = currentSegmentStartPts90k + segmentDuration90k;
            }
            finalizeCurrentSegment(closingPts);
        }
    }

    private void onAccessUnit(RtpH264AccessUnitNormalizer.AccessUnit accessUnit) {
        if (!accessUnit.hasVcl()) {
            return;
        }
        int pts90k = mapVideoPts90k(accessUnit.timestamp90k());
        byte[] accessUnitBytes = new byte[accessUnit.annexB().readableBytes()];
        accessUnit.annexB().getBytes(accessUnit.annexB().readerIndex(), accessUnitBytes);
        ingestVideoAccessUnit(accessUnitBytes, pts90k, accessUnit.keyFrame());
    }

    private void ingestVideoAccessUnit(byte[] annexb, int pts90k, boolean keyFrame) {
        if (annexb == null || annexb.length == 0) {
            return;
        }
        if (currentSegment == null) {
            if (!keyFrame) {
                return;
            }
            startNewSegment(pts90k);
        } else if (keyFrame && elapsed90k(currentSegmentStartPts90k, pts90k) >= segmentDuration90k) {
            finalizeCurrentSegment(pts90k);
            startNewSegment(pts90k);
        }
        if (currentSegment == null) {
            return;
        }
        currentSegment.writeVideoAccessUnit(annexb, pts90k);
        lastMuxPts90k = pts90k;
    }

    private void startNewSegment(int pts90k) {
        String segmentFile = String.format(Locale.US, "seg_%06d.ts", nextSequence);
        Path segmentPath = streamDir.resolve(segmentFile);
        currentSegment = new HlsSegmentWriter(nextSequence, segmentFile, segmentPath, activeAacConfig);
        currentSegmentStartPts90k = pts90k;
        lastMuxPts90k = pts90k;
        if (videoStartNano < 0L) {
            videoStartNano = System.nanoTime();
        }
        nextSequence++;
    }

    private void finalizeCurrentSegment(int closingPts90k) {
        if (currentSegment == null) {
            return;
        }
        currentSegment.close();
        double durationSec = elapsed90k(currentSegmentStartPts90k, closingPts90k) / (double) HlsStreamFrameProcessor.CLOCK_90K;
        if (durationSec < MIN_SEGMENT_DURATION_SEC) {
            durationSec = MIN_SEGMENT_DURATION_SEC;
        }
        segments.addLast(new HlsSegmentInfo(
                currentSegment.sequence(),
                currentSegment.fileName(),
                currentSegment.path(),
                durationSec));
        while (segments.size() > listSize) {
            HlsSegmentInfo removed = segments.removeFirst();
            if (deleteSegments) {
                try {
                    Files.deleteIfExists(removed.path());
                } catch (IOException e) {
                    log.warn("Failed to delete old HLS segment {}", removed.path(), e);
                }
            }
        }
        writePlaylist();
        currentSegment = null;
        currentSegmentStartPts90k = Integer.MIN_VALUE;
    }

    private void prepareStreamDirectory() {
        try {
            Files.createDirectories(streamDir);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(streamDir)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString().toLowerCase(Locale.US);
                    if (name.endsWith(".ts") || name.endsWith(".m3u8") || name.endsWith(".tmp")) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare HLS stream dir " + streamDir, e);
        }
    }

    private void writePlaylist() {
        List<HlsSegmentInfo> snapshot = new ArrayList<HlsSegmentInfo>(segments);
        int mediaSequence = snapshot.isEmpty() ? nextSequence : snapshot.get(0).sequence();
        int targetDuration = Math.max(1, segmentDuration90k / HlsStreamFrameProcessor.CLOCK_90K);
        for (HlsSegmentInfo segment : snapshot) {
            targetDuration = Math.max(targetDuration, (int) Math.ceil(segment.durationSec()));
        }
        StringBuilder m3u8 = new StringBuilder(256 + snapshot.size() * 48);
        m3u8.append("#EXTM3U\n");
        m3u8.append("#EXT-X-VERSION:3\n");
        m3u8.append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n');
        m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append('\n');
        for (HlsSegmentInfo segment : snapshot) {
            m3u8.append("#EXTINF:")
                    .append(String.format(Locale.US, "%.3f", segment.durationSec()))
                    .append(",\n");
            m3u8.append(segment.fileName()).append('\n');
        }
        Path tmp = playlistPath.resolveSibling(playlistPath.getFileName().toString() + ".tmp");
        try {
            Files.write(tmp, m3u8.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(tmp, playlistPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to write HLS playlist {}", playlistPath, e);
        }
    }

    private int mapVideoPts90k(int rtpTs90k) {
        if (firstVideoRtpTs90k == Integer.MIN_VALUE) {
            firstVideoRtpTs90k = rtpTs90k;
        }
        return (int) elapsed90k(firstVideoRtpTs90k, rtpTs90k);
    }

    private long mapAudioPts90k(int rtpTs, long arrivalNano) {
        if (audioBaseRtpTs == Integer.MIN_VALUE) {
            audioBaseRtpTs = rtpTs;
            long wallDelta90k = 0L;
            if (videoStartNano > 0L) {
                long deltaNs = arrivalNano - videoStartNano;
                wallDelta90k = (deltaNs * HlsStreamFrameProcessor.CLOCK_90K) / 1000000000L;
                if (wallDelta90k < 0L) {
                    wallDelta90k = 0L;
                }
            }
            audioBasePts90k = wallDelta90k;
        }
        long deltaAudio = elapsed90k(audioBaseRtpTs, rtpTs);
        long delta90k = (deltaAudio * HlsStreamFrameProcessor.CLOCK_90K)
                / Math.max(1, activeAacConfig == null ? 48000 : activeAacConfig.clockRate());
        return (audioBasePts90k + delta90k) & 0xFFFFFFFFL;
    }

    private int mapVideoPts90kFromMs(int timestampMs) {
        if (firstVideoRtmpTimestampMs == Integer.MIN_VALUE) {
            firstVideoRtmpTimestampMs = timestampMs;
        }
        int deltaMs = timestampMs - firstVideoRtmpTimestampMs;
        if (deltaMs < 0) {
            deltaMs = 0;
        }
        return (int) (((long) deltaMs * 90L) & 0xFFFFFFFFL);
    }

    private int mapAudioPts90kFromMs(int timestampMs) {
        if (firstVideoRtmpTimestampMs == Integer.MIN_VALUE) {
            return (int) (((long) Math.max(0, timestampMs) * 90L) & 0xFFFFFFFFL);
        }
        int deltaMs = timestampMs - firstVideoRtmpTimestampMs;
        if (deltaMs < 0) {
            deltaMs = 0;
        }
        return (int) (((long) deltaMs * 90L) & 0xFFFFFFFFL);
    }

    private void parseRtmpAvcSequenceHeader(ByteBuf payload) {
        int base = payload.readerIndex() + 5;
        int end = payload.readerIndex() + payload.readableBytes();
        if (base + 6 > end) {
            return;
        }
        int lengthSizeMinusOne = payload.getUnsignedByte(base + 4) & 0x03;
        rtmpAvcNalLengthSize = lengthSizeMinusOne + 1;
        int off = base + 5;
        int numSps = payload.getUnsignedByte(off) & 0x1F;
        off++;
        byte[] sps = null;
        byte[] pps = null;
        for (int i = 0; i < numSps; i++) {
            if (off + 2 > end) {
                return;
            }
            int len = ((payload.getUnsignedByte(off) << 8) | payload.getUnsignedByte(off + 1));
            off += 2;
            if (len <= 0 || off + len > end) {
                return;
            }
            byte[] nalu = new byte[len];
            payload.getBytes(off, nalu);
            if (i == 0) {
                sps = nalu;
            }
            off += len;
        }
        if (off + 1 > end) {
            return;
        }
        int numPps = payload.getUnsignedByte(off);
        off++;
        for (int i = 0; i < numPps; i++) {
            if (off + 2 > end) {
                return;
            }
            int len = ((payload.getUnsignedByte(off) << 8) | payload.getUnsignedByte(off + 1));
            off += 2;
            if (len <= 0 || off + len > end) {
                return;
            }
            byte[] nalu = new byte[len];
            payload.getBytes(off, nalu);
            if (i == 0) {
                pps = nalu;
            }
            off += len;
        }
        if (sps != null) {
            rtmpSps = sps;
        }
        if (pps != null) {
            rtmpPps = pps;
        }
    }

    private HlsRtmpAnnexbFrame decodeRtmpAvccFrame(ByteBuf payload, boolean keyFrame) {
        int off = payload.readerIndex() + 5;
        int end = payload.readerIndex() + payload.readableBytes();
        int nalLengthSize = rtmpAvcNalLengthSize <= 0 ? 4 : rtmpAvcNalLengthSize;
        List<byte[]> nals = new ArrayList<byte[]>();
        boolean hasSps = false;
        boolean hasPps = false;
        boolean hasIdr = false;
        while (off + nalLengthSize <= end) {
            int nalLen = 0;
            for (int i = 0; i < nalLengthSize; i++) {
                nalLen = (nalLen << 8) | payload.getUnsignedByte(off + i);
            }
            off += nalLengthSize;
            if (nalLen <= 0 || off + nalLen > end) {
                break;
            }
            byte[] nalu = new byte[nalLen];
            payload.getBytes(off, nalu);
            nals.add(nalu);
            int nalType = nalu[0] & 0x1F;
            if (nalType == 7) {
                hasSps = true;
            } else if (nalType == 8) {
                hasPps = true;
            } else if (nalType == 5) {
                hasIdr = true;
            }
            off += nalLen;
        }
        if (nals.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.readableBytes() * 2);
        if (keyFrame && !hasSps && rtmpSps != null) {
            HlsCodecUtil.writeAnnexbNal(out, rtmpSps);
        }
        if (keyFrame && !hasPps && rtmpPps != null) {
            HlsCodecUtil.writeAnnexbNal(out, rtmpPps);
        }
        for (byte[] nalu : nals) {
            HlsCodecUtil.writeAnnexbNal(out, nalu);
        }
        return new HlsRtmpAnnexbFrame(out.toByteArray(), hasIdr);
    }

    private static long elapsed90k(int startPts90k, int endPts90k) {
        return ((endPts90k & 0xFFFFFFFFL) - (startPts90k & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
    }
}
