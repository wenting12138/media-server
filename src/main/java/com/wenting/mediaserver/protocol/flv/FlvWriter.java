package com.wenting.mediaserver.protocol.flv;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal HTTP-FLV writer.
 */
public final class FlvWriter {

    private static final AttributeKey<AtomicBoolean> FLUSH_SCHEDULED_KEY =
            AttributeKey.valueOf("flv.flush.scheduled");

    private FlvWriter() {
    }

    public static void writeHeader(ChannelHandlerContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.write(new DefaultHttpContent(buildFlvHeader()));
        requestFlush(ctx);
    }

    public static void writeTag(ChannelHandlerContext ctx, int typeId, int timestamp, ByteBuf payload) {
        if (ctx == null) {
            ReferenceCountUtil.safeRelease(payload);
            return;
        }
        try {
            ctx.write(new DefaultHttpContent(buildFlvTag(typeId, timestamp, payload)));
            requestFlush(ctx);
        } finally {
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    static ByteBuf buildFlvHeader() {
        ByteBuf out = Unpooled.buffer(13);
        out.writeByte('F');
        out.writeByte('L');
        out.writeByte('V');
        out.writeByte(0x01); // version
        out.writeByte(0x05); // audio + video
        out.writeInt(9);     // data offset
        out.writeInt(0);     // previous tag size 0
        return out;
    }

    static ByteBuf buildFlvTag(int typeId, int timestamp, ByteBuf payload) {
        int ts = timestamp < 0 ? 0 : timestamp;
        int size = payload == null ? 0 : payload.readableBytes();
        ByteBuf out = Unpooled.buffer(11 + size + 4);
        out.writeByte(typeId & 0xFF);
        write24(out, size);
        write24(out, ts & 0xFFFFFF);
        out.writeByte((ts >>> 24) & 0xFF);
        write24(out, 0); // stream id, always 0
        if (payload != null && size > 0) {
            out.writeBytes(payload, payload.readerIndex(), size);
        }
        out.writeInt(11 + size);
        return out;
    }

    private static void write24(ByteBuf out, int value) {
        out.writeByte((value >>> 16) & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
        out.writeByte(value & 0xFF);
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
