package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;

/**
 * SRTP/SRTCP packet protection abstraction.
 */
public interface WebRtcSrtpTransformer {

    /**
     * @return true when packets are cryptographically protected (real SRTP), false for placeholder/pass-through.
     */
    boolean isProtectedTransport();

    /**
     * Protect outbound RTP packet. Returns owned buffer for sending or null when packet should be dropped.
     */
    ByteBuf protectRtp(ByteBuf plainRtp);

    /**
     * Protect outbound RTCP packet. Returns owned buffer for sending or null when packet should be dropped.
     */
    ByteBuf protectRtcp(ByteBuf plainRtcp);
}
