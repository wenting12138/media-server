package com.wenting.mediaserver.core.webrtc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcIceSdpFragmentTest {

    @Test
    void shouldParseTrickleFragment() {
        WebRtcIceSdpFragment fragment = WebRtcIceSdpFragment.parse(
                "a=ice-ufrag:u1\r\n"
                        + "a=ice-pwd:pwdpwdpwdpwdpwdpwdpwdpwd\r\n"
                        + "a=mid:0\r\n"
                        + "a=candidate:1 1 udp 2130706431 127.0.0.1 50000 typ host\r\n"
                        + "a=end-of-candidates\r\n");

        assertEquals("u1", fragment.iceUfrag());
        assertEquals("pwdpwdpwdpwdpwdpwdpwdpwd", fragment.icePwd());
        assertEquals(1, fragment.candidates().size());
        assertTrue(fragment.endOfCandidates());
    }

    @Test
    void shouldRejectNonIcePatchBody() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                WebRtcIceSdpFragment.parse("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n");
            }
        });
    }
}
