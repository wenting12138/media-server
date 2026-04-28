package com.wenting.mediaserver.core.webrtc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebRtcOfferInfoTest {

    @Test
    void shouldParseOfferInfo() {
        WebRtcOfferInfo info = WebRtcOfferInfo.parse(
                "v=0\r\n"
                        + "o=- 1 2 IN IP4 127.0.0.1\r\n"
                        + "s=-\r\n"
                        + "t=0 0\r\n"
                        + "a=ice-ufrag:u1\r\n"
                        + "a=ice-pwd:pwdpwdpwdpwdpwdpwdpwdpwd\r\n"
                        + "a=fingerprint:sha-256 11:22\r\n"
                        + "a=setup:actpass\r\n"
                        + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                        + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n");

        assertEquals("u1", info.iceUfrag());
        assertEquals("pwdpwdpwdpwdpwdpwdpwdpwd", info.icePwd());
        assertEquals("sha-256 11:22", info.fingerprint());
        assertEquals("actpass", info.setupRole());
        assertEquals(2, info.mediaCount());
        assertEquals(1, info.audioMediaCount());
        assertEquals(1, info.videoMediaCount());
    }

    @Test
    void shouldRejectMissingFingerprint() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                WebRtcOfferInfo.parse(
                        "v=0\r\n"
                                + "a=ice-ufrag:u1\r\n"
                                + "a=ice-pwd:pwdpwdpwdpwdpwdpwdpwdpwd\r\n"
                                + "a=setup:actpass\r\n"
                                + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n");
            }
        });
    }
}
