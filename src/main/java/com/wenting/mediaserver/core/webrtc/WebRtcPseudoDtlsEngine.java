package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;

/**
 * Placeholder DTLS engine:
 * marks session established after client-hello is seen and one more DTLS packet arrives.
 */
public final class WebRtcPseudoDtlsEngine implements WebRtcDtlsEngine {

    @Override
    public WebRtcDtlsEngineResult onPacket(WebRtcSession session, ByteBuf dtlsPacket, boolean clientHello) {
        if (session == null || dtlsPacket == null || !dtlsPacket.isReadable()) {
            return WebRtcDtlsEngineResult.noChange();
        }
        if (session.transportState() == WebRtcTransportState.DTLS_ESTABLISHED) {
            return WebRtcDtlsEngineResult.noChange();
        }
        if (clientHello) {
            return WebRtcDtlsEngineResult.noChange();
        }
        if (session.transportState() == WebRtcTransportState.DTLS_CLIENT_HELLO_SEEN) {
            return WebRtcDtlsEngineResult.established(WebRtcPassThroughSrtpTransformer.INSTANCE);
        }
        return WebRtcDtlsEngineResult.noChange();
    }
}
