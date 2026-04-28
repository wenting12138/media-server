package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcPseudoDtlsEngineTest {

    @Test
    void shouldEstablishAfterClientHelloThenFollowup() {
        WebRtcSession session = new WebRtcSessionManager(60_000L).createPlaybackSession(
                new StreamKey(StreamProtocol.RTMP, "live", "cam_dtls_engine"),
                "v=0\r\n",
                "127.0.0.1");
        session.onDtlsPacket(true);

        WebRtcPseudoDtlsEngine engine = new WebRtcPseudoDtlsEngine();
        WebRtcDtlsEngineResult result = engine.onPacket(
                session,
                Unpooled.wrappedBuffer(new byte[]{23, (byte) 0xFE, (byte) 0xFD, 0x00, 0x00}),
                false);

        assertTrue(result.established());
        assertTrue(result.srtpTransformer() != null);
    }
}
