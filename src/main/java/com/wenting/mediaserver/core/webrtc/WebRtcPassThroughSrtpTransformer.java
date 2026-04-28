package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;

/**
 * Placeholder transformer before real SRTP keying is integrated.
 */
public final class WebRtcPassThroughSrtpTransformer implements WebRtcSrtpTransformer {

    public static final WebRtcPassThroughSrtpTransformer INSTANCE = new WebRtcPassThroughSrtpTransformer();

    private WebRtcPassThroughSrtpTransformer() {
    }

    @Override
    public boolean isProtectedTransport() {
        return false;
    }

    @Override
    public ByteBuf protectRtp(ByteBuf plainRtp) {
        if (plainRtp == null || !plainRtp.isReadable()) {
            return null;
        }
        return plainRtp.retainedDuplicate();
    }

    @Override
    public ByteBuf protectRtcp(ByteBuf plainRtcp) {
        if (plainRtcp == null || !plainRtcp.isReadable()) {
            return null;
        }
        return plainRtcp.retainedDuplicate();
    }
}
