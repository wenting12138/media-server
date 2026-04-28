package com.wenting.mediaserver.core.webrtc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcSdpAnswerGeneratorTest {

    @Test
    void shouldUseProvidedDtlsFingerprint() {
        String fp = "sha-256 AA:BB:CC:DD:EE:FF";
        WebRtcSdpAnswerGenerator generator = new WebRtcSdpAnswerGenerator(fp);

        String answer = generator.createAnswer(minimalOffer(), "session1", "127.0.0.1");

        assertTrue(answer.contains("a=fingerprint:" + fp));
    }

    @Test
    void shouldFallbackToSyntheticFingerprintWhenProvidedValueInvalid() {
        WebRtcSdpAnswerGenerator generator = new WebRtcSdpAnswerGenerator("not-a-fingerprint");

        String answer = generator.createAnswer(minimalOffer(), "session2", "127.0.0.1");

        assertTrue(answer.contains("a=fingerprint:sha-256 "));
    }

    private static String minimalOffer() {
        return "v=0\r\n"
                + "o=- 123456789 2 IN IP4 127.0.0.1\r\n"
                + "s=-\r\n"
                + "t=0 0\r\n"
                + "a=group:BUNDLE 0\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=ice-ufrag:test\r\n"
                + "a=ice-pwd:testtesttesttesttesttest\r\n"
                + "a=fingerprint:sha-256 11:22\r\n"
                + "a=setup:actpass\r\n"
                + "a=mid:0\r\n"
                + "a=sendrecv\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:96 H264/90000\r\n";
    }
}
