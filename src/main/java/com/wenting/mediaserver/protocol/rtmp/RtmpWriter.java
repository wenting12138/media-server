package com.wenting.mediaserver.protocol.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RtmpWriter {
    private static final AttributeKey<AtomicBoolean> FLUSH_SCHEDULED_KEY =
            AttributeKey.valueOf("rtmp.flush.scheduled");

    private RtmpWriter() {
    }

    public static void writeProtocolControl(ChannelHandlerContext ctx, int typeId, int value) {
        ByteBuf payload = Unpooled.buffer(4);
        payload.writeInt(value);
        writeChunk(ctx, RtmpConstants.CSID_PROTOCOL, typeId, 0, 0, payload);
    }

    public static void writeSetPeerBandwidth(ChannelHandlerContext ctx, int value, int limitType) {
        ByteBuf payload = Unpooled.buffer(5);
        payload.writeInt(value);
        payload.writeByte(limitType);
        writeChunk(ctx, RtmpConstants.CSID_PROTOCOL, RtmpConstants.TYPE_SET_PEER_BANDWIDTH, 0, 0, payload);
    }

    public static void writeStreamBegin(ChannelHandlerContext ctx, int streamId) {
        ByteBuf payload = Unpooled.buffer(6);
        payload.writeShort(0); // StreamBegin event type
        payload.writeInt(streamId);
        writeChunk(ctx, RtmpConstants.CSID_PROTOCOL, RtmpConstants.TYPE_USER_CONTROL, 0, 0, payload);
    }

    public static void writeCommand(ChannelHandlerContext ctx, int messageStreamId, ByteBuf payload) {
        writeChunk(ctx, RtmpConstants.CSID_COMMAND, RtmpConstants.TYPE_COMMAND_AMF0, messageStreamId, 0, payload);
    }

    public static void writeMedia(
            ChannelHandlerContext ctx,
            int csid,
            int typeId,
            int messageStreamId,
            int timestamp,
            ByteBuf payload) {
        writeChunk(ctx, csid, typeId, messageStreamId, timestamp, payload);
    }

    public static void writeSampleAccess(ChannelHandlerContext ctx, int messageStreamId) {
        ByteBuf payload = Unpooled.buffer();
        RtmpAmf0.writeString(payload, "|RtmpSampleAccess");
        RtmpAmf0.writeBoolean(payload, true);
        RtmpAmf0.writeBoolean(payload, true);
        writeChunk(ctx, RtmpConstants.CSID_COMMAND, RtmpConstants.TYPE_DATA_AMF0, messageStreamId, 0, payload);
    }

    public static void writeData(ChannelHandlerContext ctx, int messageStreamId, int timestamp, ByteBuf payload) {
        writeChunk(ctx, RtmpConstants.CSID_COMMAND, RtmpConstants.TYPE_DATA_AMF0, messageStreamId, timestamp, payload);
    }

    public static void writeChunk(
            ChannelHandlerContext ctx,
            int csid,
            int typeId,
            int messageStreamId,
            int timestamp,
            ByteBuf payload) {
        try {
            final int chunkSize = 4096;
            int len = payload.readableBytes();
            int offset = payload.readerIndex();
            int safeTimestamp = timestamp < 0 ? 0 : timestamp;
            boolean extendedTimestamp = safeTimestamp >= 0xFFFFFF;

            int firstPayload = Math.min(len, chunkSize);
            int basicHeaderSize = basicHeaderLength(csid);
            ByteBuf out = Unpooled.buffer(basicHeaderSize + 11 + (extendedTimestamp ? 4 : 0) + firstPayload);
            writeBasicHeader(out, 0, csid); // fmt=0, full message header
            RtmpChunkDecoder.write24(out, extendedTimestamp ? 0xFFFFFF : safeTimestamp);
            RtmpChunkDecoder.write24(out, len);
            out.writeByte(typeId & 0xFF);
            RtmpChunkDecoder.writeLittleEndianInt(out, messageStreamId);
            if (extendedTimestamp) {
                out.writeInt(safeTimestamp);
            }
            out.writeBytes(payload, offset, firstPayload);
            ctx.write(out);

            int remaining = len - firstPayload;
            offset += firstPayload;

            while (remaining > 0) {
                int toWrite = Math.min(remaining, chunkSize);
                ByteBuf next = Unpooled.buffer(basicHeaderSize + (extendedTimestamp ? 4 : 0) + toWrite);
                writeBasicHeader(next, 3, csid); // fmt=3, continuation chunk
                if (extendedTimestamp) {
                    next.writeInt(safeTimestamp);
                }
                next.writeBytes(payload, offset, toWrite);
                ctx.write(next);
                remaining -= toWrite;
                offset += toWrite;
            }

            requestFlush(ctx);
        } finally {
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    private static int basicHeaderLength(int csid) {
        if (csid >= 2 && csid <= 63) {
            return 1;
        }
        if (csid >= 64 && csid <= 319) {
            return 2;
        }
        return 3;
    }

    private static void writeBasicHeader(ByteBuf out, int fmt, int csid) {
        int fmtBits = (fmt & 0x03) << 6;
        if (csid >= 2 && csid <= 63) {
            out.writeByte(fmtBits | csid);
            return;
        }
        if (csid >= 64 && csid <= 319) {
            out.writeByte(fmtBits);
            out.writeByte(csid - 64);
            return;
        }
        int adjusted = csid - 64;
        out.writeByte(fmtBits | 1);
        out.writeByte(adjusted & 0xFF);
        out.writeByte((adjusted >> 8) & 0xFF);
    }

    private static void requestFlush(ChannelHandlerContext ctx) {
        AtomicBoolean scheduled = ctx.channel().attr(FLUSH_SCHEDULED_KEY).get();
        if (scheduled == null) {
            AtomicBoolean init = new AtomicBoolean(false);
            AtomicBoolean existing = ctx.channel().attr(FLUSH_SCHEDULED_KEY).setIfAbsent(init);
            scheduled = existing == null ? init : existing;
        }
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        final AtomicBoolean state = scheduled;
        ctx.executor().execute(() -> {
            try {
                if (ctx.channel().isActive()) {
                    ctx.flush();
                }
            } finally {
                state.set(false);
            }
        });
    }
}
