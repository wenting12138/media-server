package com.wenting.mediaserver.protocol.flv;

import com.wenting.mediaserver.protocol.rtmp.RtmpConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlvWriterTest {

    @Test
    void shouldBuildFlvHeader() {
        ByteBuf header = FlvWriter.buildFlvHeader();
        try {
            assertEquals(13, header.readableBytes());
            assertEquals('F', header.readUnsignedByte());
            assertEquals('L', header.readUnsignedByte());
            assertEquals('V', header.readUnsignedByte());
            assertEquals(1, header.readUnsignedByte());
            assertEquals(0x05, header.readUnsignedByte());
            assertEquals(9, header.readInt());
            assertEquals(0, header.readInt());
        } finally {
            ReferenceCountUtil.safeRelease(header);
        }
    }

    @Test
    void shouldBuildFlvTagWithExtendedTimestamp() {
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[] {0x11, 0x22, 0x33});
        ByteBuf tag = null;
        try {
            tag = FlvWriter.buildFlvTag(RtmpConstants.TYPE_VIDEO, 0x01020304, payload);
            assertEquals(RtmpConstants.TYPE_VIDEO, tag.readUnsignedByte());
            assertEquals(0, tag.readUnsignedByte());
            assertEquals(0, tag.readUnsignedByte());
            assertEquals(3, tag.readUnsignedByte());
            assertEquals(0x02, tag.readUnsignedByte());
            assertEquals(0x03, tag.readUnsignedByte());
            assertEquals(0x04, tag.readUnsignedByte());
            assertEquals(0x01, tag.readUnsignedByte());
            assertEquals(0, tag.readUnsignedByte());
            assertEquals(0, tag.readUnsignedByte());
            assertEquals(0, tag.readUnsignedByte());
            assertEquals(0x11, tag.readUnsignedByte());
            assertEquals(0x22, tag.readUnsignedByte());
            assertEquals(0x33, tag.readUnsignedByte());
            assertEquals(14, tag.readInt());
            assertEquals(0, payload.readerIndex());
        } finally {
            ReferenceCountUtil.safeRelease(tag);
            ReferenceCountUtil.safeRelease(payload);
        }
    }
}
