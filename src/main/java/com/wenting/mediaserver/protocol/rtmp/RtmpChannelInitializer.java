package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public final class RtmpChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final StreamRegistry registry;

    public RtmpChannelInitializer(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(new RtmpHandshakeHandler(registry));
    }
}
