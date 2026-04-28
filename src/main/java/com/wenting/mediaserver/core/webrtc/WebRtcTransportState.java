package com.wenting.mediaserver.core.webrtc;

/**
 * Minimal transport lifecycle state for WebRTC media plane bootstrap.
 */
public enum WebRtcTransportState {
    NEW,
    ICE_CONNECTED,
    DTLS_CLIENT_HELLO_SEEN,
    DTLS_ESTABLISHED
}
