package com.wenting.mediaserver.protocol.rtsp;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

/**
 * One RTP or RTCP payload received inside RTSP TCP interleaving ($ ch hi lo payload).
 * Owns {@code payload} until passed downstream; handler must release.
 */
public final class InterleavedRtpPacket {

    private final int channel;
    private final ByteBuf payload;

    public InterleavedRtpPacket(int channel, ByteBuf payload) {
        this.channel = channel;
        this.payload = payload;
    }

    public int channel() {
        return channel;
    }

    public ByteBuf payload() {
        return payload;
    }

    public void release() {
        ReferenceCountUtil.release(payload);
    }
}
