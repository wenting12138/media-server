package com.wenting.mediaserver.protocol.rtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Packs H264 NAL units into RTP packets per RFC 6184.
 * Supports Single NAL and FU-A fragmentation.
 */
public final class H264RtpPacketizer {
    private static final Logger log = LoggerFactory.getLogger(H264RtpPacketizer.class);
    private static final int MAX_RTP_PAYLOAD = 1400;
    private static final int NAL_FU_A = 28;
    private static final int H264_PAYLOAD_TYPE = 96;
    private static final int RTP_VERSION = 2;
    private static final int SSRC = 0x12345678;

    private final AtomicInteger sequenceNumber = new AtomicInteger(0);

    public void packetize(ByteBuf nalUnit, int timestamp90khz, Consumer<ByteBuf> onRtp) {
        packetize(nalUnit, timestamp90khz, true, onRtp);
    }

    public void packetize(ByteBuf nalUnit, int timestamp90khz, boolean markerForAccessUnitEnd, Consumer<ByteBuf> onRtp) {
        if (nalUnit == null || !nalUnit.isReadable()) {
            return;
        }
        int nalLen = nalUnit.readableBytes();
        if (nalLen <= MAX_RTP_PAYLOAD) {
            ByteBuf rtp = createRtpHeader(timestamp90khz, markerForAccessUnitEnd, nalLen);
            rtp.writeBytes(nalUnit);
            onRtp.accept(rtp);
        } else {
            int nalHeader = nalUnit.readUnsignedByte();
            int nalType = nalHeader & 0x1F;
            int fuIndicator = (nalHeader & 0xE0) | NAL_FU_A;
            int remaining = nalUnit.readableBytes();
            int offset = nalUnit.readerIndex();
            boolean first = true;
            while (remaining > 0) {
                int chunk = Math.min(remaining, MAX_RTP_PAYLOAD - 2);
                boolean last = remaining == chunk;
                int fuHeader = nalType;
                if (first) {
                    fuHeader |= 0x80;
                }
                if (last) {
                    fuHeader |= 0x40;
                }
                ByteBuf rtp = createRtpHeader(timestamp90khz, markerForAccessUnitEnd && last, chunk + 2);
                rtp.writeByte(fuIndicator);
                rtp.writeByte(fuHeader);
                rtp.writeBytes(nalUnit, offset, chunk);
                onRtp.accept(rtp);
                first = false;
                remaining -= chunk;
                offset += chunk;
            }
        }
    }

    private ByteBuf createRtpHeader(int timestamp90khz, boolean marker, int payloadLen) {
        ByteBuf buf = Unpooled.buffer(12 + payloadLen);
        int seq = sequenceNumber.getAndIncrement() & 0xFFFF;
        buf.writeByte((RTP_VERSION << 6));
        buf.writeByte((marker ? 0x80 : 0x00) | H264_PAYLOAD_TYPE);
        buf.writeShort(seq);
        buf.writeInt(timestamp90khz);
        buf.writeInt(SSRC);
        return buf;
    }
}
