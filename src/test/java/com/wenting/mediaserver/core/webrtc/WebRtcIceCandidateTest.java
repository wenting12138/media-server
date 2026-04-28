package com.wenting.mediaserver.core.webrtc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcIceCandidateTest {

    @Test
    void shouldParseCandidateBody() {
        WebRtcIceCandidate candidate = WebRtcIceCandidate.parse(
                "candidate:1 1 udp 2130706431 10.10.10.5 54321 typ host generation 0");

        assertEquals("1", candidate.foundation());
        assertEquals(1, candidate.component());
        assertEquals("udp", candidate.transport());
        assertEquals(2130706431L, candidate.priority());
        assertEquals("10.10.10.5", candidate.address());
        assertEquals(54321, candidate.port());
        assertEquals("host", candidate.type());
        assertTrue(candidate.isUdp());
        assertTrue(candidate.isRtpComponent());
    }

    @Test
    void shouldRejectInvalidCandidate() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                WebRtcIceCandidate.parse("candidate:abc");
            }
        });
    }
}
