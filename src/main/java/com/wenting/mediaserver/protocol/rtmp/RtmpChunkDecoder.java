package com.wenting.mediaserver.protocol.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RtmpChunkDecoder extends ByteToMessageDecoder {
    private final Map<Integer, HeaderState> headers = new HashMap<Integer, HeaderState>();
    private int inChunkSize = 128;

    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            in.markReaderIndex();
            if (in.readableBytes() < 1) {
                in.resetReaderIndex();
                return;
            }
            int b0 = in.readUnsignedByte();
            int fmt = (b0 >> 6) & 0x03;
            int csid = b0 & 0x3F;
            if (csid == 0 || csid == 1) {
                in.resetReaderIndex();
                return; // MVP: no extended csid support
            }
            HeaderState hs = headers.get(Integer.valueOf(csid));
            if (fmt == 0) {
                if (in.readableBytes() < 11) {
                    in.resetReaderIndex();
                    return;
                }
                int ts = read24(in);
                int len = read24(in);
                int typeId = in.readUnsignedByte();
                int msid = readLittleEndianInt(in);
                hs = new HeaderState(ts, len, typeId, msid);
                headers.put(Integer.valueOf(csid), hs);
                hs.payload = Unpooled.buffer(len);
            } else if (fmt == 1) {
                if (hs == null || in.readableBytes() < 7) {
                    in.resetReaderIndex();
                    return;
                }
                int delta = read24(in);
                int len = read24(in);
                int typeId = in.readUnsignedByte();
                hs.timestamp = hs.timestamp + delta;
                hs.messageLength = len;
                hs.typeId = typeId;
                hs.payload = Unpooled.buffer(len);
            } else if (fmt == 2) {
                if (hs == null || in.readableBytes() < 3) {
                    in.resetReaderIndex();
                    return;
                }
                int delta = read24(in);
                hs.timestamp = hs.timestamp + delta;
                hs.payload = Unpooled.buffer(hs.messageLength);
            } else if (fmt == 3 && hs != null) {
                if (hs.payload == null) {
                    hs.payload = Unpooled.buffer(hs.messageLength);
                }
            } else {
                in.resetReaderIndex();
                return;
            }
            if (hs == null || hs.payload == null) {
                in.resetReaderIndex();
                return;
            }
            int remaining = hs.messageLength - hs.payload.readableBytes();
            int toRead = Math.min(remaining, inChunkSize);
            if (in.readableBytes() < toRead) {
                in.resetReaderIndex();
                return;
            }
            hs.payload.writeBytes(in, toRead);
            if (hs.payload.readableBytes() >= hs.messageLength) {
                ByteBuf full = hs.payload;
                hs.payload = null;
                if (hs.typeId == 1 && full.readableBytes() >= 4) {
                    inChunkSize = Math.max(128, full.readInt());
                    full.release();
                } else {
                    out.add(new RtmpMessage(hs.typeId, hs.timestamp, hs.messageStreamId, full));
                }
            }
        }
    }

    static int read24(ByteBuf in) {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return (b1 << 16) | (b2 << 8) | b3;
    }

    static void write24(ByteBuf out, int value) {
        out.writeByte((value >> 16) & 0xFF);
        out.writeByte((value >> 8) & 0xFF);
        out.writeByte(value & 0xFF);
    }

    static int readLittleEndianInt(ByteBuf in) {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        int b4 = in.readUnsignedByte();
        return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
    }

    static void writeLittleEndianInt(ByteBuf out, int v) {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
        out.writeByte((v >> 16) & 0xFF);
        out.writeByte((v >> 24) & 0xFF);
    }

    private static final class HeaderState {
        private int timestamp;
        private int messageLength;
        private int typeId;
        private final int messageStreamId;
        private ByteBuf payload;

        private HeaderState(int timestamp, int messageLength, int typeId, int messageStreamId) {
            this.timestamp = timestamp;
            this.messageLength = messageLength;
            this.typeId = typeId;
            this.messageStreamId = messageStreamId;
        }
    }
}
