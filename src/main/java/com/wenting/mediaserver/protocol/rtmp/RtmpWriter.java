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
        writeChunk(ctx, 2, typeId, 0, 0, payload);
    }

    public static void writeSetPeerBandwidth(ChannelHandlerContext ctx, int value, int limitType) {
        ByteBuf payload = Unpooled.buffer(5);
        payload.writeInt(value);
        payload.writeByte(limitType);
        writeChunk(ctx, RtmpConstants.CSID_PROTOCOL, RtmpConstants.TYPE_SET_PEER_BANDWIDTH, 0, 0, payload);
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

    public static void writeChunk(
            ChannelHandlerContext ctx,
            int csid,
            int typeId,
            int messageStreamId,
            int timestamp,
            ByteBuf payload) {
        int len = payload.readableBytes();
        ByteBuf out = Unpooled.buffer(1 + 11 + len);
        out.writeByte(csid & 0x3F); // fmt0
        RtmpChunkDecoder.write24(out, timestamp < 0 ? 0 : Math.min(timestamp, 0xFFFFFF));
        RtmpChunkDecoder.write24(out, len);
        out.writeByte(typeId & 0xFF);
        RtmpChunkDecoder.writeLittleEndianInt(out, messageStreamId);
        out.writeBytes(payload, payload.readerIndex(), len);
        ctx.writeAndFlush(out);
    }
}
