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
    private final UdpPortPairPool portPool;
    /** Outbound RTP (and matching RTCP sink for Transport symmetry). */
    private volatile DatagramChannel egressRtpChannel;
    private volatile DatagramChannel egressRtcpSinkChannel;
    private volatile int egressEvenPort = -1;
    private final Object egressLock = new Object();
    private final AtomicBoolean egressFirstPacketLogged = new AtomicBoolean(false);

    public RtpUdpMediaPlane(EventLoopGroup worker, int portRangeMin, int portRangeMax) {
        this.worker = worker;
        this.portPool = new UdpPortPairPool(portRangeMin, portRangeMax);
        log.info(
                "RTP UDP port pool initialized range {}-{} pairs={}",
                portPool.portRangeMin(),
                portPool.portRangeMax(),
                portPool.pairCapacity());
    }

    public void ensureEgressStarted() throws InterruptedException {
        synchronized (egressLock) {
            if (egressRtpChannel != null && egressRtpChannel.isActive()) {
                return;
            }
            Bootstrap rtpB = newBootstrapOutbound();
            Bootstrap rtcpB = newBootstrapRtcpDiscard();
            DatagramChannel[] pair = allocatePortPair(rtpB, rtcpB);
            if (pair != null) {
                this.egressRtpChannel = pair[0];
                this.egressRtcpSinkChannel = pair[1];
                int p = ((InetSocketAddress) pair[0].localAddress()).getPort();
                this.egressEvenPort = p;
                log.info("RTP UDP egress server_port {}-{}", p, p + 1);
                return;
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
     * {@link PublishedStream#onPublisherVideoRtp} after {@link PublisherRtpReceiver#bindSink(PublishedStream,int)}.
     */
    public PublisherRtpReceiver openPublisherReceiver() throws InterruptedException {
        final AtomicReference<PublishedStream> sink = new AtomicReference<PublishedStream>();
        final AtomicReference<Integer> sinkTrackId = new AtomicReference<Integer>(0);
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
                                    Integer trackId = sinkTrackId.get();
                                    if (trackId != null && trackId.intValue() == 1) {
                                        ps.onPublisherAudioRtp(copy);
                                    } else {
                                        ps.onPublisherVideoRtp(copy);
                                    }
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

        DatagramChannel[] pair = allocatePortPair(rtpBootstrap, rtcpBootstrap);
        DatagramChannel rtp = pair == null ? null : pair[0];
        DatagramChannel rtcp = pair == null ? null : pair[1];
        if (rtp == null || rtcp == null) {
            throw new IllegalStateException("could not allocate RTP/RTCP UDP port pair");
        }

        int evenPort = serverPort(rtp);
        log.debug("publisher RTP/RTCP UDP ports {} / {}", evenPort, serverPort(rtcp));
        return new PublisherRtpReceiver(sink, sinkTrackId, rtp, rtcp, evenPort);
    }

    private static int serverPort(DatagramChannel ch) {
        return ((InetSocketAddress) ch.localAddress()).getPort();
    }

    private DatagramChannel[] allocatePortPair(Bootstrap rtpBootstrap, Bootstrap rtcpBootstrap) throws InterruptedException {
        int attempts = portPool.pairCapacity();
        for (int i = 0; i < attempts; i++) {
            Integer pObj = portPool.acquireEvenPort();
            if (pObj == null) {
                break;
            }
            int p = pObj.intValue();
            DatagramChannel[] pair = tryBindPair(rtpBootstrap, rtcpBootstrap, p);
            if (pair != null) {
                return pair;
            }
            portPool.releaseEvenPort(p);
        }
        return null;
    }

    private void releaseEvenPort(int evenPort) {
        portPool.releaseEvenPort(evenPort);
    }

    private static DatagramChannel[] tryBindPair(Bootstrap rtpBootstrap, Bootstrap rtcpBootstrap, int rtpPort)
            throws InterruptedException {
        DatagramChannel rtp = null;
        try {
            log.debug("RTP UDP allocate try server_port {}-{}", rtpPort, rtpPort + 1);
            rtp = (DatagramChannel) rtpBootstrap.bind(rtpPort).sync().channel();
            DatagramChannel rtcp = (DatagramChannel) rtcpBootstrap.bind(rtpPort + 1).sync().channel();
            log.info("RTP UDP allocate success server_port {}-{}", rtpPort, rtpPort + 1);
            return new DatagramChannel[]{rtp, rtcp};
        } catch (Exception e) {
            if (rtp != null) {
                rtp.close().sync();
            }
            log.debug("RTP UDP allocate failed server_port {}-{}: {}", rtpPort, rtpPort + 1, e.toString());
            return null;
        }
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
            if (egressEvenPort > 0) {
                releaseEvenPort(egressEvenPort);
                egressEvenPort = -1;
            }
        }
    }

    public final class PublisherRtpReceiver {
        private final AtomicReference<PublishedStream> sink;
        private final AtomicReference<Integer> sinkTrackId;
        private final DatagramChannel rtpChannel;
        private final DatagramChannel rtcpChannel;
        private final int evenPort;

        private PublisherRtpReceiver(
                AtomicReference<PublishedStream> sink,
                AtomicReference<Integer> sinkTrackId,
                DatagramChannel rtp,
                DatagramChannel rtcp,
                int evenPort) {
            this.sink = sink;
            this.sinkTrackId = sinkTrackId;
            this.rtpChannel = rtp;
            this.rtcpChannel = rtcp;
            this.evenPort = evenPort;
        }

        public int serverRtpPort() {
            return ((InetSocketAddress) rtpChannel.localAddress()).getPort();
        }

        public int serverRtcpPort() {
            return ((InetSocketAddress) rtcpChannel.localAddress()).getPort();
        }

        public void bindSink(PublishedStream publishedStream, int trackId) {
            sink.set(publishedStream);
            sinkTrackId.set(trackId);
        }

        public void close() {
            sink.set(null);
            sinkTrackId.set(0);
            rtpChannel.close();
            rtcpChannel.close();
            releaseEvenPort(evenPort);
        }
    }
}
