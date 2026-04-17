package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty bridge: forwards RTSP messages to {@link RtspStateMachine} (explicit FSM).
 */
public final class RtspConnectionHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(RtspConnectionHandler.class);

    private final RtspStateMachine fsm;

    public RtspConnectionHandler(StreamRegistry registry) {
        this.fsm = new RtspStateMachine(registry);
    }

    /** 推流 / 拉流 / 未定：由状态机当前状态推导（等价于早期的 {@code Side}）。 */
    public RtspConnectionRole rtspConnectionRole() {
        return fsm.connectionRole();
    }

    /** 细粒度 RTSP 会话状态（协商中、已 PLAY/RECORD 等）。 */
    public RtspFsmState rtspFsmState() {
        return fsm.rtspState();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        fsm.onConnectionInactive(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("RTSP connection error: {}", cause.toString());
        fsm.onConnectionInactive(ctx.channel());
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof RtspRequestMessage) {
            RtspRequestMessage req = (RtspRequestMessage) msg;
            try {
                fsm.handleRequest(ctx, req);
            } finally {
                releaseBody(req);
            }
        } else if (msg instanceof InterleavedRtpPacket) {
            InterleavedRtpPacket p = (InterleavedRtpPacket) msg;
            try {
                fsm.handleInterleaved(ctx, p);
            } finally {
                p.release();
            }
        }
    }

    private static void releaseBody(RtspRequestMessage req) {
        ByteBuf b = req.body();
        if (b != null && b != io.netty.buffer.Unpooled.EMPTY_BUFFER && b.refCnt() > 0) {
            b.release();
        }
    }
}
