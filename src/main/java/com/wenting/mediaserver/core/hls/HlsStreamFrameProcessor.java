package com.wenting.mediaserver.core.hls;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.EncodedMediaPacket;
import com.wenting.mediaserver.core.transcode.RtpH264AccessUnitNormalizer;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;
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
 * MVP scope: video-only live HLS.
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
        if (packet.sourceProtocol() != EncodedMediaPacket.SourceProtocol.RTSP
                || packet.trackType() != EncodedMediaPacket.TrackType.VIDEO
                || packet.codecType() != EncodedMediaPacket.CodecType.H264
                || packet.payloadFormat() != EncodedMediaPacket.PayloadFormat.RTP_PACKET) {
            return;
        }
        session.onRtpPacket(packet.payload());
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
        private SegmentWriter currentSegment;
        private int nextSequence;
        private int currentSegmentStartPts90k;
        private int lastPts90k;

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
            this.lastPts90k = Integer.MIN_VALUE;
            prepareStreamDirectory();
            seedParameterSetsFromSdp(sdpText);
        }

        private synchronized void onRtpPacket(ByteBuf rtpPacket) {
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
            int pts90k = accessUnit.timestamp90k();
            if (currentSegment == null) {
                if (!accessUnit.keyFrame()) {
                    return;
                }
                startNewSegment(pts90k);
            } else if (accessUnit.keyFrame() && elapsed90k(currentSegmentStartPts90k, pts90k) >= segmentDuration90k) {
                finalizeCurrentSegment(pts90k);
                startNewSegment(pts90k);
            }
            if (currentSegment == null) {
                return;
            }
            currentSegment.writeAccessUnit(accessUnit.annexB(), pts90k);
            lastPts90k = pts90k;
        }

        private void startNewSegment(int pts90k) {
            String segmentFile = String.format(Locale.US, "seg_%06d.ts", nextSequence);
            Path segmentPath = streamDir.resolve(segmentFile);
            currentSegment = new SegmentWriter(nextSequence, segmentFile, segmentPath);
            currentSegmentStartPts90k = pts90k;
            lastPts90k = pts90k;
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

        private void seedParameterSetsFromSdp(String sdpText) {
            byte[][] sets = parseSpropParameterSets(sdpText);
            if (sets == null) {
                return;
            }
            byte[] sps = sets[0];
            byte[] pps = sets[1];
            if ((sps == null || sps.length == 0) && (pps == null || pps.length == 0)) {
                return;
            }
            normalizer.seedParameterSets(sps, pps);
            log.info("HLS seeded H264 parameter sets stream={} sps={}B pps={}B",
                    key.path(),
                    sps == null ? 0 : sps.length,
                    pps == null ? 0 : pps.length);
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
                int closingPts = lastPts90k;
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
        private final TsMuxer muxer = new TsMuxer();
        private boolean closed;

        private SegmentWriter(int sequence, String fileName, Path path) {
            this.sequence = sequence;
            this.fileName = fileName;
            this.path = path;
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

        private void writeAccessUnit(ByteBuf annexbAccessUnit, int pts90k) {
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
        private int ccPat;
        private int ccPmt;
        private int ccVideo;

        private void writePatPmt(OutputStream out) throws IOException {
            writePsiPacket(out, PID_PAT, buildPatSection(), true);
            writePsiPacket(out, PID_PMT, buildPmtSection(), true);
        }

        private void writeH264AccessUnit(OutputStream out, byte[] annexbAccessUnit, long pts90k) throws IOException {
            byte[] pes = buildPes(annexbAccessUnit, pts90k);
            writeTsPayload(out, PID_VIDEO, pes, true);
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
            int value = ccVideo;
            ccVideo = (ccVideo + 1) & 0x0F;
            return value;
        }

        private byte[] buildPes(byte[] annexb, long pts90k) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(annexb.length + 32);
            out.write(0x00);
            out.write(0x00);
            out.write(0x01);
            out.write(0xE0);
            out.write(0x00);
            out.write(0x00);
            out.write(0x80);
            out.write(0x80);
            out.write(0x05);
            writePts(out, pts90k);
            out.write(annexb);
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
            ByteArrayOutputStream out = new ByteArrayOutputStream(64);
            out.write(0x02);
            writeShort(out, 0xB000 | 0x0012);
            writeShort(out, 0x0001);
            out.write(0xC1);
            out.write(0x00);
            out.write(0x00);
            writeShort(out, 0xE000 | PID_VIDEO);
            writeShort(out, 0xF000);
            out.write(0x1B);
            writeShort(out, 0xE000 | PID_VIDEO);
            writeShort(out, 0xF000);
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
    }

    private static long elapsed90k(int startPts90k, int endPts90k) {
        return ((endPts90k & 0xFFFFFFFFL) - (startPts90k & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
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
