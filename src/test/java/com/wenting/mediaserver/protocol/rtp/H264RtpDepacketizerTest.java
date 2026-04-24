package com.wenting.mediaserver.protocol.rtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class H264RtpDepacketizerTest {

    @Test
    void singleNalUnit() {
        H264RtpDepacketizer d = new H264RtpDepacketizer();
        AtomicInteger nals = new AtomicInteger();
        ByteBuf rtp = Unpooled.buffer();
        rtp.writeByte(0x80);
        rtp.writeByte(0xe0);
        rtp.writeShort(0);
        rtp.writeInt(0);
        rtp.writeInt(0x11223344);
        rtp.writeByte(0x67);
        rtp.writeByte(0x42);
        rtp.writeByte(0x11);
        d.ingest(rtp, nal -> {
            try {
                nals.incrementAndGet();
                assertTrue(nal.readableBytes() >= 5);
                assertEquals(0, nal.getUnsignedByte(0));
                assertEquals(0, nal.getUnsignedByte(1));
                assertEquals(0, nal.getUnsignedByte(2));
                assertEquals(1, nal.getUnsignedByte(3));
                assertEquals(0x67, nal.getUnsignedByte(4));
            } finally {
                ReferenceCountUtil.release(nal);
            }
        });
        assertEquals(1, nals.get());
        rtp.release();
    }

    @Test
    void stapAEmitsTwoNals() {
        H264RtpDepacketizer d = new H264RtpDepacketizer();
        AtomicInteger nals = new AtomicInteger();
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(0x18);
        payload.writeShort(2);
        payload.writeByte(0x67);
        payload.writeByte(0xaa);
        payload.writeShort(2);
        payload.writeByte(0x68);
        payload.writeByte(0xbb);

        ByteBuf rtp = rtpWrap(payload);
        d.ingest(rtp, nal -> {
            try {
                nals.incrementAndGet();
            } finally {
                ReferenceCountUtil.release(nal);
            }
        });
        assertEquals(2, nals.get());
        rtp.release();
        payload.release();
    }

    @Test
    void fuADropsNalOnSequenceGap() {
        H264RtpDepacketizer d = new H264RtpDepacketizer();
        AtomicInteger nals = new AtomicInteger();
        AtomicReference<ByteBuf> last = new AtomicReference<ByteBuf>();

        ByteBuf start = rtpWrapFuA(100, true, false, 5, new byte[]{0x11, 0x22});
        ByteBuf midLost = rtpWrapFuA(102, false, false, 5, new byte[]{0x33, 0x44}); // seq=101 lost
        ByteBuf end = rtpWrapFuA(103, false, true, 5, new byte[]{0x55, 0x66});
        try {
            d.ingest(start, nal -> {
                nals.incrementAndGet();
                last.set(nal);
            });
            d.ingest(midLost, nal -> {
                nals.incrementAndGet();
                last.set(nal);
            });
            d.ingest(end, nal -> {
                nals.incrementAndGet();
                last.set(nal);
            });
            assertEquals(0, nals.get());
            assertNull(last.get());
        } finally {
            start.release();
            midLost.release();
            end.release();
            ReferenceCountUtil.release(last.get());
        }
    }

    private static ByteBuf rtpWrap(ByteBuf nalPayload) {
        ByteBuf rtp = Unpooled.buffer(12 + nalPayload.readableBytes());
        rtp.writeByte(0x80);
        rtp.writeByte(0x60);
        rtp.writeShort(1);
        rtp.writeInt(0);
        rtp.writeInt(0x11223344);
        rtp.writeBytes(nalPayload);
        return rtp;
    }

    private static ByteBuf rtpWrapFuA(int seq, boolean start, boolean end, int nalType, byte[] fragmentPayload) {
        ByteBuf rtp = Unpooled.buffer(12 + 2 + fragmentPayload.length);
        rtp.writeByte(0x80);
        rtp.writeByte(0x60);
        rtp.writeShort(seq & 0xFFFF);
        rtp.writeInt(0);
        rtp.writeInt(0x11223344);
        int nri = 3 << 5;
        rtp.writeByte(nri | 28); // FU-A indicator
        int fuHeader = nalType & 0x1F;
        if (start) {
            fuHeader |= 0x80;
        }
        if (end) {
            fuHeader |= 0x40;
        }
        rtp.writeByte(fuHeader);
        rtp.writeBytes(fragmentPayload);
        return rtp;
    }
}
