package com.wenting.mediaserver.core.hls;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.EncodedMediaPacket;
import com.wenting.mediaserver.protocol.rtp.H264RtpPacketizer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsStreamFrameProcessorTest {

    @Test
    void generatesPlaylistAndTsSegmentsFromRtspRtpH264() throws Exception {
        Path root = Files.createTempDirectory("hls-processor-test");
        try {
            MediaServerConfig config = new MediaServerConfig(
                    18080,
                    1554,
                    11935,
                    20000,
                    30000,
                    true,
                    "ffmpeg",
                    "__wm",
                    "127.0.0.1",
                    512,
                    "java",
                    false,
                    true,
                    root.toString(),
                    1,
                    6,
                    true);
            HlsStreamFrameProcessor processor = new HlsStreamFrameProcessor(config);
            StreamKey key = new StreamKey("live", "cam1");
            H264RtpPacketizer packetizer = new H264RtpPacketizer();

            processor.onPublishStart(key, "v=0\r\n");
            emitAccessUnit(processor, key, packetizer, 0, new byte[][]{
                    sps(), pps(), idr()
            });
            emitAccessUnit(processor, key, packetizer, 45000, new byte[][]{
                    pFrame()
            });
            emitAccessUnit(processor, key, packetizer, 90000, new byte[][]{
                    idr()
            });
            processor.onPublishStop(key);
            processor.close();

            Path playlist = root.resolve("live").resolve("cam1").resolve("index.m3u8");
            assertTrue(Files.exists(playlist));
            String text = new String(Files.readAllBytes(playlist), StandardCharsets.UTF_8);
            assertTrue(text.contains("#EXTM3U"));
            assertTrue(text.contains("#EXT-X-TARGETDURATION:"));
            assertTrue(text.contains("#EXTINF:"));
            assertTrue(text.contains("seg_"));

            List<Path> segments = listTsFiles(root.resolve("live").resolve("cam1"));
            assertFalse(segments.isEmpty());
            byte[] first = Files.readAllBytes(segments.get(0));
            assertTrue(first.length >= 188);
            assertEquals(0x47, first[0] & 0xFF);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void seedsSpsPpsFromSdpWhenRtpDoesNotCarryParameterSets() throws Exception {
        Path root = Files.createTempDirectory("hls-processor-seed-test");
        try {
            MediaServerConfig config = new MediaServerConfig(
                    18080,
                    1554,
                    11935,
                    20000,
                    30000,
                    true,
                    "ffmpeg",
                    "__wm",
                    "127.0.0.1",
                    512,
                    "java",
                    false,
                    true,
                    root.toString(),
                    1,
                    6,
                    true);
            HlsStreamFrameProcessor processor = new HlsStreamFrameProcessor(config);
            StreamKey key = new StreamKey("live", "cam_seed");
            H264RtpPacketizer packetizer = new H264RtpPacketizer();
            String sdp = "v=0\r\n"
                    + "a=fmtp:96 packetization-mode=1;sprop-parameter-sets="
                    + Base64.getEncoder().encodeToString(sps())
                    + ","
                    + Base64.getEncoder().encodeToString(pps())
                    + "\r\n";

            processor.onPublishStart(key, sdp);
            emitAccessUnit(processor, key, packetizer, 0, new byte[][]{
                    idr()
            });
            emitAccessUnit(processor, key, packetizer, 90000, new byte[][]{
                    idr()
            });
            processor.onPublishStop(key);
            processor.close();

            List<Path> segments = listTsFiles(root.resolve("live").resolve("cam_seed"));
            assertFalse(segments.isEmpty());
            byte[] first = Files.readAllBytes(segments.get(0));
            assertTrue(indexOf(first, new byte[]{0x00, 0x00, 0x00, 0x01, 0x67}) >= 0);
            assertTrue(indexOf(first, new byte[]{0x00, 0x00, 0x00, 0x01, 0x68}) >= 0);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void muxesAacAudioIntoTsWhenSdpContainsMpeg4Generic() throws Exception {
        Path root = Files.createTempDirectory("hls-processor-aac-test");
        try {
            MediaServerConfig config = new MediaServerConfig(
                    18080,
                    1554,
                    11935,
                    20000,
                    30000,
                    true,
                    "ffmpeg",
                    "__wm",
                    "127.0.0.1",
                    512,
                    "java",
                    false,
                    true,
                    root.toString(),
                    1,
                    6,
                    true);
            HlsStreamFrameProcessor processor = new HlsStreamFrameProcessor(config);
            StreamKey key = new StreamKey("live", "cam_aac");
            H264RtpPacketizer packetizer = new H264RtpPacketizer();
            String sdp = "v=0\r\n"
                    + "m=audio 0 RTP/AVP 97\r\n"
                    + "a=rtpmap:97 MPEG4-GENERIC/48000/2\r\n"
                    + "a=fmtp:97 streamtype=5; profile-level-id=15; mode=AAC-hbr; config=1190; "
                    + "sizelength=13; indexlength=3; indexdeltalength=3;\r\n";

            processor.onPublishStart(key, sdp);
            emitAccessUnit(processor, key, packetizer, 0, new byte[][]{
                    sps(), pps(), idr()
            });
            emitAacRtp(processor, key, 97, 0, aacFrame());
            emitAacRtp(processor, key, 97, 1024, aacFrame());
            emitAccessUnit(processor, key, packetizer, 90000, new byte[][]{
                    idr()
            });
            processor.onPublishStop(key);
            processor.close();

            List<Path> segments = listTsFiles(root.resolve("live").resolve("cam_aac"));
            assertFalse(segments.isEmpty());
            byte[] first = Files.readAllBytes(segments.get(0));
            assertTrue(indexOf(first, new byte[]{0x0F, (byte) 0xE1, 0x02, (byte) 0xF0, 0x00}) >= 0);
            assertTrue(indexOf(first, new byte[]{(byte) 0xFF, (byte) 0xF1}) >= 0);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void muxesRtmpH264AacIntoTs() throws Exception {
        Path root = Files.createTempDirectory("hls-processor-rtmp-test");
        try {
            MediaServerConfig config = new MediaServerConfig(
                    18080,
                    1554,
                    11935,
                    20000,
                    30000,
                    true,
                    "ffmpeg",
                    "__wm",
                    "127.0.0.1",
                    512,
                    "java",
                    false,
                    true,
                    root.toString(),
                    1,
                    6,
                    true);
            HlsStreamFrameProcessor processor = new HlsStreamFrameProcessor(config);
            StreamKey key = new StreamKey("live", "cam_rtmp");
            processor.onPublishStart(key, "");

            emitRtmpVideoSeqHeader(processor, key);
            emitRtmpAacSeqHeader(processor, key);
            emitRtmpVideoNalu(processor, key, 0, true, new byte[][]{idr()});
            emitRtmpAacRaw(processor, key, 0, aacFrame());
            emitRtmpAacRaw(processor, key, 22, aacFrame());
            emitRtmpVideoNalu(processor, key, 1000, true, new byte[][]{idr()});

            processor.onPublishStop(key);
            processor.close();

            List<Path> segments = listTsFiles(root.resolve("live").resolve("cam_rtmp"));
            assertFalse(segments.isEmpty());
            byte[] first = Files.readAllBytes(segments.get(0));
            assertTrue(indexOf(first, new byte[]{0x1B, (byte) 0xE1, 0x01, (byte) 0xF0, 0x00}) >= 0);
            assertTrue(indexOf(first, new byte[]{0x0F, (byte) 0xE1, 0x02, (byte) 0xF0, 0x00}) >= 0);
            assertTrue(indexOf(first, new byte[]{(byte) 0xFF, (byte) 0xF1}) >= 0);
        } finally {
            deleteRecursively(root);
        }
    }

    private static void emitAccessUnit(
            HlsStreamFrameProcessor processor,
            StreamKey key,
            H264RtpPacketizer packetizer,
            int timestamp90k,
            byte[][] nals) {
        if (nals == null || nals.length == 0) {
            return;
        }
        for (int i = 0; i < nals.length; i++) {
            boolean marker = i == nals.length - 1;
            ByteBuf nal = Unpooled.wrappedBuffer(nals[i]);
            try {
                packetizer.packetize(nal, timestamp90k, marker, rtp -> {
                    EncodedMediaPacket packet = new EncodedMediaPacket(
                            EncodedMediaPacket.SourceProtocol.RTSP,
                            EncodedMediaPacket.TrackType.VIDEO,
                            EncodedMediaPacket.CodecType.H264,
                            EncodedMediaPacket.PayloadFormat.RTP_PACKET,
                            0,
                            1,
                            rtp.retainedDuplicate());
                    try {
                        processor.onPacket(key, packet);
                    } finally {
                        packet.release();
                        rtp.release();
                    }
                });
            } finally {
                nal.release();
            }
        }
    }

    private static byte[] sps() {
        return new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x50, 0x1E, (byte) 0xD0};
    }

    private static byte[] pps() {
        return new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80};
    }

    private static byte[] idr() {
        return new byte[]{0x65, (byte) 0x88, (byte) 0x84, 0x21};
    }

    private static byte[] pFrame() {
        return new byte[]{0x41, (byte) 0x9A, 0x22, 0x11};
    }

    private static byte[] aacFrame() {
        return new byte[]{0x11, 0x22, 0x33, 0x44, 0x55};
    }

    private static void emitAacRtp(
            HlsStreamFrameProcessor processor,
            StreamKey key,
            int payloadType,
            int timestamp,
            byte[] rawAac) {
        ByteBuf rtp = Unpooled.buffer(12 + 2 + 2 + rawAac.length);
        rtp.writeByte(0x80);
        rtp.writeByte(payloadType & 0x7F);
        rtp.writeShort(1);
        rtp.writeInt(timestamp);
        rtp.writeInt(0x12345678);
        rtp.writeShort(16);
        int auHeader = ((rawAac.length & 0x1FFF) << 3);
        rtp.writeShort(auHeader);
        rtp.writeBytes(rawAac);
        EncodedMediaPacket packet = new EncodedMediaPacket(
                EncodedMediaPacket.SourceProtocol.RTSP,
                EncodedMediaPacket.TrackType.AUDIO,
                EncodedMediaPacket.CodecType.UNKNOWN,
                EncodedMediaPacket.PayloadFormat.RTP_PACKET,
                0,
                1,
                rtp.retainedDuplicate());
        try {
            processor.onPacket(key, packet);
        } finally {
            packet.release();
            rtp.release();
        }
    }

    private static void emitRtmpVideoSeqHeader(HlsStreamFrameProcessor processor, StreamKey key) {
        byte[] sps = sps();
        byte[] pps = pps();
        ByteBuf tag = Unpooled.buffer(5 + 11 + sps.length + pps.length);
        tag.writeByte(0x17);
        tag.writeByte(0x00);
        tag.writeByte(0x00);
        tag.writeByte(0x00);
        tag.writeByte(0x00);
        tag.writeByte(0x01);
        tag.writeByte(sps[1] & 0xFF);
        tag.writeByte(sps[2] & 0xFF);
        tag.writeByte(sps[3] & 0xFF);
        tag.writeByte(0xFF);
        tag.writeByte(0xE1);
        tag.writeShort(sps.length);
        tag.writeBytes(sps);
        tag.writeByte(0x01);
        tag.writeShort(pps.length);
        tag.writeBytes(pps);
        emitRtmpPacket(processor, key, EncodedMediaPacket.TrackType.VIDEO, EncodedMediaPacket.CodecType.H264, 0, tag);
    }

    private static void emitRtmpVideoNalu(
            HlsStreamFrameProcessor processor,
            StreamKey key,
            int timestampMs,
            boolean keyFrame,
            byte[][] nals) {
        int size = 5;
        for (byte[] nal : nals) {
            size += 4 + nal.length;
        }
        ByteBuf tag = Unpooled.buffer(size);
        tag.writeByte(keyFrame ? 0x17 : 0x27);
        tag.writeByte(0x01);
        tag.writeByte(0x00);
        tag.writeByte(0x00);
        tag.writeByte(0x00);
        for (byte[] nal : nals) {
            tag.writeInt(nal.length);
            tag.writeBytes(nal);
        }
        emitRtmpPacket(processor, key, EncodedMediaPacket.TrackType.VIDEO, EncodedMediaPacket.CodecType.H264, timestampMs, tag);
    }

    private static void emitRtmpAacSeqHeader(HlsStreamFrameProcessor processor, StreamKey key) {
        ByteBuf tag = Unpooled.buffer(4);
        tag.writeByte(0xAF);
        tag.writeByte(0x00);
        tag.writeByte(0x11);
        tag.writeByte(0x90);
        emitRtmpPacket(processor, key, EncodedMediaPacket.TrackType.AUDIO, EncodedMediaPacket.CodecType.AAC, 0, tag);
    }

    private static void emitRtmpAacRaw(HlsStreamFrameProcessor processor, StreamKey key, int timestampMs, byte[] rawAac) {
        ByteBuf tag = Unpooled.buffer(2 + rawAac.length);
        tag.writeByte(0xAF);
        tag.writeByte(0x01);
        tag.writeBytes(rawAac);
        emitRtmpPacket(processor, key, EncodedMediaPacket.TrackType.AUDIO, EncodedMediaPacket.CodecType.AAC, timestampMs, tag);
    }

    private static void emitRtmpPacket(
            HlsStreamFrameProcessor processor,
            StreamKey key,
            EncodedMediaPacket.TrackType track,
            EncodedMediaPacket.CodecType codec,
            int timestampMs,
            ByteBuf payload) {
        EncodedMediaPacket packet = new EncodedMediaPacket(
                EncodedMediaPacket.SourceProtocol.RTMP,
                track,
                codec,
                EncodedMediaPacket.PayloadFormat.RTMP_TAG,
                timestampMs,
                1,
                payload.retainedDuplicate());
        try {
            processor.onPacket(key, packet);
        } finally {
            packet.release();
            payload.release();
        }
    }

    private static List<Path> listTsFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return new ArrayList<Path>();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ts"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean matched = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }
}
