package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;

/**
 * DTLS handshake engine abstraction.
 */
public interface WebRtcDtlsEngine {

    WebRtcDtlsEngineResult onPacket(WebRtcSession session, ByteBuf dtlsPacket, boolean clientHello);

    default String localFingerprint() {
        return null;
    }
}
