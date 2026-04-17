package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.rtsp.RtspEncoder;

/**
 * RTSP over TCP: framing decoder, response encoder, session handler (push/pull + RTP relay + H264 depacketize).
 */
public final class RtspChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final StreamRegistry registry;

    public RtspChannelInitializer(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(new RtspTcpFramingDecoder());
        ch.pipeline().addLast(new RtspEncoder());
        ch.pipeline().addLast(new RtspConnectionHandler(registry));
    }
}
