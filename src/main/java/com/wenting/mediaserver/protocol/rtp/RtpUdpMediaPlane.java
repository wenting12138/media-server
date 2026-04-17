package com.wenting.mediaserver.protocol.rtp;

import com.wenting.mediaserver.core.publish.PublishedStream;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared UDP plane: ephemeral outbound socket for RTP toward UDP subscribers,
 * and allocation of publisher RTP/RTCP receive port pairs.
 */
public final class RtpUdpMediaPlane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RtpUdpMediaPlane.class);

    private final EventLoopGroup worker;
    /** Outbound RTP (and matching RTCP sink for Transport symmetry). */
    private volatile DatagramChannel egressRtpChannel;
    private volatile DatagramChannel egressRtcpSinkChannel;
    private final Object egressLock = new Object();
    private final AtomicBoolean egressFirstPacketLogged = new AtomicBoolean(false);

    public RtpUdpMediaPlane(EventLoopGroup worker) {
        this.worker = worker;
    }

    public void ensureEgressStarted() throws InterruptedException {
        synchronized (egressLock) {
            if (egressRtpChannel != null && egressRtpChannel.isActive()) {
                return;
            }
            Bootstrap rtpB = newBootstrapOutbound();
            Bootstrap rtcpB = newBootstrapRtcpDiscard();
            for (int i = 0; i < 64; i++) {
                DatagramChannel rtp = (DatagramChannel) rtpB.bind(0).sync().channel();
                int p = ((InetSocketAddress) rtp.localAddress()).getPort();
                if ((p & 1) != 0) {
                    rtp.close().sync();
                    continue;
                }
                try {
                    DatagramChannel rtcp = (DatagramChannel) rtcpB.bind(p + 1).sync().channel();
                    this.egressRtpChannel = rtp;
                    this.egressRtcpSinkChannel = rtcp;
                    log.info("RTP UDP egress server_port {}-{}", p, p + 1);
                    return;
                } catch (Exception e) {
                    rtp.close().sync();
                }
            }
            throw new IllegalStateException("could not allocate RTP/RTCP egress UDP port pair");
        }
    }

    public int egressServerRtpPort() {
        DatagramChannel ch = egressRtpChannel;
        return ch == null ? -1 : ((InetSocketAddress) ch.localAddress()).getPort();
    }

    public int egressServerRtcpPort() {
        DatagramChannel ch = egressRtcpSinkChannel;
        return ch == null ? -1 : ((InetSocketAddress) ch.localAddress()).getPort();
    }

    private Bootstrap newBootstrapOutbound() {
        Bootstrap b = new Bootstrap();
        b.group(worker).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        // outbound RTP only
                    }
                });
        return b;
    }

    private Bootstrap newBootstrapRtcpDiscard() {
        Bootstrap b = new Bootstrap();
        b.group(worker).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                                // discard RTCP toward advertised sink port
                            }
                        });
                    }
                });
        return b;
    }

    /**
     * Sends one RTP datagram to a subscriber. Takes ownership of {@code rtp} (released if not sent).
     */
    public void sendRtpTo(InetSocketAddress destination, ByteBuf rtp) {
        DatagramChannel ch = egressRtpChannel;
        if (ch == null || !ch.isActive() || destination == null) {
            ReferenceCountUtil.safeRelease(rtp);
            return;
        }
        ch.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (!ch.isActive()) {
                    ReferenceCountUtil.safeRelease(rtp);
                    return;
                }
                if (egressFirstPacketLogged.compareAndSet(false, true)) {
                    log.info(
                            "RTP UDP egress first packet local={} -> remote={} bytes={}",
                            ch.localAddress(),
                            destination,
                            rtp.readableBytes());
                }
                ch.writeAndFlush(new DatagramPacket(rtp, destination));
            }
        });
    }

    /**
     * Opens RTP (even port) + RTCP (odd) UDP listeners. RTP datagram payloads are passed to
     * {@link PublishedStream#onPublisherVideoRtp} after {@link PublisherRtpReceiver#bindSink(PublishedStream)}.
     */
    public PublisherRtpReceiver openPublisherReceiver() throws InterruptedException {
        final AtomicReference<PublishedStream> sink = new AtomicReference<PublishedStream>();
        final AtomicBoolean ingressFirstPacketLogged = new AtomicBoolean(false);

        Bootstrap rtpBootstrap = new Bootstrap();
        rtpBootstrap.group(worker).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                                PublishedStream ps = sink.get();
                                ByteBuf content = msg.content();
                                if (ps == null || !content.isReadable()) {
                                    return;
                                }
                                if (ingressFirstPacketLogged.compareAndSet(false, true)) {
                                    log.info(
                                            "RTP UDP ingress first packet remote={} -> local={} bytes={}",
                                            msg.sender(),
                                            msg.recipient(),
                                            content.readableBytes());
                                }
                                ByteBuf copy = content.retainedDuplicate();
                                try {
                                    ps.onPublisherVideoRtp(copy);
                                } finally {
                                    ReferenceCountUtil.release(copy);
                                }
                            }
                        });
                    }
                });

        Bootstrap rtcpBootstrap = new Bootstrap();
        rtcpBootstrap.group(worker).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                                // RTCP ignored on MVP path
                            }
                        });
                    }
                });

        DatagramChannel rtp = null;
        DatagramChannel rtcp = null;
        for (int attempt = 0; attempt < 64; attempt++) {
            DatagramChannel rtpCand = (DatagramChannel) rtpBootstrap.bind(0).sync().channel();
            int p = ((InetSocketAddress) rtpCand.localAddress()).getPort();
            if ((p & 1) != 0) {
                rtpCand.close().sync();
                continue;
            }
            try {
                DatagramChannel rtcpCand = (DatagramChannel) rtcpBootstrap.bind(p + 1).sync().channel();
                rtp = rtpCand;
                rtcp = rtcpCand;
                break;
            } catch (Exception e) {
                rtpCand.close().sync();
            }
        }
        if (rtp == null || rtcp == null) {
            throw new IllegalStateException("could not allocate RTP/RTCP UDP port pair");
        }

        log.debug("publisher RTP/RTCP UDP ports {} / {}", serverPort(rtp), serverPort(rtcp));
        return new PublisherRtpReceiver(sink, rtp, rtcp);
    }

    private static int serverPort(DatagramChannel ch) {
        return ((InetSocketAddress) ch.localAddress()).getPort();
    }

    @Override
    public void close() {
        synchronized (egressLock) {
            if (egressRtpChannel != null) {
                egressRtpChannel.close();
                egressRtpChannel = null;
            }
            if (egressRtcpSinkChannel != null) {
                egressRtcpSinkChannel.close();
                egressRtcpSinkChannel = null;
            }
        }
    }

    public static final class PublisherRtpReceiver {
        private final AtomicReference<PublishedStream> sink;
        private final DatagramChannel rtpChannel;
        private final DatagramChannel rtcpChannel;

        private PublisherRtpReceiver(AtomicReference<PublishedStream> sink, DatagramChannel rtp, DatagramChannel rtcp) {
            this.sink = sink;
            this.rtpChannel = rtp;
            this.rtcpChannel = rtcp;
        }

        public int serverRtpPort() {
            return ((InetSocketAddress) rtpChannel.localAddress()).getPort();
        }

        public int serverRtcpPort() {
            return ((InetSocketAddress) rtcpChannel.localAddress()).getPort();
        }

        public void bindSink(PublishedStream publishedStream) {
            sink.set(publishedStream);
        }

        public void close() {
            sink.set(null);
            rtpChannel.close();
            rtcpChannel.close();
        }
    }
}
