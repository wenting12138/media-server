package com.wenting.mediaserver.core.hls;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.EncodedMediaPacket;
import com.wenting.mediaserver.core.transcode.RtpH264AccessUnitNormalizer;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;
import com.wenting.mediaserver.protocol.rtp.RtpHeader;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-Java RTSP(H264 RTP) -> HLS(TS) pipeline.
 * Supports H264 video and optional AAC audio.
 */
public final class HlsStreamFrameProcessor implements StreamFrameProcessor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HlsStreamFrameProcessor.class);

    private static final int CLOCK_90K = 90000;
    private static final double MIN_SEGMENT_DURATION_SEC = 0.2d;

    private final boolean enabled;
    private final Path rootDir;
    private final int segmentDuration90k;
    private final int listSize;
    private final boolean deleteSegments;
    private final Map<StreamKey, Session> sessions = new ConcurrentHashMap<StreamKey, Session>();

    public HlsStreamFrameProcessor(MediaServerConfig config) {
        this.enabled = config != null && config.hlsEnabled();
        this.rootDir = Paths.get(config == null ? "hls" : config.hlsRoot()).toAbsolutePath().normalize();
        int seconds = config == null ? 2 : config.hlsSegmentSeconds();
        this.segmentDuration90k = Math.max(1, seconds) * CLOCK_90K;
        this.listSize = Math.max(2, config == null ? 6 : config.hlsListSize());
        this.deleteSegments = config == null || config.hlsDeleteSegments();
        if (enabled) {
            try {
                Files.createDirectories(rootDir);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create HLS root dir: " + rootDir, e);
            }
            log.info("HLS enabled root={} segment={}s list={} deleteSegments={}",
                    rootDir, segmentDuration90k / CLOCK_90K, listSize, deleteSegments);
        } else {
            log.info("HLS disabled");
        }
    }

    public Path rootDir() {
        return rootDir;
    }

    @Override
    public void onPublishStart(StreamKey key, String sdpText) {
        if (!enabled || key == null) {
            return;
        }
        Session session = new Session(key, sdpText);
        Session previous = sessions.putIfAbsent(key, session);
        if (previous == null) {
            log.info("HLS session start stream={} dir={}", key.path(), session.streamDir);
        }
    }

    @Override
    public void onPacket(StreamKey key, EncodedMediaPacket packet) {
        if (!enabled || key == null || packet == null) {
            return;
        }
        Session session = sessions.get(key);
        if (session == null) {
            return;
        }
        if (packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTSP
                && packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTP_PACKET) {
            if (packet.trackType() == EncodedMediaPacket.TrackType.VIDEO
                    && packet.codecType() == EncodedMediaPacket.CodecType.H264) {
                session.onVideoRtpPacket(packet.payload());
                return;
            }
            if (packet.trackType() == EncodedMediaPacket.TrackType.AUDIO) {
                session.onAudioRtpPacket(packet.payload(), System.nanoTime());
            }
            return;
        }
        if (packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTMP
                && packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTMP_TAG) {
            if (packet.trackType() == EncodedMediaPacket.TrackType.VIDEO
                    && packet.codecType() == EncodedMediaPacket.CodecType.H264) {
                session.onRtmpVideoTag(packet.payload(), packet.timestamp());
                return;
            }
            if (packet.trackType() == EncodedMediaPacket.TrackType.AUDIO
                    && packet.codecType() == EncodedMediaPacket.CodecType.AAC) {
                session.onRtmpAudioTag(packet.payload(), packet.timestamp());
            }
        }
    }

    @Override
    public void onPublishStop(StreamKey key) {
        if (!enabled || key == null) {
            return;
        }
        Session session = sessions.remove(key);
        if (session != null) {
            session.close();
            log.info("HLS session stop stream={}", key.path());
        }
    }

    @Override
    public void close() {
        for (Session session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    private final class Session {
        private final StreamKey key;
        private final Path streamDir;
        private final Path playlistPath;
        private final RtpH264AccessUnitNormalizer normalizer = new RtpH264AccessUnitNormalizer();
        private final Deque<SegmentInfo> segments = new ArrayDeque<SegmentInfo>();
        private AacConfig activeAacConfig;
        private SegmentWriter currentSegment;
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

        private Session(StreamKey key, String sdpText) {
            this.key = key;
            this.streamDir = rootDir
                    .resolve(safePathPart(key.app()))
                    .resolve(safePathPart(key.stream()))
                    .normalize();
            if (!streamDir.startsWith(rootDir)) {
                throw new IllegalStateException("Invalid stream dir for HLS: " + streamDir);
            }
            this.playlistPath = streamDir.resolve("index.m3u8");
            this.nextSequence = 0;
            this.currentSegmentStartPts90k = Integer.MIN_VALUE;
            this.lastMuxPts90k = Integer.MIN_VALUE;
            prepareStreamDirectory();
            SdpHints sdpHints = parseSdpHints(sdpText);
            if (sdpHints != null && (sdpHints.sps != null || sdpHints.pps != null)) {
                normalizer.seedParameterSets(sdpHints.sps, sdpHints.pps);
                log.info("HLS seeded H264 parameter sets stream={} sps={}B pps={}B",
                        key.path(),
                        sdpHints.sps == null ? 0 : sdpHints.sps.length,
                        sdpHints.pps == null ? 0 : sdpHints.pps.length);
            }
            this.activeAacConfig = sdpHints == null ? null : sdpHints.aacConfig;
            if (activeAacConfig != null) {
                log.info("HLS AAC enabled stream={} pt={} clock={} ch={}",
                        key.path(), activeAacConfig.payloadType, activeAacConfig.clockRate, activeAacConfig.channelConfig);
            }
        }

        private synchronized void onVideoRtpPacket(ByteBuf rtpPacket) {
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

        private void onAccessUnit(RtpH264AccessUnitNormalizer.AccessUnit accessUnit) {
            if (!accessUnit.hasVcl()) {
                return;
            }
            int pts90k = mapVideoPts90k(accessUnit.timestamp90k());
            byte[] accessUnitBytes = new byte[accessUnit.annexB().readableBytes()];
            accessUnit.annexB().getBytes(accessUnit.annexB().readerIndex(), accessUnitBytes);
            ingestVideoAccessUnit(accessUnitBytes, pts90k, accessUnit.keyFrame());
        }

        private synchronized void onAudioRtpPacket(ByteBuf rtpPacket, long arrivalNano) {
            if (activeAacConfig == null || currentSegment == null || rtpPacket == null || !rtpPacket.isReadable()) {
                return;
            }
            int payloadType = readRtpPayloadType(rtpPacket);
            if (payloadType >= 0 && activeAacConfig.payloadType >= 0 && payloadType != activeAacConfig.payloadType) {
                return;
            }
            int rtpTs = readRtpTimestamp(rtpPacket);
            if (rtpTs == Integer.MIN_VALUE) {
                return;
            }
            List<byte[]> frames = extractAacAccessUnits(rtpPacket, activeAacConfig);
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

        private synchronized void onRtmpVideoTag(ByteBuf payload, int timestampMs) {
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
            int compositionTime = readSigned24(payload.getUnsignedByte(ri + 2), payload.getUnsignedByte(ri + 3), payload.getUnsignedByte(ri + 4));
            int ptsMs = timestampMs + compositionTime;
            if (ptsMs < 0) {
                ptsMs = 0;
            }
            RtmpAnnexbFrame frame = decodeRtmpAvccFrame(payload, frameType == 1);
            if (frame == null || frame.annexb == null || frame.annexb.length == 0) {
                return;
            }
            int pts90k = mapVideoPts90kFromMs(ptsMs);
            boolean keyFrame = frameType == 1 && frame.hasIdr;
            ingestVideoAccessUnit(frame.annexb, pts90k, keyFrame);
        }

        private synchronized void onRtmpAudioTag(ByteBuf payload, int timestampMs) {
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
                AacConfig parsed = parseAacConfigFromAsc(payload, ri + 2, payload.readableBytes() - 2);
                if (parsed != null) {
                    activeAacConfig = parsed;
                    log.info("HLS AAC enabled(stream={} source=rtmp) clock={} ch={}",
                            key.path(), activeAacConfig.clockRate, activeAacConfig.channelConfig);
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

        private void startNewSegment(int pts90k) {
            String segmentFile = String.format(Locale.US, "seg_%06d.ts", nextSequence);
            Path segmentPath = streamDir.resolve(segmentFile);
            currentSegment = new SegmentWriter(nextSequence, segmentFile, segmentPath, activeAacConfig);
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
            double durationSec = elapsed90k(currentSegmentStartPts90k, closingPts90k) / (double) CLOCK_90K;
            if (durationSec < MIN_SEGMENT_DURATION_SEC) {
                durationSec = MIN_SEGMENT_DURATION_SEC;
            }
            segments.addLast(new SegmentInfo(
                    currentSegment.sequence,
                    currentSegment.fileName,
                    currentSegment.path,
                    durationSec));
            while (segments.size() > listSize) {
                SegmentInfo removed = segments.removeFirst();
                if (deleteSegments) {
                    try {
                        Files.deleteIfExists(removed.path);
                    } catch (IOException e) {
                        log.warn("Failed to delete old HLS segment {}", removed.path, e);
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
            List<SegmentInfo> snapshot = new ArrayList<SegmentInfo>(segments);
            int mediaSequence = snapshot.isEmpty() ? nextSequence : snapshot.get(0).sequence;
            int targetDuration = Math.max(1, segmentDuration90k / CLOCK_90K);
            for (SegmentInfo segment : snapshot) {
                targetDuration = Math.max(targetDuration, (int) Math.ceil(segment.durationSec));
            }
            StringBuilder m3u8 = new StringBuilder(256 + snapshot.size() * 48);
            m3u8.append("#EXTM3U\n");
            m3u8.append("#EXT-X-VERSION:3\n");
            m3u8.append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n');
            m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append('\n');
            for (SegmentInfo segment : snapshot) {
                m3u8.append("#EXTINF:")
                        .append(String.format(Locale.US, "%.3f", segment.durationSec))
                        .append(",\n");
                m3u8.append(segment.fileName).append('\n');
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
                    wallDelta90k = (deltaNs * CLOCK_90K) / 1000000000L;
                    if (wallDelta90k < 0L) {
                        wallDelta90k = 0L;
                    }
                }
                audioBasePts90k = wallDelta90k;
            }
            long deltaAudio = elapsed90k(audioBaseRtpTs, rtpTs);
            long delta90k = (deltaAudio * CLOCK_90K) / Math.max(1, activeAacConfig == null ? 48000 : activeAacConfig.clockRate);
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

        private RtmpAnnexbFrame decodeRtmpAvccFrame(ByteBuf payload, boolean keyFrame) {
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
                writeAnnexbNal(out, rtmpSps);
                hasSps = true;
            }
            if (keyFrame && !hasPps && rtmpPps != null) {
                writeAnnexbNal(out, rtmpPps);
                hasPps = true;
            }
            for (byte[] nalu : nals) {
                writeAnnexbNal(out, nalu);
            }
            return new RtmpAnnexbFrame(out.toByteArray(), hasIdr, hasSps, hasPps);
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

        private synchronized void close() {
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
    }

    private static final class SegmentInfo {
        private final int sequence;
        private final String fileName;
        private final Path path;
        private final double durationSec;

        private SegmentInfo(int sequence, String fileName, Path path, double durationSec) {
            this.sequence = sequence;
            this.fileName = fileName;
            this.path = path;
            this.durationSec = durationSec;
        }
    }

    private static final class SegmentWriter {
        private final int sequence;
        private final String fileName;
        private final Path path;
        private final OutputStream out;
        private final TsMuxer muxer;
        private boolean closed;

        private SegmentWriter(int sequence, String fileName, Path path, AacConfig aacConfig) {
            this.sequence = sequence;
            this.fileName = fileName;
            this.path = path;
            this.muxer = new TsMuxer(aacConfig);
            try {
                this.out = new BufferedOutputStream(Files.newOutputStream(path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE));
                muxer.writePatPmt(out);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to open HLS segment file " + path, e);
            }
        }

        private void writeVideoAccessUnit(ByteBuf annexbAccessUnit, int pts90k) {
            if (closed || annexbAccessUnit == null || !annexbAccessUnit.isReadable()) {
                return;
            }
            try {
                byte[] accessUnit = new byte[annexbAccessUnit.readableBytes()];
                annexbAccessUnit.getBytes(annexbAccessUnit.readerIndex(), accessUnit);
                muxer.writeH264AccessUnit(out, accessUnit, pts90k & 0xFFFFFFFFL);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write HLS segment " + path, e);
            }
        }

        private void writeVideoAccessUnit(byte[] annexbAccessUnit, int pts90k) {
            if (closed || annexbAccessUnit == null || annexbAccessUnit.length == 0) {
                return;
            }
            try {
                muxer.writeH264AccessUnit(out, annexbAccessUnit, pts90k & 0xFFFFFFFFL);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write HLS segment " + path, e);
            }
        }

        private void writeAudioFrame(byte[] rawAac, int pts90k) {
            if (closed || rawAac == null || rawAac.length == 0) {
                return;
            }
            try {
                muxer.writeAacFrame(out, rawAac, pts90k & 0xFFFFFFFFL);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write HLS audio into segment " + path, e);
            }
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.flush();
            } catch (IOException ignore) {
                // ignore
            }
            try {
                out.close();
            } catch (IOException ignore) {
                // ignore
            }
        }
    }

    private static final class TsMuxer {
        private static final int TS_PACKET_SIZE = 188;
        private static final int TS_PAYLOAD_MAX = 184;
        private static final int PID_PAT = 0x0000;
        private static final int PID_PMT = 0x0100;
        private static final int PID_VIDEO = 0x0101;
        private static final int PID_AUDIO = 0x0102;
        private int ccPat;
        private int ccPmt;
        private int ccVideo;
        private int ccAudio;
        private final AacConfig aacConfig;

        private TsMuxer(AacConfig aacConfig) {
            this.aacConfig = aacConfig;
        }

        private void writePatPmt(OutputStream out) throws IOException {
            writePsiPacket(out, PID_PAT, buildPatSection(), true);
            writePsiPacket(out, PID_PMT, buildPmtSection(), true);
        }

        private void writeH264AccessUnit(OutputStream out, byte[] annexbAccessUnit, long pts90k) throws IOException {
            byte[] pes = buildPes(annexbAccessUnit, pts90k, true);
            writeTsPayload(out, PID_VIDEO, pes, true);
        }

        private void writeAacFrame(OutputStream out, byte[] rawAac, long pts90k) throws IOException {
            if (aacConfig == null) {
                return;
            }
            byte[] adts = buildAdtsFrame(rawAac, aacConfig);
            byte[] pes = buildPes(adts, pts90k, false);
            writeTsPayload(out, PID_AUDIO, pes, true);
        }

        private void writePsiPacket(OutputStream out, int pid, byte[] section, boolean unitStart) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream(section.length + 1);
            payload.write(0x00);
            payload.write(section);
            writeTsPayload(out, pid, payload.toByteArray(), unitStart);
        }

        private void writeTsPayload(OutputStream out, int pid, byte[] payload, boolean firstUnitStart) throws IOException {
            int off = 0;
            boolean start = firstUnitStart;
            while (off < payload.length) {
                int remaining = payload.length - off;
                int payloadLen = Math.min(TS_PAYLOAD_MAX, remaining);
                int cc = nextCc(pid);
                byte[] packet = buildTsPacket(pid, start, cc, payload, off, payloadLen);
                out.write(packet);
                off += payloadLen;
                start = false;
            }
        }

        private byte[] buildTsPacket(
                int pid,
                boolean payloadUnitStart,
                int continuityCounter,
                byte[] payload,
                int payloadOffset,
                int payloadLength) {
            byte[] packet = new byte[TS_PACKET_SIZE];
            packet[0] = 0x47;
            packet[1] = (byte) (((payloadUnitStart ? 0x40 : 0x00) | ((pid >> 8) & 0x1F)) & 0xFF);
            packet[2] = (byte) (pid & 0xFF);
            int pos = 4;
            if (payloadLength == TS_PAYLOAD_MAX) {
                packet[3] = (byte) (0x10 | (continuityCounter & 0x0F));
            } else {
                packet[3] = (byte) (0x30 | (continuityCounter & 0x0F));
                int adaptationLength = 183 - payloadLength;
                packet[pos++] = (byte) (adaptationLength & 0xFF);
                if (adaptationLength > 0) {
                    packet[pos++] = 0x00;
                    for (int i = 1; i < adaptationLength; i++) {
                        packet[pos++] = (byte) 0xFF;
                    }
                }
            }
            System.arraycopy(payload, payloadOffset, packet, pos, payloadLength);
            return packet;
        }

        private int nextCc(int pid) {
            if (pid == PID_PAT) {
                int value = ccPat;
                ccPat = (ccPat + 1) & 0x0F;
                return value;
            }
            if (pid == PID_PMT) {
                int value = ccPmt;
                ccPmt = (ccPmt + 1) & 0x0F;
                return value;
            }
            if (pid == PID_AUDIO) {
                int value = ccAudio;
                ccAudio = (ccAudio + 1) & 0x0F;
                return value;
            }
            int value = ccVideo;
            ccVideo = (ccVideo + 1) & 0x0F;
            return value;
        }

        private byte[] buildPes(byte[] payload, long pts90k, boolean video) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 32);
            out.write(0x00);
            out.write(0x00);
            out.write(0x01);
            out.write(video ? 0xE0 : 0xC0);
            out.write(0x00);
            out.write(0x00);
            out.write(0x80);
            out.write(0x80);
            out.write(0x05);
            writePts(out, pts90k);
            out.write(payload);
            return out.toByteArray();
        }

        private void writePts(ByteArrayOutputStream out, long pts90k) {
            long pts = pts90k & 0x1FFFFFFFFL;
            out.write((int) (((0x2 << 4) | (((pts >> 30) & 0x07) << 1) | 0x01) & 0xFF));
            out.write((int) ((pts >> 22) & 0xFF));
            out.write((int) (((((pts >> 15) & 0x7F) << 1) | 0x01) & 0xFF));
            out.write((int) ((pts >> 7) & 0xFF));
            out.write((int) ((((pts & 0x7F) << 1) | 0x01) & 0xFF));
        }

        private byte[] buildPatSection() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32);
            out.write(0x00);
            writeShort(out, 0xB000 | 0x000D);
            writeShort(out, 0x0001);
            out.write(0xC1);
            out.write(0x00);
            out.write(0x00);
            writeShort(out, 0x0001);
            writeShort(out, 0xE000 | PID_PMT);
            writeCrc(out);
            return out.toByteArray();
        }

        private byte[] buildPmtSection() throws IOException {
            boolean hasAudio = aacConfig != null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(hasAudio ? 96 : 64);
            out.write(0x02);
            int sectionLength = hasAudio ? 0x0017 : 0x0012;
            writeShort(out, 0xB000 | sectionLength);
            writeShort(out, 0x0001);
            out.write(0xC1);
            out.write(0x00);
            out.write(0x00);
            writeShort(out, 0xE000 | PID_VIDEO);
            writeShort(out, 0xF000);
            out.write(0x1B);
            writeShort(out, 0xE000 | PID_VIDEO);
            writeShort(out, 0xF000);
            if (hasAudio) {
                out.write(0x0F);
                writeShort(out, 0xE000 | PID_AUDIO);
                writeShort(out, 0xF000);
            }
            writeCrc(out);
            return out.toByteArray();
        }

        private void writeShort(ByteArrayOutputStream out, int value) {
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        private void writeCrc(ByteArrayOutputStream out) throws IOException {
            byte[] bytes = out.toByteArray();
            int crc = mpegCrc32(bytes, 0, bytes.length);
            out.write((crc >> 24) & 0xFF);
            out.write((crc >> 16) & 0xFF);
            out.write((crc >> 8) & 0xFF);
            out.write(crc & 0xFF);
        }

        private int mpegCrc32(byte[] data, int off, int len) {
            int crc = 0xFFFFFFFF;
            for (int i = off; i < off + len; i++) {
                crc ^= (data[i] & 0xFF) << 24;
                for (int b = 0; b < 8; b++) {
                    if ((crc & 0x80000000) != 0) {
                        crc = (crc << 1) ^ 0x04C11DB7;
                    } else {
                        crc = crc << 1;
                    }
                }
            }
            return crc;
        }

        private byte[] buildAdtsFrame(byte[] rawAac, AacConfig config) {
            int frameLength = rawAac.length + 7;
            int profile = Math.max(1, config.audioObjectType) - 1;
            int sfIndex = config.sampleRateIndex;
            int channel = config.channelConfig & 0x07;
            byte[] out = new byte[frameLength];
            out[0] = (byte) 0xFF;
            out[1] = (byte) 0xF1;
            out[2] = (byte) ((((profile & 0x03) << 6) | ((sfIndex & 0x0F) << 2) | ((channel >> 2) & 0x01)) & 0xFF);
            out[3] = (byte) ((((channel & 0x03) << 6) | ((frameLength >> 11) & 0x03)) & 0xFF);
            out[4] = (byte) ((frameLength >> 3) & 0xFF);
            out[5] = (byte) ((((frameLength & 0x07) << 5) | 0x1F) & 0xFF);
            out[6] = (byte) 0xFC;
            System.arraycopy(rawAac, 0, out, 7, rawAac.length);
            return out;
        }
    }

    private static long elapsed90k(int startPts90k, int endPts90k) {
        return ((endPts90k & 0xFFFFFFFFL) - (startPts90k & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
    }

    private static int readSigned24(int b0, int b1, int b2) {
        int value = ((b0 & 0xFF) << 16) | ((b1 & 0xFF) << 8) | (b2 & 0xFF);
        if ((value & 0x800000) != 0) {
            value |= 0xFF000000;
        }
        return value;
    }

    private static void writeAnnexbNal(ByteArrayOutputStream out, byte[] nalu) {
        if (out == null || nalu == null || nalu.length == 0) {
            return;
        }
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(nalu, 0, nalu.length);
    }

    private static AacConfig parseAacConfigFromAsc(ByteBuf payload, int offset, int length) {
        if (payload == null || length < 2) {
            return null;
        }
        int end = payload.readerIndex() + payload.readableBytes();
        if (offset < payload.readerIndex() || offset + 2 > end) {
            return null;
        }
        int bits = ((payload.getUnsignedByte(offset) & 0xFF) << 8)
                | (payload.getUnsignedByte(offset + 1) & 0xFF);
        int audioObjectType = (bits >> 11) & 0x1F;
        int sampleRateIndex = (bits >> 7) & 0x0F;
        int channelConfig = (bits >> 3) & 0x0F;
        if (audioObjectType <= 0) {
            audioObjectType = 2;
        }
        if (sampleRateIndex < 0 || sampleRateIndex > 12) {
            sampleRateIndex = 3;
        }
        if (channelConfig <= 0) {
            channelConfig = 2;
        }
        int sampleRate = sampleRateFromIndex(sampleRateIndex);
        return new AacConfig(
                -1,
                sampleRate,
                channelConfig,
                audioObjectType,
                sampleRateIndex,
                13,
                3,
                3);
    }

    private static int sampleRateFromIndex(int idx) {
        final int[] rates = new int[]{
                96000, 88200, 64000, 48000, 44100, 32000,
                24000, 22050, 16000, 12000, 11025, 8000, 7350
        };
        if (idx < 0 || idx >= rates.length) {
            return 48000;
        }
        return rates[idx];
    }

    private static SdpHints parseSdpHints(String sdpText) {
        if (sdpText == null || sdpText.trim().isEmpty()) {
            return null;
        }
        byte[][] sets = parseSpropParameterSets(sdpText);
        byte[] sps = sets == null ? null : sets[0];
        byte[] pps = sets == null ? null : sets[1];
        AacConfig aac = parseAacConfigFromSdp(sdpText);
        if ((sps == null || sps.length == 0)
                && (pps == null || pps.length == 0)
                && aac == null) {
            return null;
        }
        return new SdpHints(sps, pps, aac);
    }

    private static AacConfig parseAacConfigFromSdp(String sdpText) {
        if (sdpText == null || sdpText.trim().isEmpty()) {
            return null;
        }
        String[] lines = sdpText.split("\r\n|\n");
        int payloadType = -1;
        String rtpmap = null;
        String fmtp = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.toLowerCase(Locale.ROOT).startsWith("m=audio ")) {
                continue;
            }
            String[] parts = line.split(" ");
            if (parts.length >= 4) {
                try {
                    payloadType = Integer.parseInt(parts[3].trim());
                } catch (NumberFormatException e) {
                    payloadType = -1;
                }
            }
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j] == null ? "" : lines[j].trim();
                if (l.startsWith("m=")) {
                    break;
                }
                String lower = l.toLowerCase(Locale.ROOT);
                if (lower.startsWith("a=rtpmap:")) {
                    String rest = l.substring("a=rtpmap:".length());
                    int sp = rest.indexOf(' ');
                    if (sp > 0) {
                        int pt = parseIntSafe(rest.substring(0, sp).trim(), -1);
                        if (pt == payloadType) {
                            rtpmap = rest.substring(sp + 1).trim();
                        }
                    }
                } else if (lower.startsWith("a=fmtp:")) {
                    String rest = l.substring("a=fmtp:".length());
                    int sp = rest.indexOf(' ');
                    if (sp > 0) {
                        int pt = parseIntSafe(rest.substring(0, sp).trim(), -1);
                        if (pt == payloadType) {
                            fmtp = rest.substring(sp + 1).trim();
                        }
                    }
                }
            }
            break;
        }
        if (payloadType < 0 || rtpmap == null) {
            return null;
        }
        String upper = rtpmap.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("MPEG4-GENERIC/")) {
            return null;
        }
        String[] mapParts = rtpmap.split("/");
        int clockRate = mapParts.length >= 2 ? parseIntSafe(mapParts[1], 48000) : 48000;
        int channelConfig = mapParts.length >= 3 ? Math.max(1, parseIntSafe(mapParts[2], 2)) : 2;
        int sizeLength = parseFmtpInt(fmtp, "sizelength", 13);
        int indexLength = parseFmtpInt(fmtp, "indexlength", 3);
        int indexDeltaLength = parseFmtpInt(fmtp, "indexdeltalength", 3);
        byte[] asc = decodeHex(parseFmtpString(fmtp, "config"));

        int audioObjectType = 2;
        int sampleRateIndex = sampleRateIndex(clockRate);
        if (asc != null && asc.length >= 2) {
            int bits = ((asc[0] & 0xFF) << 8) | (asc[1] & 0xFF);
            audioObjectType = Math.max(1, (bits >> 11) & 0x1F);
            sampleRateIndex = (bits >> 7) & 0x0F;
            int ch = (bits >> 3) & 0x0F;
            if (ch > 0) {
                channelConfig = ch;
            }
        }
        return new AacConfig(
                payloadType,
                clockRate,
                channelConfig,
                audioObjectType,
                sampleRateIndex,
                sizeLength,
                indexLength,
                indexDeltaLength);
    }

    private static byte[][] parseSpropParameterSets(String sdpText) {
        if (sdpText == null || sdpText.isEmpty()) {
            return null;
        }
        String[] lines = sdpText.split("\r\n|\n");
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase();
            if (!lower.startsWith("a=fmtp:") || lower.indexOf("sprop-parameter-sets=") < 0) {
                continue;
            }
            int keyIndex = lower.indexOf("sprop-parameter-sets=");
            String value = line.substring(keyIndex + "sprop-parameter-sets=".length());
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) {
                value = value.substring(0, semicolon);
            }
            String[] parts = value.split(",");
            byte[] sps = decodeB64(parts, 0);
            byte[] pps = decodeB64(parts, 1);
            return new byte[][]{sps, pps};
        }
        return null;
    }

    private static byte[] decodeB64(String[] parts, int idx) {
        if (parts == null || idx < 0 || idx >= parts.length) {
            return null;
        }
        String value = parts[idx] == null ? "" : parts[idx].trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int parseFmtpInt(String fmtp, String key, int fallback) {
        String raw = parseFmtpString(fmtp, key);
        return parseIntSafe(raw, fallback);
    }

    private static String parseFmtpString(String fmtp, String key) {
        if (fmtp == null || fmtp.trim().isEmpty() || key == null || key.trim().isEmpty()) {
            return null;
        }
        String[] parts = fmtp.split(";");
        String target = key.trim().toLowerCase(Locale.ROOT);
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (!target.equals(k)) {
                continue;
            }
            String v = token.substring(eq + 1).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private static int parseIntSafe(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static byte[] decodeHex(String value) {
        if (value == null) {
            return null;
        }
        String hex = value.trim();
        if (hex.isEmpty() || (hex.length() & 1) != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int sampleRateIndex(int sampleRate) {
        final int[] rates = new int[]{
                96000, 88200, 64000, 48000, 44100, 32000,
                24000, 22050, 16000, 12000, 11025, 8000, 7350
        };
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == sampleRate) {
                return i;
            }
        }
        return 3; // default 48000
    }

    private static int readRtpTimestamp(ByteBuf rtpPacket) {
        if (rtpPacket == null || rtpPacket.readableBytes() < 12) {
            return Integer.MIN_VALUE;
        }
        int ri = rtpPacket.readerIndex();
        if (ri + 8 > rtpPacket.writerIndex()) {
            return Integer.MIN_VALUE;
        }
        return rtpPacket.getInt(ri + 4);
    }

    private static int readRtpPayloadType(ByteBuf rtpPacket) {
        if (rtpPacket == null || rtpPacket.readableBytes() < 2) {
            return -1;
        }
        int ri = rtpPacket.readerIndex();
        if (ri + 2 > rtpPacket.writerIndex()) {
            return -1;
        }
        return rtpPacket.getUnsignedByte(ri + 1) & 0x7F;
    }

    private static List<byte[]> extractAacAccessUnits(ByteBuf rtpPacket, AacConfig config) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (rtpPacket == null || config == null) {
            return out;
        }
        int hdrLen = RtpHeader.headerLength(rtpPacket);
        if (hdrLen < 0 || rtpPacket.readableBytes() <= hdrLen + 2) {
            return out;
        }
        int payloadStart = rtpPacket.readerIndex() + hdrLen;
        int payloadEnd = rtpPacket.readerIndex() + rtpPacket.readableBytes();
        int auHeaderBits = rtpPacket.getUnsignedShort(payloadStart);
        int auHeaderBytes = (auHeaderBits + 7) / 8;
        int headerStart = payloadStart + 2;
        int dataStart = headerStart + auHeaderBytes;
        if (dataStart > payloadEnd) {
            return out;
        }
        int bitPos = 0;
        int dataOff = dataStart;
        int index = 0;
        while (bitPos < auHeaderBits) {
            int indexBits = index == 0 ? config.indexLength : config.indexDeltaLength;
            if (bitPos + config.sizeLength + indexBits > auHeaderBits) {
                break;
            }
            int auSize = readBits(rtpPacket, headerStart, bitPos, config.sizeLength);
            bitPos += config.sizeLength;
            if (indexBits > 0) {
                bitPos += indexBits;
            }
            if (auSize <= 0 || dataOff + auSize > payloadEnd) {
                break;
            }
            byte[] frame = new byte[auSize];
            rtpPacket.getBytes(dataOff, frame);
            out.add(frame);
            dataOff += auSize;
            index++;
        }
        return out;
    }

    private static int readBits(ByteBuf buf, int baseOffset, int bitOffset, int bitLength) {
        int value = 0;
        for (int i = 0; i < bitLength; i++) {
            int absoluteBit = bitOffset + i;
            int byteIndex = baseOffset + (absoluteBit >> 3);
            int bitIndex = 7 - (absoluteBit & 0x07);
            int bit = (buf.getUnsignedByte(byteIndex) >> bitIndex) & 0x01;
            value = (value << 1) | bit;
        }
        return value;
    }

    private static final class SdpHints {
        private final byte[] sps;
        private final byte[] pps;
        private final AacConfig aacConfig;

        private SdpHints(byte[] sps, byte[] pps, AacConfig aacConfig) {
            this.sps = sps;
            this.pps = pps;
            this.aacConfig = aacConfig;
        }
    }

    private static final class RtmpAnnexbFrame {
        private final byte[] annexb;
        private final boolean hasIdr;
        private final boolean hasSps;
        private final boolean hasPps;

        private RtmpAnnexbFrame(byte[] annexb, boolean hasIdr, boolean hasSps, boolean hasPps) {
            this.annexb = annexb;
            this.hasIdr = hasIdr;
            this.hasSps = hasSps;
            this.hasPps = hasPps;
        }
    }

    private static final class AacConfig {
        private final int payloadType;
        private final int clockRate;
        private final int channelConfig;
        private final int audioObjectType;
        private final int sampleRateIndex;
        private final int sizeLength;
        private final int indexLength;
        private final int indexDeltaLength;

        private AacConfig(
                int payloadType,
                int clockRate,
                int channelConfig,
                int audioObjectType,
                int sampleRateIndex,
                int sizeLength,
                int indexLength,
                int indexDeltaLength) {
            this.payloadType = payloadType;
            this.clockRate = Math.max(1, clockRate);
            this.channelConfig = Math.max(1, channelConfig);
            this.audioObjectType = Math.max(1, audioObjectType);
            this.sampleRateIndex = Math.max(0, Math.min(12, sampleRateIndex));
            this.sizeLength = Math.max(1, sizeLength);
            this.indexLength = Math.max(0, indexLength);
            this.indexDeltaLength = Math.max(0, indexDeltaLength);
        }

        private int frameDuration90k() {
            return (int) ((1024L * CLOCK_90K) / clockRate);
        }
    }

    private static String safePathPart(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "default";
        }
        String value = raw.trim();
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-'
                    || c == '.') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.length() == 0 ? "default" : out.toString();
    }
}
