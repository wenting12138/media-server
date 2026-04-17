package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.protocol.rtp.H264RtpDepacketizer;
import com.wenting.mediaserver.protocol.rtp.RtpUdpMediaPlane;
import com.wenting.mediaserver.protocol.rtsp.RtspInterleavedWriter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One published RTSP stream: SDP, publisher session, subscribers, H264 depacketizer, RTP relay.
 */
public final class PublishedStream {

    private static final Logger log = LoggerFactory.getLogger(PublishedStream.class);
    private static final long STATS_LOG_INTERVAL_MS = 5000L;

    private final StreamKey key;
    private final MediaSession publisherSession;
    private volatile String sdp;
    private volatile Channel publisherChannel;
    private final CopyOnWriteArrayList<Channel> subscribers = new CopyOnWriteArrayList<Channel>();
    private final CopyOnWriteArrayList<InetSocketAddress> udpSubscribers = new CopyOnWriteArrayList<InetSocketAddress>();
    private volatile RtpUdpMediaPlane rtpUdpPlane;
    private final H264RtpDepacketizer h264 = new H264RtpDepacketizer();
    private final AtomicLong inPackets = new AtomicLong();
    private final AtomicLong inBytes = new AtomicLong();
    private final AtomicLong outTcpPackets = new AtomicLong();
    private final AtomicLong outTcpBytes = new AtomicLong();
    private final AtomicLong outUdpPackets = new AtomicLong();
    private final AtomicLong outUdpBytes = new AtomicLong();
    private volatile long statsLogAtMs = System.currentTimeMillis();

    public PublishedStream(StreamKey key, MediaSession publisherSession) {
        this.key = key;
        this.publisherSession = publisherSession;
    }

    public StreamKey key() {
        return key;
    }

    public MediaSession publisherSession() {
        return publisherSession;
    }

    public String sdp() {
        return sdp;
    }

    public void setSdp(String sdp) {
        this.sdp = sdp;
    }

    public void setPublisherChannel(Channel publisherChannel) {
        this.publisherChannel = publisherChannel;
    }

    public void addSubscriber(Channel ch) {
        subscribers.addIfAbsent(ch);
    }

    public void removeSubscriber(Channel ch) {
        subscribers.remove(ch);
    }

    public void attachRtpUdpMediaPlane(RtpUdpMediaPlane plane) {
        this.rtpUdpPlane = plane;
    }

    public void addUdpSubscriber(InetSocketAddress rtpDestination) {
        if (rtpDestination != null) {
            udpSubscribers.addIfAbsent(rtpDestination);
        }
    }

    public void removeUdpSubscriber(InetSocketAddress rtpDestination) {
        if (rtpDestination != null) {
            udpSubscribers.remove(rtpDestination);
        }
    }

    /**
     * Invoked for each complete RTP packet (including header) on the publisher's video RTP interleave channel.
     */
    public void onPublisherVideoRtp(ByteBuf rtp) {
        publisherSession.touch();
        int rtpBytes = rtp.readableBytes();
        inPackets.incrementAndGet();
        inBytes.addAndGet(rtpBytes);
        h264.ingest(rtp, nal -> {
            try {
                if (log.isTraceEnabled() && nal != null && nal.readableBytes() > 5) {
                    int nalType = nal.getUnsignedByte(4) & 0x1F;
                    log.trace("stream {} complete NAL type {}", key.path(), nalType);
                }
            } finally {
                ReferenceCountUtil.release(nal);
            }
        });
        List<Channel> snapshot = new ArrayList<Channel>(subscribers);
        for (Channel sub : snapshot) {
            if (!sub.isActive()) {
                subscribers.remove(sub);
                continue;
            }
            ByteBuf dup = rtp.retainedDuplicate();
            ByteBuf framed = RtspInterleavedWriter.frame(0, dup);
            sub.writeAndFlush(framed);
            outTcpPackets.incrementAndGet();
            outTcpBytes.addAndGet(rtpBytes);
        }
        RtpUdpMediaPlane plane = rtpUdpPlane;
        if (plane != null && !udpSubscribers.isEmpty()) {
            List<InetSocketAddress> udpSnap = new ArrayList<InetSocketAddress>(udpSubscribers);
            for (InetSocketAddress dst : udpSnap) {
                plane.sendRtpTo(dst, rtp.retainedDuplicate());
                outUdpPackets.incrementAndGet();
                outUdpBytes.addAndGet(rtpBytes);
            }
        }
        maybeLogStats();
    }

    private void maybeLogStats() {
        long now = System.currentTimeMillis();
        if (now - statsLogAtMs < STATS_LOG_INTERVAL_MS) {
            return;
        }
        statsLogAtMs = now;
        log.info(
                "RTP relay stats stream={} in={}pkts/{}B out=tcp:{}pkts/{}B udp:{}pkts/{}B subs=tcp:{} udp:{}",
                key.path(),
                inPackets.get(),
                inBytes.get(),
                outTcpPackets.get(),
                outTcpBytes.get(),
                outUdpPackets.get(),
                outUdpBytes.get(),
                subscribers.size(),
                udpSubscribers.size());
    }

    public void shutdown() {
        h264.reset();
        for (Channel ch : subscribers) {
            ch.close();
        }
        subscribers.clear();
        udpSubscribers.clear();
    }
}
