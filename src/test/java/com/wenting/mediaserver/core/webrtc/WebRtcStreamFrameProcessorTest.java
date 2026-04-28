package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import com.wenting.mediaserver.core.transcode.EncodedMediaPacket;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcStreamFrameProcessorTest {

    @Test
    void shouldMapRtmpPacketsToSessionMediaState() {
        WebRtcSessionManager manager = new WebRtcSessionManager(60_000L);
        StreamKey key = new StreamKey(StreamProtocol.RTMP, "live", "cam1");
        WebRtcSession session = manager.createPlaybackSession(key, "v=0\r\n", "127.0.0.1");
        WebRtcStreamFrameProcessor processor = new WebRtcStreamFrameProcessor(manager);

        EncodedMediaPacket videoSeq = new EncodedMediaPacket(
                EncodedMediaPacket.SourceProtocol.RTMP,
                EncodedMediaPacket.TrackType.VIDEO,
                EncodedMediaPacket.CodecType.H264,
                EncodedMediaPacket.PayloadFormat.RTMP_TAG,
                0,
                1,
                Unpooled.wrappedBuffer(new byte[]{0x17, 0x00, 0x00}));
        EncodedMediaPacket videoKey = new EncodedMediaPacket(
                EncodedMediaPacket.SourceProtocol.RTMP,
                EncodedMediaPacket.TrackType.VIDEO,
                EncodedMediaPacket.CodecType.H264,
                EncodedMediaPacket.PayloadFormat.RTMP_TAG,
                33,
                1,
                Unpooled.wrappedBuffer(new byte[]{0x17, 0x01, 0x00}));
        EncodedMediaPacket audio = new EncodedMediaPacket(
                EncodedMediaPacket.SourceProtocol.RTMP,
                EncodedMediaPacket.TrackType.AUDIO,
                EncodedMediaPacket.CodecType.AAC,
                EncodedMediaPacket.PayloadFormat.RTMP_TAG,
                33,
                1,
                Unpooled.wrappedBuffer(new byte[]{(byte) 0xAF, 0x01, 0x00}));
        try {
            processor.onPacket(key, videoSeq);
            processor.onPacket(key, videoKey);
            processor.onPacket(key, audio);
        } finally {
            videoSeq.release();
            videoKey.release();
            audio.release();
        }

        assertTrue(session.mediaAttachedAtMs() > 0);
        assertTrue(session.mediaLastPacketAtMs() > 0);
        assertEquals("rtmp", session.mediaSourceProtocol());
        assertEquals("rtmp_tag", session.mediaPayloadFormat());
        assertTrue(session.mediaVideoConfigSeen());
        assertTrue(session.mediaVideoKeyFrameSeen());
        assertEquals(2L, session.mediaVideoPackets());
        assertEquals(1L, session.mediaAudioPackets());
    }

    @Test
    void shouldIgnoreOtherStreamsAndDetectRtspIdr() {
        WebRtcSessionManager manager = new WebRtcSessionManager(60_000L);
        StreamKey key1 = new StreamKey(StreamProtocol.RTSP, "live", "cam1");
        StreamKey key2 = new StreamKey(StreamProtocol.RTSP, "live", "cam2");
        WebRtcSession session1 = manager.createPlaybackSession(key1, "v=0\r\n", "127.0.0.1");
        WebRtcSession session2 = manager.createPlaybackSession(key2, "v=0\r\n", "127.0.0.1");
        WebRtcStreamFrameProcessor processor = new WebRtcStreamFrameProcessor(manager);

        byte[] rtpIdr = new byte[] {
                (byte) 0x80, (byte) 96, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x65, 0x00
        };
        EncodedMediaPacket pkt = new EncodedMediaPacket(
                EncodedMediaPacket.SourceProtocol.RTSP,
                EncodedMediaPacket.TrackType.VIDEO,
                EncodedMediaPacket.CodecType.H264,
                EncodedMediaPacket.PayloadFormat.RTP_PACKET,
                0,
                1,
                Unpooled.wrappedBuffer(rtpIdr));
        try {
            processor.onPacket(key1, pkt);
        } finally {
            pkt.release();
        }

        assertEquals(1L, session1.mediaVideoPackets());
        assertTrue(session1.mediaVideoKeyFrameSeen());
        assertEquals(0L, session2.mediaVideoPackets());
        assertFalse(session2.mediaVideoKeyFrameSeen());
    }
}
