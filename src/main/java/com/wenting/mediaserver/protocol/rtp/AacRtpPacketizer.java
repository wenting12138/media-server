package com.wenting.mediaserver.protocol.rtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Packs AAC frames into RTP packets (simplified RFC 3640-like mode).
 * Each AAC frame is sent as one RTP packet with a 2-byte size prefix.
 */
public final class AacRtpPacketizer {
    private static final int AAC_PAYLOAD_TYPE = 97;
    private static final int RTP_VERSION = 2;
    private static final int SSRC = 0x12345679;

    private final AtomicInteger sequenceNumber = new AtomicInteger(0);

    public void packetize(ByteBuf aacFrame, int timestamp, Consumer<ByteBuf> onRtp) {
        if (aacFrame == null || !aacFrame.isReadable()) {
            return;
        }
        int len = aacFrame.readableBytes();
        int seq = sequenceNumber.getAndIncrement() & 0xFFFF;
        ByteBuf rtp = Unpooled.buffer(12 + 2 + len);
        rtp.writeByte((RTP_VERSION << 6));
        rtp.writeByte(0x80 | AAC_PAYLOAD_TYPE); // marker=1
        rtp.writeShort(seq);
        rtp.writeInt(timestamp);
        rtp.writeInt(SSRC);
        // AU-header-section: AU-headers-length (16 bits) = 16, then AU-header (16 bits) = len << 3
        rtp.writeShort(16);
        rtp.writeShort(len << 3);
        rtp.writeBytes(aacFrame);
        onRtp.accept(rtp);
    }
}
