package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;

class WebRtcSessionManagerTest {

    @Test
    void shouldCreateAndRemoveSession() {
        WebRtcSessionManager manager = new WebRtcSessionManager(60_000L);
        StreamKey key = new StreamKey(StreamProtocol.RTSP, "live", "cam_webrtc");
        WebRtcSession session = manager.createPlaybackSession(key, "v=0\r\n", "127.0.0.1");

        assertNotNull(session);
        assertNotNull(session.id());
        assertEquals(1, manager.sessionCount());
        assertEquals(1, manager.listSessions().size());
        assertEquals(session.id(), manager.get(session.id()).id());

        WebRtcSession removed = manager.remove(session.id());
        assertNotNull(removed);
        assertNull(manager.get(session.id()));
        assertEquals(0, manager.sessionCount());
    }

    @Test
    void shouldCleanupExpiredSessions() throws Exception {
        WebRtcSessionManager manager = new WebRtcSessionManager(1L);
        StreamKey key = new StreamKey(StreamProtocol.RTMP, "live", "cam_webrtc_2");
        WebRtcSession session = manager.createPlaybackSession(key, "v=0\r\n", "127.0.0.1");

        Thread.sleep(3L);
        int removed = manager.cleanupExpired(System.currentTimeMillis());

        assertTrue(removed >= 1);
        assertNull(manager.get(session.id()));
    }

    @Test
    void shouldStoreRemoteIceFragment() {
        WebRtcSessionManager manager = new WebRtcSessionManager(60_000L);
        StreamKey key = new StreamKey(StreamProtocol.RTMP, "live", "cam_webrtc_3");
        WebRtcSession session = manager.createPlaybackSession(key, "v=0\r\n", "127.0.0.1");

        WebRtcIceSdpFragment fragment = WebRtcIceSdpFragment.parse(
                "a=ice-ufrag:testu\r\n"
                        + "a=ice-pwd:testpwdtestpwdtestpwdtestpwd\r\n"
                        + "a=candidate:1 1 udp 2130706431 127.0.0.1 50000 typ host\r\n"
                        + "a=end-of-candidates\r\n");
        session.applyRemoteIceFragment(fragment);

        assertEquals("testu", session.remoteIceUfrag());
        assertEquals("testpwdtestpwdtestpwdtestpwd", session.remoteIcePwd());
        assertEquals(1, session.remoteCandidateCount());
        assertTrue(session.remoteEndOfCandidates());
        assertEquals("127.0.0.1", session.selectedRtpCandidateHost());
        assertEquals(50000, session.selectedRtpCandidatePort());
        assertEquals("host", session.selectedCandidateType());
        assertEquals("udp", session.selectedCandidateTransport());
        WebRtcSession found = manager.findBySelectedRtpEndpoint(new InetSocketAddress("127.0.0.1", 50000));
        assertNotNull(found);
        assertEquals(session.id(), found.id());
    }

    @Test
    void shouldGatePlainRelayByPolicy() {
        WebRtcSessionManager manager = new WebRtcSessionManager(60_000L);
        StreamKey key = new StreamKey(StreamProtocol.RTMP, "live", "cam_webrtc_4");
        WebRtcSession session = manager.createPlaybackSession(key, "v=0\r\n", "127.0.0.1");

        ByteBuf plain = Unpooled.wrappedBuffer(new byte[]{(byte) 0x80, 96, 0, 1});
        ByteBuf out1 = session.protectOutboundRtp(plain, false);
        assertNull(out1);

        session.setSrtpTransformer(WebRtcPassThroughSrtpTransformer.INSTANCE);
        ByteBuf out2 = session.protectOutboundRtp(plain, false);
        assertNull(out2);

        ByteBuf out3 = session.protectOutboundRtp(plain, true);
        assertNotNull(out3);
        assertEquals(4, out3.readableBytes());
        out3.release();
    }
}
