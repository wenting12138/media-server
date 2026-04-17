package com.wenting.mediaserver.protocol.rtsp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Writes RTP/RTCP in RTSP TCP interleaved framing. Releases {@code rtpPayload} (one reference count).
 */
public final class RtspInterleavedWriter {

    private RtspInterleavedWriter() {
    }

    public static ByteBuf frame(int channel, ByteBuf rtpPayload) {
        int n = rtpPayload.readableBytes();
        ByteBuf out = Unpooled.buffer(4 + n);
        out.writeByte(0x24);
        out.writeByte(channel & 0xFF);
        out.writeShort(n);
        out.writeBytes(rtpPayload);
        rtpPayload.release();
        return out;
    }
}
