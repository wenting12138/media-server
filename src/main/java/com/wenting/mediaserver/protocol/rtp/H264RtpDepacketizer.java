package com.wenting.mediaserver.protocol.rtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import java.util.function.Consumer;

/**
 * RFC 6184 H264 RTP depacketization (single NAL, STAP-A, FU-A). Outputs Annex-B NALs ({@code 00 00 00 01} + RBSP).
 * <p>
 * Each {@code onNal} buffer is new retained memory; consumer must {@link ReferenceCountUtil#release} it.
 */
public final class H264RtpDepacketizer {

    private static final byte[] START_CODE = new byte[]{0, 0, 0, 1};

    private static final int NAL_STAP_A = 24;
    private static final int NAL_FU_A = 28;

    private ByteBuf fuBuffer;
    private int expectedFuNalType = -1;

    public void reset() {
        ReferenceCountUtil.release(fuBuffer);
        fuBuffer = null;
        expectedFuNalType = -1;
    }

    public void ingest(ByteBuf rtpPacket, Consumer<ByteBuf> onNal) {
        if (rtpPacket == null || !rtpPacket.isReadable()) {
            return;
        }
        int hdrLen = RtpHeader.headerLength(rtpPacket);
        if (hdrLen < 0 || rtpPacket.readableBytes() <= hdrLen) {
            return;
        }
        ByteBuf payload = rtpPacket.slice(rtpPacket.readerIndex() + hdrLen, rtpPacket.readableBytes() - hdrLen);
        if (!payload.isReadable()) {
            return;
        }
        int nalHeader = payload.getUnsignedByte(0);
        int nalType = nalHeader & 0x1F;

        if (nalType >= 1 && nalType <= 23) {
            emitSingleNal(payload, onNal);
        } else if (nalType == NAL_STAP_A) {
            emitStapA(payload, onNal);
        } else if (nalType == NAL_FU_A) {
            handleFuA(payload, onNal);
        } else {
            // STAP-B, MTAP, FU-B, padding: ignored for MVP
        }
    }

    private static void emitSingleNal(ByteBuf nalPayload, Consumer<ByteBuf> onNal) {
        ByteBuf out = Unpooled.buffer(START_CODE.length + nalPayload.readableBytes());
        out.writeBytes(START_CODE);
        out.writeBytes(nalPayload);
        onNal.accept(out);
    }

    private static void emitStapA(ByteBuf stapPayload, Consumer<ByteBuf> onNal) {
        int readerIndex = stapPayload.readerIndex();
        int off = readerIndex + 1;
        int end = readerIndex + stapPayload.readableBytes();
        while (off + 2 <= end) {
            int sz = stapPayload.getUnsignedShort(off);
            off += 2;
            if (off + sz > end || sz <= 0) {
                break;
            }
            ByteBuf nalSlice = stapPayload.slice(off, sz);
            emitSingleNal(nalSlice, onNal);
            off += sz;
        }
    }

    private void handleFuA(ByteBuf fuPayload, Consumer<ByteBuf> onNal) {
        if (fuPayload.readableBytes() < 2) {
            resetFuState();
            return;
        }
        int fuIndicator = fuPayload.getUnsignedByte(0);
        int fuHeader = fuPayload.getUnsignedByte(1);
        boolean start = (fuHeader & 0x80) != 0;
        boolean end = (fuHeader & 0x40) != 0;
        int nalType = fuHeader & 0x1F;
        int dataOffset = 2;
        int dataLen = fuPayload.readableBytes() - dataOffset;
        if (dataLen <= 0) {
            resetFuState();
            return;
        }

        if (start) {
            resetFuState();
            expectedFuNalType = nalType;
            int reconstructedNalHeader = (fuIndicator & 0xE0) | nalType;
            fuBuffer = Unpooled.buffer();
            fuBuffer.writeByte(reconstructedNalHeader);
        } else if (fuBuffer == null || expectedFuNalType != nalType) {
            resetFuState();
            return;
        }

        fuBuffer.writeBytes(fuPayload.slice(fuPayload.readerIndex() + dataOffset, dataLen));
        if (end) {
            ByteBuf nalBody = fuBuffer;
            fuBuffer = null;
            expectedFuNalType = -1;
            ByteBuf out = Unpooled.buffer(START_CODE.length + nalBody.readableBytes());
            out.writeBytes(START_CODE);
            out.writeBytes(nalBody);
            nalBody.release();
            onNal.accept(out);
        }
    }

    private void resetFuState() {
        ReferenceCountUtil.release(fuBuffer);
        fuBuffer = null;
        expectedFuNalType = -1;
    }
}
