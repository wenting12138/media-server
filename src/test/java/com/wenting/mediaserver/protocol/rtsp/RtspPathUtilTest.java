package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RtspPathUtilTest {

    @Test
    void streamKeyIgnoresStreamIdSegment() {
        StreamKey k = RtspPathUtil.streamKeyFromRtspUri("rtsp://127.0.0.1:1554/live/123456/streamid=0");
        assertEquals("live", k.app());
        assertEquals("123456", k.stream());
    }

    @Test
    void parsesStreamTrackId() {
        assertEquals(Optional.of(0), RtspPathUtil.streamTrackIdFromRtspUri("rtsp://h/live/s/streamid=0"));
        assertEquals(Optional.of(1), RtspPathUtil.streamTrackIdFromRtspUri("rtsp://h/live/s/streamid=1"));
        assertEquals(Optional.of(2), RtspPathUtil.streamTrackIdFromRtspUri("rtsp://h/live/s/trackid=2"));
        assertFalse(RtspPathUtil.streamTrackIdFromRtspUri("rtsp://h/live/s").isPresent());
    }
}
