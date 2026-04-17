package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;

/**
 * RTSP pipeline placeholder. Next steps: custom decoder for interleaved RTP, session FSM, SDP exchange.
 */
public final class RtspChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final StreamRegistry registry;

    public RtspChannelInitializer(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(new RtspDecoder());
        ch.pipeline().addLast(new RtspEncoder());
        ch.pipeline().addLast(new RtspStubHandler(registry));
    }
}
