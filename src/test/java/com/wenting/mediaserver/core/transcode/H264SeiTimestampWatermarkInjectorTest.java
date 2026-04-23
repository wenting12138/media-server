package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H264SeiTimestampWatermarkInjectorTest {

    @Test
    void injectsSeiOnKeyframeNaluPacket() {
        H264SeiTimestampWatermarkInjector injector = new H264SeiTimestampWatermarkInjector();
        StreamKey key = new StreamKey("live", "cam1");

        ByteBuf seqHeader = Unpooled.wrappedBuffer(new byte[]{
                0x17, 0x00, 0x00, 0x00, 0x00,
                0x01, 0x64, 0x00, 0x1f, (byte) 0xff, (byte) 0xe1
        });
        ByteBuf keyframeNalu = Unpooled.wrappedBuffer(new byte[]{
                0x17, 0x01, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x04,
                0x65, 0x11, 0x22, 0x33
        });

        ByteBuf seqOut = injector.injectIfNeeded(key, seqHeader, 0);
        ByteBuf out = injector.injectIfNeeded(key, keyframeNalu, 1000);
        try {
            assertEquals(seqHeader.readableBytes(), seqOut.readableBytes());
            assertTrue(out.readableBytes() > keyframeNalu.readableBytes());
            int reader = out.readerIndex();
            int seiLen = out.getInt(reader + 5);
            assertTrue(seiLen > 0);
            int seiNalType = out.getUnsignedByte(reader + 9) & 0x1F;
            assertEquals(6, seiNalType);
        } finally {
            seqOut.release();
            out.release();
            seqHeader.release();
            keyframeNalu.release();
        }
    }

    @Test
    void nonKeyframePassThroughSizeUnchanged() {
        H264SeiTimestampWatermarkInjector injector = new H264SeiTimestampWatermarkInjector();
        StreamKey key = new StreamKey("live", "cam2");
        ByteBuf nonKeyframe = Unpooled.wrappedBuffer(new byte[]{
                0x27, 0x01, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x02,
                0x41, 0x00
        });
        ByteBuf out = injector.injectIfNeeded(key, nonKeyframe, 33);
        try {
            assertEquals(nonKeyframe.readableBytes(), out.readableBytes());
        } finally {
            out.release();
            nonKeyframe.release();
        }
    }
}
