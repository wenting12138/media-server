package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts RTSP-shaped HTTP requests and logs them. Full RTSP state machine is intentionally not implemented yet.
 */
final class RtspStubHandler extends SimpleChannelInboundHandler<HttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(RtspStubHandler.class);

    private final StreamRegistry registry;

    RtspStubHandler(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest msg) {
        log.info("RTSP stub received {} {} (active publishers={})",
                msg.method(),
                msg.uri(),
                registry.publisherCount());
//        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("RTSP connection closed: {}", cause.toString());
        ctx.close();
    }
}
