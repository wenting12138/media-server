package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
