package com.wenting.mediaserver.protocol.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RtmpChunkDecoderTest {

    @Test
    void shouldDecodeExtendedCsidType0Chunk() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpChunkDecoder());
        ByteBuf in = Unpooled.buffer();
        try {
            // Basic header: fmt=0, csid=64 (encoded as 0 + 1-byte extension value 0).
            in.writeByte(0x00);
            in.writeByte(0x00);
            RtmpChunkDecoder.write24(in, 100);
            RtmpChunkDecoder.write24(in, 4);
            in.writeByte(RtmpConstants.TYPE_VIDEO);
            RtmpChunkDecoder.writeLittleEndianInt(in, 1);
            in.writeBytes(new byte[]{1, 2, 3, 4});

            channel.writeInbound(in.retain());
            RtmpMessage msg = channel.readInbound();
            assertNotNull(msg);
            assertEquals(RtmpConstants.TYPE_VIDEO, msg.typeId());
            assertEquals(100, msg.timestamp());
            assertEquals(1, msg.messageStreamId());
            assertEquals(4, msg.payload().readableBytes());
            assertEquals(1, msg.payload().readUnsignedByte());
            assertEquals(2, msg.payload().readUnsignedByte());
            assertEquals(3, msg.payload().readUnsignedByte());
            assertEquals(4, msg.payload().readUnsignedByte());
            ReferenceCountUtil.release(msg.payload());
            assertNull(channel.readInbound());
        } finally {
            ReferenceCountUtil.safeRelease(in);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldDecodeExtendedTimestampAcrossContinuationChunks() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpChunkDecoder());
        ByteBuf in = Unpooled.buffer();
        try {
            byte[] payload = new byte[200];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xFF);
            }

            // First chunk: fmt=0, csid=6, timestamp uses extended field.
            in.writeByte(0x06);
            RtmpChunkDecoder.write24(in, 0xFFFFFF);
            RtmpChunkDecoder.write24(in, payload.length);
            in.writeByte(RtmpConstants.TYPE_VIDEO);
            RtmpChunkDecoder.writeLittleEndianInt(in, 1);
            in.writeInt(0x01020304);
            in.writeBytes(payload, 0, 128);

            // Continuation chunk: fmt=3, same csid, includes extended timestamp.
            in.writeByte(0xC6);
            in.writeInt(0x01020304);
            in.writeBytes(payload, 128, payload.length - 128);

            channel.writeInbound(in.retain());
            RtmpMessage msg = channel.readInbound();
            assertNotNull(msg);
            assertEquals(0x01020304, msg.timestamp());
            assertEquals(payload.length, msg.payload().readableBytes());
            for (int i = 0; i < payload.length; i++) {
                assertEquals(payload[i] & 0xFF, msg.payload().readUnsignedByte());
            }
            ReferenceCountUtil.release(msg.payload());
            assertNull(channel.readInbound());
        } finally {
            ReferenceCountUtil.safeRelease(in);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldAdvanceTimestampForFmt3NewMessage() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpChunkDecoder());
        ByteBuf in = Unpooled.buffer();
        try {
            // Message 1: fmt=0, ts=10
            in.writeByte(0x06);
            RtmpChunkDecoder.write24(in, 10);
            RtmpChunkDecoder.write24(in, 1);
            in.writeByte(RtmpConstants.TYPE_VIDEO);
            RtmpChunkDecoder.writeLittleEndianInt(in, 1);
            in.writeByte(0x11);

            // Message 2: fmt=1, delta=5 => ts=15
            in.writeByte(0x46);
            RtmpChunkDecoder.write24(in, 5);
            RtmpChunkDecoder.write24(in, 1);
            in.writeByte(RtmpConstants.TYPE_VIDEO);
            in.writeByte(0x22);

            // Message 3: fmt=3, new message uses previous delta => ts=20
            in.writeByte(0xC6);
            in.writeByte(0x33);

            channel.writeInbound(in.retain());
            RtmpMessage m1 = channel.readInbound();
            RtmpMessage m2 = channel.readInbound();
            RtmpMessage m3 = channel.readInbound();

            assertNotNull(m1);
            assertNotNull(m2);
            assertNotNull(m3);
            assertEquals(10, m1.timestamp());
            assertEquals(15, m2.timestamp());
            assertEquals(20, m3.timestamp());
            assertEquals(0x11, m1.payload().readUnsignedByte());
            assertEquals(0x22, m2.payload().readUnsignedByte());
            assertEquals(0x33, m3.payload().readUnsignedByte());

            ReferenceCountUtil.release(m1.payload());
            ReferenceCountUtil.release(m2.payload());
            ReferenceCountUtil.release(m3.payload());
            assertNull(channel.readInbound());
        } finally {
            ReferenceCountUtil.safeRelease(in);
            channel.finishAndReleaseAll();
        }
    }
}
