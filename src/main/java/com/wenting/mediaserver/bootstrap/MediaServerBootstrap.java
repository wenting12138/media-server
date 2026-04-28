package com.wenting.mediaserver.bootstrap;

import com.wenting.mediaserver.api.HttpJsonApiHandler;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.hls.HlsStreamFrameProcessor;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.transcode.CompositeStreamFrameProcessor;
import com.wenting.mediaserver.core.transcode.StreamTranscodeDispatcher;
import com.wenting.mediaserver.core.transcode.StreamTranscoderFactory;
import com.wenting.mediaserver.core.webrtc.WebRtcBcDtlsEngine;
import com.wenting.mediaserver.core.webrtc.WebRtcDtlsEngine;
import com.wenting.mediaserver.core.webrtc.WebRtcDtlsSrtpBootstrap;
import com.wenting.mediaserver.core.webrtc.WebRtcDtlsMode;
import com.wenting.mediaserver.core.webrtc.WebRtcPseudoDtlsEngine;
import com.wenting.mediaserver.core.webrtc.WebRtcSessionManager;
import com.wenting.mediaserver.core.webrtc.WebRtcStreamFrameProcessor;
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
import java.util.function.IntSupplier;

/**
 * Owns Netty boss/worker groups and protocol server channels.
 */
public final class MediaServerBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MediaServerBootstrap.class);

    private final MediaServerConfig config;
    private final StreamTranscodeDispatcher transcodeDispatcher;
    private final HlsStreamFrameProcessor hlsProcessor;
    private final WebRtcSessionManager webRtcSessionManager;
    private final WebRtcDtlsSrtpBootstrap webRtcDtlsSrtpBootstrap;
    private final WebRtcStreamFrameProcessor webRtcFrameProcessor;
    private final WebRtcDtlsMode webRtcDtlsMode;
    private final boolean webRtcAllowPlainRelay;
    private final WebRtcDtlsEngine webRtcDtlsEngine;
    private final String webRtcLocalFingerprint;
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
        this.webRtcSessionManager = new WebRtcSessionManager();
        this.webRtcDtlsMode = WebRtcDtlsMode.parse(System.getenv("MEDIA_WEBRTC_DTLS_MODE"), WebRtcDtlsMode.REAL);
        this.webRtcAllowPlainRelay = parseBoolean(System.getenv("MEDIA_WEBRTC_PSEUDO_ALLOW_PLAIN_RTP"), true);
        this.rtpUdpPlane = new RtpUdpMediaPlane(worker, config.rtpPortMin(), config.rtpPortMax());
        this.webRtcDtlsEngine = createDtlsEngine(webRtcDtlsMode);
        this.webRtcLocalFingerprint = webRtcDtlsEngine == null ? null : webRtcDtlsEngine.localFingerprint();
        this.webRtcDtlsSrtpBootstrap = new WebRtcDtlsSrtpBootstrap(
                webRtcSessionManager,
                rtpUdpPlane,
                webRtcDtlsEngine);
        this.rtpUdpPlane.setEgressIngressObserver(webRtcDtlsSrtpBootstrap);
        this.webRtcFrameProcessor = new WebRtcStreamFrameProcessor(
                webRtcSessionManager,
                rtpUdpPlane,
                webRtcDtlsMode == WebRtcDtlsMode.PSEUDO && webRtcAllowPlainRelay);
        this.frameProcessor = new CompositeStreamFrameProcessor(Arrays.asList(transcodeDispatcher, hlsProcessor, webRtcFrameProcessor));
        this.registry = new StreamRegistry(frameProcessor, config.transcodeOutputSuffix());
        this.transcodeDispatcher.bindRegistry(this.registry);
        log.info(
                "WebRTC DTLS mode={} pseudoPlainRelay={} engine={} fingerprint={} (env: MEDIA_WEBRTC_DTLS_MODE / MEDIA_WEBRTC_PSEUDO_ALLOW_PLAIN_RTP)",
                webRtcDtlsMode.name().toLowerCase(),
                (webRtcDtlsMode == WebRtcDtlsMode.PSEUDO && webRtcAllowPlainRelay),
                webRtcDtlsEngine == null ? "none" : webRtcDtlsEngine.getClass().getSimpleName(),
                webRtcLocalFingerprint == null ? "synthetic" : webRtcLocalFingerprint);
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
                        ch.pipeline().addLast(new HttpJsonApiHandler(
                                config,
                                registry,
                                hlsProcessor,
                                webRtcSessionManager,
                                webRtcLocalFingerprint,
                                new IntSupplier() {
                                    @Override
                                    public int getAsInt() {
                                        return rtpUdpPlane.egressServerRtpPort();
                                    }
                                }));
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

    private static WebRtcDtlsEngine createDtlsEngine(WebRtcDtlsMode mode) {
        if (mode == WebRtcDtlsMode.OFF) {
            return null;
        }
        if (mode == WebRtcDtlsMode.PSEUDO) {
            // PSEUDO is for local plumbing/integration tests only.
            return new WebRtcPseudoDtlsEngine();
        }
        try {
            return WebRtcBcDtlsEngine.create();
        } catch (Exception e) {
            if (mode == WebRtcDtlsMode.STRICT) {
                throw new IllegalStateException("strict dtls mode requires a working BouncyCastle DTLS engine", e);
            }
            log.warn("real dtls engine unavailable, fallback to pseudo mode: {}", e.toString());
            return new WebRtcPseudoDtlsEngine();
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String v = raw.trim().toLowerCase();
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "off".equals(v)) {
            return false;
        }
        return fallback;
    }
}
