package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.EncodedMediaPacket;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;
import com.wenting.mediaserver.protocol.rtp.H264RtpDepacketizer;
import com.wenting.mediaserver.protocol.rtp.RtpUdpMediaPlane;
import com.wenting.mediaserver.protocol.rtmp.RtmpConstants;
import com.wenting.mediaserver.protocol.rtmp.RtmpWriter;
import com.wenting.mediaserver.protocol.rtsp.RtspInterleavedWriter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
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
    private final StreamFrameProcessor frameProcessor;
    private volatile boolean frameProcessorStarted;
    private volatile String sdp;
    private volatile Channel publisherChannel;
    private final CopyOnWriteArrayList<Channel> subscribers = new CopyOnWriteArrayList<Channel>();
    private final CopyOnWriteArrayList<InetSocketAddress> udpVideoSubscribers = new CopyOnWriteArrayList<InetSocketAddress>();
    private final CopyOnWriteArrayList<InetSocketAddress> udpAudioSubscribers = new CopyOnWriteArrayList<InetSocketAddress>();
    private static final class RtmpSubscriber {
        final ChannelHandlerContext ctx;
        final int messageStreamId;
        RtmpSubscriber(ChannelHandlerContext ctx, int messageStreamId) {
            this.ctx = ctx;
            this.messageStreamId = messageStreamId;
        }
        @Override
        public boolean equals(Object o) {
            return o instanceof RtmpSubscriber && ((RtmpSubscriber) o).ctx == ctx;
        }
        @Override
        public int hashCode() {
            return System.identityHashCode(ctx);
        }
    }
    private final CopyOnWriteArrayList<RtmpSubscriber> rtmpSubscribers = new CopyOnWriteArrayList<RtmpSubscriber>();
    private volatile RtpUdpMediaPlane rtpUdpPlane;
    private volatile ByteBuf rtmpMetadata;
    private volatile ByteBuf rtmpVideoSeqHeader;
    private volatile ByteBuf rtmpAudioSeqHeader;
    private final H264RtpDepacketizer h264 = new H264RtpDepacketizer();
    private final AtomicLong videoInPackets = new AtomicLong();
    private final AtomicLong videoInBytes = new AtomicLong();
    private final AtomicLong videoOutTcpPackets = new AtomicLong();
    private final AtomicLong videoOutTcpBytes = new AtomicLong();
    private final AtomicLong videoOutUdpPackets = new AtomicLong();
    private final AtomicLong videoOutUdpBytes = new AtomicLong();
    private final AtomicLong audioInPackets = new AtomicLong();
    private final AtomicLong audioInBytes = new AtomicLong();
    private final AtomicLong audioOutTcpPackets = new AtomicLong();
    private final AtomicLong audioOutTcpBytes = new AtomicLong();
    private final AtomicLong audioOutUdpPackets = new AtomicLong();
    private final AtomicLong audioOutUdpBytes = new AtomicLong();
    private final AtomicLong rtmpDroppedPackets = new AtomicLong();
    private volatile long statsLogAtMs = System.currentTimeMillis();

    public PublishedStream(StreamKey key, MediaSession publisherSession, StreamFrameProcessor frameProcessor) {
        this.key = key;
        this.publisherSession = publisherSession;
        this.frameProcessor = frameProcessor == null ? StreamFrameProcessor.NOOP : frameProcessor;
    }

    public void markPublishStarted() {
        if (frameProcessorStarted) {
            return;
        }
        frameProcessorStarted = true;
        frameProcessor.onPublishStart(key, sdp);
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

    public void addRtmpSubscriber(ChannelHandlerContext ctx, int messageStreamId) {
        if (ctx == null) {
            return;
        }
        RtmpSubscriber sub = new RtmpSubscriber(ctx, messageStreamId);
        rtmpSubscribers.addIfAbsent(sub);
        ByteBuf meta = rtmpMetadata;
        if (meta != null && meta.isReadable()) {
            RtmpWriter.writeData(ctx, messageStreamId, 0, meta.retainedDuplicate());
        }
        ByteBuf vsh = rtmpVideoSeqHeader;
        if (vsh != null && vsh.isReadable()) {
            RtmpWriter.writeMedia(ctx, RtmpConstants.CSID_VIDEO, RtmpConstants.TYPE_VIDEO, messageStreamId, 0, vsh.retainedDuplicate());
        }
        ByteBuf ash = rtmpAudioSeqHeader;
        if (ash != null && ash.isReadable()) {
            RtmpWriter.writeMedia(ctx, RtmpConstants.CSID_AUDIO, RtmpConstants.TYPE_AUDIO, messageStreamId, 0, ash.retainedDuplicate());
        }
    }

    public void removeRtmpSubscriber(ChannelHandlerContext ctx) {
        if (ctx != null) {
            rtmpSubscribers.remove(new RtmpSubscriber(ctx, 0));
        }
    }

    public void attachRtpUdpMediaPlane(RtpUdpMediaPlane plane) {
        this.rtpUdpPlane = plane;
    }

    public void addUdpVideoSubscriber(InetSocketAddress rtpDestination) {
        if (rtpDestination != null) {
            udpVideoSubscribers.addIfAbsent(rtpDestination);
        }
    }

    public void removeUdpVideoSubscriber(InetSocketAddress rtpDestination) {
        if (rtpDestination != null) {
            udpVideoSubscribers.remove(rtpDestination);
        }
    }

    public void addUdpAudioSubscriber(InetSocketAddress rtpDestination) {
        if (rtpDestination != null) {
            udpAudioSubscribers.addIfAbsent(rtpDestination);
        }
    }

    public void removeUdpAudioSubscriber(InetSocketAddress rtpDestination) {
        if (rtpDestination != null) {
            udpAudioSubscribers.remove(rtpDestination);
        }
    }

    public boolean hasAnySubscriber() {
        return !subscribers.isEmpty()
                || !udpVideoSubscribers.isEmpty()
                || !udpAudioSubscribers.isEmpty()
                || !rtmpSubscribers.isEmpty();
    }

    /**
     * Invoked for each complete RTP packet (including header) on the publisher's video RTP interleave channel.
     */
    public void onPublisherVideoRtp(ByteBuf rtp) {
        publisherSession.touch();
        int rtpBytes = rtp.readableBytes();
        videoInPackets.incrementAndGet();
        videoInBytes.addAndGet(rtpBytes);
        processPacket(
                EncodedMediaPacket.SourceProtocol.RTSP,
                EncodedMediaPacket.TrackType.VIDEO,
                EncodedMediaPacket.CodecType.H264,
                EncodedMediaPacket.PayloadFormat.RTP_PACKET,
                0,
                1,
                rtp);
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
        relayRtp(rtp, rtpBytes, 0, udpVideoSubscribers, true);
        maybeLogStats();
    }

    /**
     * Invoked for each complete RTP packet (including header) on the publisher's audio RTP channel.
     */
    public void onPublisherAudioRtp(ByteBuf rtp) {
        publisherSession.touch();
        int rtpBytes = rtp.readableBytes();
        audioInPackets.incrementAndGet();
        audioInBytes.addAndGet(rtpBytes);
        processPacket(
                EncodedMediaPacket.SourceProtocol.RTSP,
                EncodedMediaPacket.TrackType.AUDIO,
                EncodedMediaPacket.CodecType.UNKNOWN,
                EncodedMediaPacket.PayloadFormat.RTP_PACKET,
                0,
                1,
                rtp);
        relayRtp(rtp, rtpBytes, 2, udpAudioSubscribers, false);
        maybeLogStats();
    }

    public void onPublisherRtmpVideo(ByteBuf payload, int timestamp, int messageStreamId) {
        publisherSession.touch();
        int bytes = payload.readableBytes();
        videoInPackets.incrementAndGet();
        videoInBytes.addAndGet(bytes);
        processPacket(
                EncodedMediaPacket.SourceProtocol.RTMP,
                EncodedMediaPacket.TrackType.VIDEO,
                EncodedMediaPacket.CodecType.H264,
                EncodedMediaPacket.PayloadFormat.RTMP_TAG,
                timestamp,
                messageStreamId,
                payload);
        cacheRtmpSeqHeader(payload, true);
        relayRtmpToSubscribers(RtmpConstants.TYPE_VIDEO, payload, timestamp, messageStreamId);
        maybeLogStats();
    }

    public void onPublisherRtmpAudio(ByteBuf payload, int timestamp, int messageStreamId) {
        publisherSession.touch();
        int bytes = payload.readableBytes();
        audioInPackets.incrementAndGet();
        audioInBytes.addAndGet(bytes);
        processPacket(
                EncodedMediaPacket.SourceProtocol.RTMP,
                EncodedMediaPacket.TrackType.AUDIO,
                EncodedMediaPacket.CodecType.AAC,
                EncodedMediaPacket.PayloadFormat.RTMP_TAG,
                timestamp,
                messageStreamId,
                payload);
        cacheRtmpSeqHeader(payload, false);
        relayRtmpToSubscribers(RtmpConstants.TYPE_AUDIO, payload, timestamp, messageStreamId);
        maybeLogStats();
    }

    public void onPublisherRtmpData(ByteBuf payload, int timestamp, int messageStreamId) {
        publisherSession.touch();
        processPacket(
                EncodedMediaPacket.SourceProtocol.RTMP,
                EncodedMediaPacket.TrackType.DATA,
                EncodedMediaPacket.CodecType.UNKNOWN,
                EncodedMediaPacket.PayloadFormat.RTMP_TAG,
                timestamp,
                messageStreamId,
                payload);
        cacheRtmpMetadata(payload);
        relayRtmpToSubscribers(RtmpConstants.TYPE_DATA_AMF0, payload, timestamp, messageStreamId);
    }

    private void processPacket(
            EncodedMediaPacket.SourceProtocol sourceProtocol,
            EncodedMediaPacket.TrackType trackType,
            EncodedMediaPacket.CodecType codecType,
            EncodedMediaPacket.PayloadFormat payloadFormat,
            int timestamp,
            int messageStreamId,
            ByteBuf payload) {
        if (payload == null || !payload.isReadable()) {
            return;
        }
        EncodedMediaPacket packet = new EncodedMediaPacket(
                sourceProtocol,
                trackType,
                codecType,
                payloadFormat,
                timestamp,
                messageStreamId <= 0 ? 1 : messageStreamId,
                payload.retainedDuplicate());
        try {
            frameProcessor.onPacket(key, packet);
        } finally {
            packet.release();
        }
    }

    private void cacheRtmpMetadata(ByteBuf payload) {
        if (!payload.isReadable()) {
            return;
        }
        ReferenceCountUtil.safeRelease(rtmpMetadata);
        rtmpMetadata = payload.retainedDuplicate();
    }

    private void cacheRtmpSeqHeader(ByteBuf payload, boolean video) {
        if (!payload.isReadable()) {
            return;
        }
        if (video) {
            if (payload.readableBytes() >= 2) {
                int avcPacketType = payload.getUnsignedByte(payload.readerIndex() + 1);
                if (avcPacketType == 0) {
                    ReferenceCountUtil.safeRelease(rtmpVideoSeqHeader);
                    rtmpVideoSeqHeader = payload.retainedDuplicate();
                }
            }
            return;
        }
        if (payload.readableBytes() >= 2) {
            int soundFormat = (payload.getUnsignedByte(payload.readerIndex()) >> 4) & 0x0F;
            int aacPacketType = payload.getUnsignedByte(payload.readerIndex() + 1);
            if (soundFormat == 10 && aacPacketType == 0) {
                ReferenceCountUtil.safeRelease(rtmpAudioSeqHeader);
                rtmpAudioSeqHeader = payload.retainedDuplicate();
            }
        }
    }

    private void relayRtmpToSubscribers(int typeId, ByteBuf payload, int timestamp, int publisherMessageStreamId) {
        List<RtmpSubscriber> snapshot = new ArrayList<RtmpSubscriber>(rtmpSubscribers);
        for (RtmpSubscriber sub : snapshot) {
            if (sub == null || !sub.ctx.channel().isActive()) {
                rtmpSubscribers.remove(sub);
                continue;
            }
            if (!sub.ctx.channel().isWritable() && shouldDropForBackpressure(typeId, payload)) {
                rtmpDroppedPackets.incrementAndGet();
                continue;
            }
            if (typeId == RtmpConstants.TYPE_DATA_AMF0) {
                RtmpWriter.writeData(sub.ctx, sub.messageStreamId, timestamp, payload.retainedDuplicate());
                continue;
            }
            RtmpWriter.writeMedia(sub.ctx, typeId == RtmpConstants.TYPE_VIDEO ? RtmpConstants.CSID_VIDEO : RtmpConstants.CSID_AUDIO, typeId, sub.messageStreamId, timestamp, payload.retainedDuplicate());
            if (typeId == RtmpConstants.TYPE_VIDEO) {
                videoOutTcpPackets.incrementAndGet();
                videoOutTcpBytes.addAndGet(payload.readableBytes());
            } else {
                audioOutTcpPackets.incrementAndGet();
                audioOutTcpBytes.addAndGet(payload.readableBytes());
            }
        }
    }

    private boolean shouldDropForBackpressure(int typeId, ByteBuf payload) {
        if (typeId != RtmpConstants.TYPE_VIDEO) {
            return false;
        }
        if (payload == null || payload.readableBytes() < 2) {
            return false;
        }
        int frameType = (payload.getUnsignedByte(payload.readerIndex()) >> 4) & 0x0F;
        int avcPacketType = payload.getUnsignedByte(payload.readerIndex() + 1);
        boolean keyFrame = frameType == 1;
        boolean sequenceHeader = avcPacketType == 0;
        return !keyFrame && !sequenceHeader;
    }

    private void relayRtp(
            ByteBuf rtp,
            int rtpBytes,
            int interleavedChannel,
            List<InetSocketAddress> udpSubscribers,
            boolean video) {
        List<Channel> snapshot = new ArrayList<Channel>(subscribers);
        for (Channel sub : snapshot) {
            if (!sub.isActive()) {
                subscribers.remove(sub);
                continue;
            }
            ByteBuf dup = rtp.retainedDuplicate();
            ByteBuf framed = RtspInterleavedWriter.frame(interleavedChannel, dup);
            sub.writeAndFlush(framed);
            if (video) {
                videoOutTcpPackets.incrementAndGet();
                videoOutTcpBytes.addAndGet(rtpBytes);
            } else {
                audioOutTcpPackets.incrementAndGet();
                audioOutTcpBytes.addAndGet(rtpBytes);
            }
        }
        RtpUdpMediaPlane plane = rtpUdpPlane;
        if (plane != null && !udpSubscribers.isEmpty()) {
            List<InetSocketAddress> udpSnap = new ArrayList<InetSocketAddress>(udpSubscribers);
            for (InetSocketAddress dst : udpSnap) {
                plane.sendRtpTo(dst, rtp.retainedDuplicate());
                if (video) {
                    videoOutUdpPackets.incrementAndGet();
                    videoOutUdpBytes.addAndGet(rtpBytes);
                } else {
                    audioOutUdpPackets.incrementAndGet();
                    audioOutUdpBytes.addAndGet(rtpBytes);
                }
            }
        }
    }

    private void maybeLogStats() {
        long now = System.currentTimeMillis();
        if (now - statsLogAtMs < STATS_LOG_INTERVAL_MS) {
            return;
        }
        if (subscribers.isEmpty() && udpVideoSubscribers.isEmpty() && udpAudioSubscribers.isEmpty() && rtmpSubscribers.isEmpty()) {
            statsLogAtMs = now;
            return;
        }
        statsLogAtMs = now;
        log.info(
                "RTP relay stats stream={} "
                        + "video(in={}pkts/{}B out=tcp:{}pkts/{}B udp:{}pkts/{}B subs=udp:{}) "
                        + "audio(in={}pkts/{}B out=tcp:{}pkts/{}B udp:{}pkts/{}B subs=udp:{}) "
                        + "subs=tcp:{}, rtmp={}, rtmpDrop={}",
                key.path(),
                videoInPackets.get(),
                videoInBytes.get(),
                videoOutTcpPackets.get(),
                videoOutTcpBytes.get(),
                videoOutUdpPackets.get(),
                videoOutUdpBytes.get(),
                udpVideoSubscribers.size(),
                audioInPackets.get(),
                audioInBytes.get(),
                audioOutTcpPackets.get(),
                audioOutTcpBytes.get(),
                audioOutUdpPackets.get(),
                audioOutUdpBytes.get(),
                udpAudioSubscribers.size(),
                subscribers.size(),
                rtmpSubscribers.size(),
                rtmpDroppedPackets.get());
    }

    public void shutdown() {
        if (frameProcessorStarted) {
            frameProcessor.onPublishStop(key);
            frameProcessorStarted = false;
        }
        h264.reset();
        for (Channel ch : subscribers) {
            ch.close();
        }
        subscribers.clear();
        rtmpSubscribers.clear();
        udpVideoSubscribers.clear();
        udpAudioSubscribers.clear();
        ReferenceCountUtil.safeRelease(rtmpMetadata);
        ReferenceCountUtil.safeRelease(rtmpVideoSeqHeader);
        ReferenceCountUtil.safeRelease(rtmpAudioSeqHeader);
        rtmpMetadata = null;
        rtmpVideoSeqHeader = null;
        rtmpAudioSeqHeader = null;
    }
}
