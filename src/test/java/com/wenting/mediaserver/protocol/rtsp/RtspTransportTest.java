package com.wenting.mediaserver.protocol.rtsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspTransportTest {

    @Test
    void parsesClientPorts() {
        String t = "RTP/AVP;unicast;client_port=5004-5005;mode=PLAY";
        assertArrayEquals(new int[]{5004, 5005}, RtspTransport.parseClientPorts(t));
    }

    @Test
    void detectsUdpTransport() {
        assertTrue(RtspTransport.isUdpTransport("RTP/AVP;unicast;client_port=5004-5005"));
        assertFalse(RtspTransport.isUdpTransport("RTP/AVP/TCP;unicast;interleaved=0-1"));
        assertFalse(RtspTransport.isUdpTransport("RTP/AVP;unicast"));
    }
}
