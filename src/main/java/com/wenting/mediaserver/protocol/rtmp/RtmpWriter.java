package com.wenting.mediaserver.protocol.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public final class RtmpWriter {
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

    public static void writeChunk(
            ChannelHandlerContext ctx,
            int csid,
            int typeId,
            int messageStreamId,
            int timestamp,
            ByteBuf payload) {
        final int chunkSize = 4096;
        int len = payload.readableBytes();
        int offset = payload.readerIndex();

        int firstPayload = Math.min(len, chunkSize);
        ByteBuf out = Unpooled.buffer(1 + 11 + firstPayload);
        out.writeByte(csid & 0x3F); // fmt=0, full message header
        RtmpChunkDecoder.write24(out, timestamp < 0 ? 0 : Math.min(timestamp, 0xFFFFFF));
        RtmpChunkDecoder.write24(out, len);
        out.writeByte(typeId & 0xFF);
        RtmpChunkDecoder.writeLittleEndianInt(out, messageStreamId);
        out.writeBytes(payload, offset, firstPayload);
        ctx.write(out);

        int remaining = len - firstPayload;
        offset += firstPayload;

        while (remaining > 0) {
            int toWrite = Math.min(remaining, chunkSize);
            ByteBuf next = Unpooled.buffer(1 + toWrite);
            next.writeByte(0xC0 | (csid & 0x3F)); // fmt=3, continuation chunk
            next.writeBytes(payload, offset, toWrite);
            ctx.write(next);
            remaining -= toWrite;
            offset += toWrite;
        }

        ctx.flush();
    }
}
