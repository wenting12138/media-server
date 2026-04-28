package com.wenting.mediaserver.core.webrtc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcBcDtlsEngineTest {

    @Test
    void shouldCreateEngineWithSha256Fingerprint() throws Exception {
        WebRtcBcDtlsEngine engine = WebRtcBcDtlsEngine.create();

        assertTrue(engine.localFingerprint().startsWith("sha-256 "));
    }
}
