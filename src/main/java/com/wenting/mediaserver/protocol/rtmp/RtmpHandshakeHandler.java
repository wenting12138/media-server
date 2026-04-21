package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

final class RtmpHandshakeHandler extends ByteToMessageDecoder {
    private static final int C1_SIZE = 1536;
    private final StreamRegistry registry;
    private boolean s0s1s2Sent;

    RtmpHandshakeHandler(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!s0s1s2Sent) {
            if (in.readableBytes() < 1 + C1_SIZE) {
                return;
            }
            in.readByte(); // C0 version
            ByteBuf c1 = in.readRetainedSlice(C1_SIZE);
            try {
                ByteBuf s0s1s2 = Unpooled.buffer(1 + C1_SIZE + C1_SIZE);
                s0s1s2.writeByte(3);
                ByteBuf s1 = Unpooled.buffer(C1_SIZE);
                s1.writeInt((int) (System.currentTimeMillis() / 1000L));
                s1.writeInt(0);
                while (s1.readableBytes() < C1_SIZE) {
                    s1.writeByte(0);
                }
                s0s1s2.writeBytes(s1);
                s0s1s2.writeBytes(c1, c1.readerIndex(), C1_SIZE);
                ctx.writeAndFlush(s0s1s2);
                s0s1s2Sent = true;
            } finally {
                c1.release();
            }
            return;
        }
        if (in.readableBytes() < C1_SIZE) {
            return;
        }
        in.skipBytes(C1_SIZE); // C2

        RtmpSession session = new RtmpSession();
        session.handshakeComplete();
        ctx.channel().attr(RtmpSession.SESSION_KEY).set(session);

        ctx.pipeline().addLast(new RtmpChunkDecoder());
        ctx.pipeline().addLast(new RtmpCommandHandler(registry));
        ctx.pipeline().remove(this);
    }
}
