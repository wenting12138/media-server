package com.wenting.mediaserver.protocol.rtp;

import io.netty.buffer.ByteBuf;

/**
 * RTP fixed header + header extension length.
 */
public final class RtpHeader {

    private RtpHeader() {
    }

    /**
     * @return total header length in bytes, or -1 if buffer too short / malformed
     */
    public static int headerLength(ByteBuf rtp) {
        if (rtp.readableBytes() < 12) {
            return -1;
        }
        int b0 = rtp.getUnsignedByte(0);
        int cc = b0 & 0x0F;
        int x = (b0 >> 4) & 0x01;
        int len = 12 + 4 * cc;
        if (x == 0) {
            return len;
        }
        if (rtp.readableBytes() < len + 4) {
            return -1;
        }
        int extLenWords = rtp.getUnsignedShort(len + 2);
        len += 4 + extLenWords * 4;
        if (rtp.readableBytes() < len) {
            return -1;
        }
        return len;
    }

    public static int payloadType(ByteBuf rtp) {
        return rtp.getUnsignedByte(1) & 0x7F;
    }

    public static boolean marker(ByteBuf rtp) {
        return (rtp.getUnsignedByte(1) & 0x80) != 0;
    }
}
