package com.wenting.mediaserver.bootstrap;

import com.wenting.mediaserver.api.HttpJsonApiHandler;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.hls.HlsStreamFrameProcessor;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.transcode.CompositeStreamFrameProcessor;
import com.wenting.mediaserver.core.transcode.StreamTranscodeDispatcher;
import com.wenting.mediaserver.core.transcode.StreamTranscoderFactory;
import com.wenting.mediaserver.protocol.rtp.RtpUdpMediaPlane;
import com.wenting.mediaserver.protocol.rtmp.RtmpChannelInitializer;
import com.wenting.mediaserver.protocol.rtsp.RtspChannelInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Owns Netty boss/worker groups and protocol server channels.
 */
public final class MediaServerBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MediaServerBootstrap.class);

    private final MediaServerConfig config;
    private final StreamTranscodeDispatcher transcodeDispatcher;
    private final HlsStreamFrameProcessor hlsProcessor;
    private final CompositeStreamFrameProcessor frameProcessor;
    private final StreamRegistry registry;
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private final RtpUdpMediaPlane rtpUdpPlane;
    private final List<Channel> channels = new ArrayList<>();

    public MediaServerBootstrap(MediaServerConfig config) {
        this.config = config;
        this.transcodeDispatcher = new StreamTranscodeDispatcher(StreamTranscoderFactory.create(config));
        this.hlsProcessor = new HlsStreamFrameProcessor(config);
        this.frameProcessor = new CompositeStreamFrameProcessor(Arrays.asList(transcodeDispatcher, hlsProcessor));
        this.registry = new StreamRegistry(frameProcessor, config.transcodeOutputSuffix());
        this.transcodeDispatcher.bindRegistry(this.registry);
        this.rtpUdpPlane = new RtpUdpMediaPlane(worker, config.rtpPortMin(), config.rtpPortMax());
    }

    public StreamRegistry registry() {
        return registry;
    }

    public ChannelFuture start() throws InterruptedException {
        ServerBootstrap http = new ServerBootstrap();
        http.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new HttpJsonApiHandler(config, registry));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 512)
                .childOption(ChannelOption.TCP_NODELAY, true);

        Channel httpChannel = http.bind(config.httpPort()).sync().channel();
        channels.add(httpChannel);
        log.info("HTTP API listening on {}", httpChannel.localAddress());
        log.info("RTP UDP port range {}-{}", config.rtpPortMin(), config.rtpPortMax());

        rtpUdpPlane.ensureEgressStarted();

        ServerBootstrap rtsp = new ServerBootstrap();
        rtsp.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new RtspChannelInitializer(registry, rtpUdpPlane));

        Channel rtspChannel = rtsp.bind(config.rtspPort()).sync().channel();
        channels.add(rtspChannel);
        log.info("RTSP (TCP signaling + RTP TCP/UDP) listening on {}", rtspChannel.localAddress());

        ServerBootstrap rtmp = new ServerBootstrap();
        rtmp.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new RtmpChannelInitializer(registry))
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(256 * 1024, 1024 * 1024));
        Channel rtmpChannel = rtmp.bind(config.rtmpPort()).sync().channel();
        channels.add(rtmpChannel);
        log.info("RTMP listening on {}", rtmpChannel.localAddress());

        return httpChannel.closeFuture();
    }

    @Override
    public void close() {
        for (Channel ch : channels) {
            ch.close();
        }
        frameProcessor.close();
        rtpUdpPlane.close();
        worker.shutdownGracefully();
        boss.shutdownGracefully();
    }
}
