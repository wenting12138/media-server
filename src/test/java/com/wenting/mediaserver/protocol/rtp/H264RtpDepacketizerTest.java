package com.wenting.mediaserver.protocol.rtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
