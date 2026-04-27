package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.EncodedMediaPacket;
import com.wenting.mediaserver.core.transcode.RtpH264AccessUnitNormalizer;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;
import com.wenting.mediaserver.protocol.flv.FlvWriter;
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

import java.util.Base64;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final CopyOnWriteArrayList<RtmpSubscriber> rtmpSubscribers = new CopyOnWriteArrayList<RtmpSubscriber>();
    private final CopyOnWriteArrayList<HttpFlvSubscriber> httpFlvSubscribers = new CopyOnWriteArrayList<HttpFlvSubscriber>();
    private volatile RtpUdpMediaPlane rtpUdpPlane;
    private volatile ByteBuf rtmpMetadata;
    private volatile ByteBuf rtmpVideoSeqHeader;
    private volatile ByteBuf rtmpAudioSeqHeader;
    private volatile ByteBuf rtmpLastVideoKeyFrame;
    private volatile ByteBuf flvVideoSeqHeader;
    private volatile ByteBuf flvAudioSeqHeader;
    private volatile ByteBuf flvLastVideoKeyFrame;
    private volatile int rtmpAvcNalLengthSize = 4;
    private volatile int rtspVideoBaseRtpTs = Integer.MIN_VALUE;
    private volatile byte[] rtspSps;
    private volatile byte[] rtspPps;
    private final H264RtpDepacketizer h264 = new H264RtpDepacketizer();
    private final RtpH264AccessUnitNormalizer rtspFlvAccessUnitNormalizer = new RtpH264AccessUnitNormalizer();
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
        seedRtspVideoParameterSetsFromSdp(sdp);
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
        sub.hasVideoSequenceHeader = rtmpVideoSeqHeader != null && rtmpVideoSeqHeader.isReadable();
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
        ByteBuf kf = rtmpLastVideoKeyFrame;
        if (sub.hasVideoSequenceHeader && kf != null && kf.isReadable()) {
            RtmpWriter.writeMedia(ctx, RtmpConstants.CSID_VIDEO, RtmpConstants.TYPE_VIDEO, messageStreamId, 0, kf.retainedDuplicate());
            sub.waitingVideoKeyFrame = false;
        }
    }

    public void removeRtmpSubscriber(ChannelHandlerContext ctx) {
        if (ctx != null) {
            rtmpSubscribers.remove(new RtmpSubscriber(ctx, 0));
        }
    }

    public void addHttpFlvSubscriber(ChannelHandlerContext ctx) {
        if (ctx == null) {
            return;
        }
        HttpFlvSubscriber sub = new HttpFlvSubscriber(ctx);
        ByteBuf vsh = resolveHttpFlvVideoSeqHeader();
        sub.hasVideoSequenceHeader = vsh != null && vsh.isReadable();
        httpFlvSubscribers.addIfAbsent(sub);
        FlvWriter.writeHeader(ctx);
        ByteBuf meta = rtmpMetadata;
        if (meta != null && meta.isReadable()) {
            FlvWriter.writeTag(ctx, RtmpConstants.TYPE_DATA_AMF0, 0, meta.retainedDuplicate());
        }
        if (vsh != null && vsh.isReadable()) {
            FlvWriter.writeTag(ctx, RtmpConstants.TYPE_VIDEO, 0, vsh.retainedDuplicate());
        }
        ByteBuf ash = resolveHttpFlvAudioSeqHeader();
        if (ash != null && ash.isReadable()) {
            FlvWriter.writeTag(ctx, RtmpConstants.TYPE_AUDIO, 0, ash.retainedDuplicate());
        }
        ByteBuf kf = resolveHttpFlvLastVideoKeyFrame();
        if (sub.hasVideoSequenceHeader && kf != null && kf.isReadable()) {
            FlvWriter.writeTag(ctx, RtmpConstants.TYPE_VIDEO, 0, kf.retainedDuplicate());
            sub.waitingVideoKeyFrame = false;
        }
    }

    public void removeHttpFlvSubscriber(ChannelHandlerContext ctx) {
        if (ctx != null) {
            httpFlvSubscribers.remove(new HttpFlvSubscriber(ctx));
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
                || !rtmpSubscribers.isEmpty()
                || !httpFlvSubscribers.isEmpty();
    }

    /**
     * Invoked for each complete RTP packet (including header) on the publisher's video RTP interleave channel.
     */
    public void onPublisherVideoRtp(ByteBuf rtp) {
        publisherSession.touch();
        int rtpBytes = rtp.readableBytes();
        videoInPackets.incrementAndGet();
        videoInBytes.addAndGet(rtpBytes);
        relayRtspVideoRtpToHttpFlvSubscribers(rtp);
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
        cacheRtmpKeyFrame(payload);
        relayRtmpToSubscribers(RtmpConstants.TYPE_VIDEO, payload, timestamp, messageStreamId);
        relayRtmpToHttpFlvSubscribers(RtmpConstants.TYPE_VIDEO, payload, timestamp);
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
        relayRtmpToHttpFlvSubscribers(RtmpConstants.TYPE_AUDIO, payload, timestamp);
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
        relayRtmpToHttpFlvSubscribers(RtmpConstants.TYPE_DATA_AMF0, payload, timestamp);
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
                    rtmpAvcNalLengthSize = parseAvcNalLengthSize(payload);
                    ReferenceCountUtil.safeRelease(rtmpLastVideoKeyFrame);
                    rtmpLastVideoKeyFrame = null;
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

    private void cacheRtmpKeyFrame(ByteBuf payload) {
        if (payload == null || payload.readableBytes() < 2) {
            return;
        }
        int ri = payload.readerIndex();
        int frameType = (payload.getUnsignedByte(ri) >> 4) & 0x0F;
        int codecId = payload.getUnsignedByte(ri) & 0x0F;
        int avcPacketType = payload.getUnsignedByte(ri + 1);
        boolean keyFrameNalu = codecId == 7 && frameType == 1 && avcPacketType == 1;
        if (!keyFrameNalu || !containsAvccIdr(payload)) {
            return;
        }
        ReferenceCountUtil.safeRelease(rtmpLastVideoKeyFrame);
        rtmpLastVideoKeyFrame = payload.retainedDuplicate();
    }

    private int parseAvcNalLengthSize(ByteBuf payload) {
        if (payload == null || payload.readableBytes() < 10) {
            return 4;
        }
        int base = payload.readerIndex();
        int avcPacketType = payload.getUnsignedByte(base + 1);
        if (avcPacketType != 0) {
            return 4;
        }
        int avccStart = base + 5;
        int end = base + payload.readableBytes();
        if (avccStart + 4 >= end) {
            return 4;
        }
        int lengthSizeMinusOne = payload.getUnsignedByte(avccStart + 4) & 0x03;
        int n = lengthSizeMinusOne + 1;
        return n >= 1 && n <= 4 ? n : 4;
    }

    private boolean containsAvccIdr(ByteBuf payload) {
        if (payload == null || payload.readableBytes() < 6) {
            return false;
        }
        int ri = payload.readerIndex();
        int codecId = payload.getUnsignedByte(ri) & 0x0F;
        int avcPacketType = payload.getUnsignedByte(ri + 1);
        if (codecId != 7 || avcPacketType != 1) {
            return false;
        }
        int off = ri + 5;
        int end = ri + payload.readableBytes();
        int lenSize = rtmpAvcNalLengthSize <= 0 ? 4 : rtmpAvcNalLengthSize;
        while (off + lenSize <= end) {
            int naluLen = 0;
            for (int i = 0; i < lenSize; i++) {
                naluLen = (naluLen << 8) | payload.getUnsignedByte(off + i);
            }
            off += lenSize;
            if (naluLen <= 0 || off + naluLen > end) {
                return false;
            }
            int nalType = payload.getUnsignedByte(off) & 0x1F;
            if (nalType == 5) {
                return true;
            }
            off += naluLen;
        }
        return false;
    }

    private ByteBuf resolveHttpFlvVideoSeqHeader() {
        ByteBuf fromRtmp = rtmpVideoSeqHeader;
        if (fromRtmp != null && fromRtmp.isReadable()) {
            return fromRtmp;
        }
        return flvVideoSeqHeader;
    }

    private ByteBuf resolveHttpFlvAudioSeqHeader() {
        ByteBuf fromRtmp = rtmpAudioSeqHeader;
        if (fromRtmp != null && fromRtmp.isReadable()) {
            return fromRtmp;
        }
        return flvAudioSeqHeader;
    }

    private ByteBuf resolveHttpFlvLastVideoKeyFrame() {
        ByteBuf fromRtmp = rtmpLastVideoKeyFrame;
        if (fromRtmp != null && fromRtmp.isReadable()) {
            return fromRtmp;
        }
        return flvLastVideoKeyFrame;
    }

    private void relayRtspVideoRtpToHttpFlvSubscribers(ByteBuf rtpPacket) {
        if (rtpPacket == null || !rtpPacket.isReadable()) {
            return;
        }
        List<RtpH264AccessUnitNormalizer.AccessUnit> accessUnits = rtspFlvAccessUnitNormalizer.ingest(rtpPacket);
        if (accessUnits == null || accessUnits.isEmpty()) {
            return;
        }
        for (RtpH264AccessUnitNormalizer.AccessUnit au : accessUnits) {
            if (au == null) {
                continue;
            }
            try {
                relayRtspAccessUnitToHttpFlvSubscribers(au);
            } finally {
                au.release();
            }
        }
    }

    private void relayRtspAccessUnitToHttpFlvSubscribers(RtpH264AccessUnitNormalizer.AccessUnit accessUnit) {
        if (accessUnit == null || !accessUnit.hasVcl() || accessUnit.annexB() == null || !accessUnit.annexB().isReadable()) {
            return;
        }
        List<byte[]> nals = extractAnnexbNals(accessUnit.annexB());
        if (nals.isEmpty()) {
            return;
        }
        boolean hasIdr = false;
        boolean hasParameterSets = false;
        for (byte[] nal : nals) {
            if (nal == null || nal.length == 0) {
                continue;
            }
            int nalType = nal[0] & 0x1F;
            if (nalType == 7 || nalType == 8) {
                cacheRtspParameterSet(nalType, nal);
                hasParameterSets = true;
            }
            if (nalType == 5) {
                hasIdr = true;
            }
        }
        if (hasParameterSets) {
            refreshRtspVideoSequenceHeader();
        }
        int timestampMs = mapRtspVideoTimestampMs(accessUnit.timestamp90k());
        if (hasParameterSets) {
            ByteBuf seq = flvVideoSeqHeader;
            if (seq != null && seq.isReadable()) {
                relayRtmpToHttpFlvSubscribers(RtmpConstants.TYPE_VIDEO, seq, timestampMs);
            }
        }
        ByteBuf payload = buildFlvAvcPayloadFromAnnexbAccessUnit(nals, hasIdr);
        if (payload == null) {
            return;
        }
        try {
            if (hasIdr) {
                ReferenceCountUtil.safeRelease(flvLastVideoKeyFrame);
                flvLastVideoKeyFrame = payload.retainedDuplicate();
            }
            relayRtmpToHttpFlvSubscribers(RtmpConstants.TYPE_VIDEO, payload, timestampMs);
        } finally {
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    private void cacheRtspParameterSet(int nalType, byte[] nalBody) {
        if (nalBody == null || nalBody.length == 0) {
            return;
        }
        if (nalType == 7) {
            rtspSps = nalBody;
        } else if (nalType == 8) {
            rtspPps = nalBody;
        }
        rtspFlvAccessUnitNormalizer.seedParameterSets(rtspSps, rtspPps);
    }

    private void refreshRtspVideoSequenceHeader() {
        byte[] sps = rtspSps;
        byte[] pps = rtspPps;
        if (sps == null || sps.length < 4 || pps == null || pps.length == 0) {
            return;
        }
        ByteBuf seq = buildFlvAvcSequenceHeader(sps, pps);
        if (seq == null) {
            return;
        }
        ReferenceCountUtil.safeRelease(flvVideoSeqHeader);
        flvVideoSeqHeader = seq;
    }

    private ByteBuf buildFlvAvcSequenceHeader(byte[] sps, byte[] pps) {
        if (sps == null || sps.length < 4 || pps == null || pps.length == 0) {
            return null;
        }
        ByteBuf out = io.netty.buffer.Unpooled.buffer(16 + sps.length + pps.length);
        out.writeByte(0x17); // keyframe + AVC
        out.writeByte(0x00); // AVC sequence header
        out.writeByte(0x00);
        out.writeByte(0x00);
        out.writeByte(0x00);
        out.writeByte(0x01); // avcC version
        out.writeByte(sps[1] & 0xFF);
        out.writeByte(sps[2] & 0xFF);
        out.writeByte(sps[3] & 0xFF);
        out.writeByte(0xFF); // lengthSizeMinusOne = 3 (4 bytes)
        out.writeByte(0xE1); // one SPS
        out.writeShort(sps.length);
        out.writeBytes(sps);
        out.writeByte(0x01); // one PPS
        out.writeShort(pps.length);
        out.writeBytes(pps);
        return out;
    }

    private ByteBuf buildFlvAvcPayloadFromAnnexbAccessUnit(List<byte[]> nals, boolean keyFrame) {
        if (nals == null || nals.isEmpty()) {
            return null;
        }
        int total = 5;
        int count = 0;
        for (byte[] nal : nals) {
            if (nal == null || nal.length == 0) {
                continue;
            }
            total += 4 + nal.length;
            count++;
        }
        if (count <= 0) {
            return null;
        }
        ByteBuf out = io.netty.buffer.Unpooled.buffer(total);
        out.writeByte(keyFrame ? 0x17 : 0x27);
        out.writeByte(0x01); // AVC NALU
        out.writeByte(0x00);
        out.writeByte(0x00);
        out.writeByte(0x00); // composition time
        for (byte[] nal : nals) {
            if (nal == null || nal.length == 0) {
                continue;
            }
            out.writeInt(nal.length);
            out.writeBytes(nal);
        }
        return out;
    }

    private List<byte[]> extractAnnexbNals(ByteBuf annexbAu) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (annexbAu == null || !annexbAu.isReadable()) {
            return out;
        }
        byte[] bytes = new byte[annexbAu.readableBytes()];
        annexbAu.getBytes(annexbAu.readerIndex(), bytes);
        int offset = 0;
        while (offset < bytes.length) {
            int start = findStartCode(bytes, offset);
            if (start < 0) {
                break;
            }
            int startCodeLen = startCodeLength(bytes, start);
            int nalStart = start + startCodeLen;
            int next = findStartCode(bytes, nalStart);
            int nalEnd = next < 0 ? bytes.length : next;
            int nalLen = nalEnd - nalStart;
            if (nalLen > 0) {
                byte[] nal = new byte[nalLen];
                System.arraycopy(bytes, nalStart, nal, 0, nalLen);
                out.add(nal);
            }
            if (next < 0) {
                break;
            }
            offset = next;
        }
        return out;
    }

    private static int findStartCode(byte[] bytes, int from) {
        if (bytes == null || from < 0 || from >= bytes.length) {
            return -1;
        }
        for (int i = from; i < bytes.length - 2; i++) {
            if (bytes[i] == 0 && bytes[i + 1] == 0) {
                if (bytes[i + 2] == 1) {
                    return i;
                }
                if (i + 3 < bytes.length && bytes[i + 2] == 0 && bytes[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] bytes, int index) {
        if (bytes == null || index < 0 || index + 2 >= bytes.length) {
            return 0;
        }
        if (bytes[index] == 0 && bytes[index + 1] == 0 && bytes[index + 2] == 1) {
            return 3;
        }
        if (index + 3 < bytes.length
                && bytes[index] == 0
                && bytes[index + 1] == 0
                && bytes[index + 2] == 0
                && bytes[index + 3] == 1) {
            return 4;
        }
        return 0;
    }

    private static int readRtpTimestamp(ByteBuf rtp) {
        if (rtp == null || rtp.readableBytes() < 12) {
            return Integer.MIN_VALUE;
        }
        int ri = rtp.readerIndex();
        if (ri + 8 >= rtp.writerIndex()) {
            return Integer.MIN_VALUE;
        }
        return rtp.getInt(ri + 4);
    }

    private int mapRtspVideoTimestampMs(int rtpTimestamp) {
        if (rtpTimestamp == Integer.MIN_VALUE) {
            return 0;
        }
        if (rtspVideoBaseRtpTs == Integer.MIN_VALUE) {
            rtspVideoBaseRtpTs = rtpTimestamp;
        }
        long delta90k = elapsed32(rtspVideoBaseRtpTs, rtpTimestamp);
        long tsMs = delta90k / 90L;
        return tsMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tsMs;
    }

    private static long elapsed32(int start, int end) {
        return ((end & 0xFFFFFFFFL) - (start & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
    }

    private void seedRtspVideoParameterSetsFromSdp(String sdpText) {
        byte[][] sets = parseSpropParameterSets(sdpText);
        if (sets == null) {
            return;
        }
        if (sets[0] != null && sets[0].length > 0) {
            rtspSps = sets[0];
        }
        if (sets[1] != null && sets[1].length > 0) {
            rtspPps = sets[1];
        }
        rtspFlvAccessUnitNormalizer.seedParameterSets(rtspSps, rtspPps);
        refreshRtspVideoSequenceHeader();
    }

    private static byte[][] parseSpropParameterSets(String sdpText) {
        if (sdpText == null || sdpText.trim().isEmpty()) {
            return null;
        }
        String[] lines = sdpText.split("\r\n|\n");
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("a=fmtp:")) {
                continue;
            }
            int idx = lower.indexOf("sprop-parameter-sets=");
            if (idx < 0) {
                continue;
            }
            String value = line.substring(idx + "sprop-parameter-sets=".length()).trim();
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) {
                value = value.substring(0, semicolon).trim();
            }
            String[] parts = value.split(",");
            byte[] sps = parts.length > 0 ? decodeBase64(parts[0]) : null;
            byte[] pps = parts.length > 1 ? decodeBase64(parts[1]) : null;
            return new byte[][]{sps, pps};
        }
        return null;
    }

    private static byte[] decodeBase64(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
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
            if (typeId == RtmpConstants.TYPE_VIDEO) {
                if (payload == null || payload.readableBytes() < 2) {
                    continue;
                }
                int frameType = (payload.getUnsignedByte(payload.readerIndex()) >> 4) & 0x0F;
                int avcPacketType = payload.getUnsignedByte(payload.readerIndex() + 1);
                boolean keyFrame = frameType == 1;
                boolean sequenceHeader = avcPacketType == 0;
                if (sequenceHeader) {
                    RtmpWriter.writeMedia(sub.ctx, RtmpConstants.CSID_VIDEO, typeId, sub.messageStreamId, timestamp, payload.retainedDuplicate());
                    videoOutTcpPackets.incrementAndGet();
                    videoOutTcpBytes.addAndGet(payload.readableBytes());
                    sub.hasVideoSequenceHeader = true;
                    // New decoder config means old reference chain may be invalid.
                    // Force subscriber to wait for next IDR.
                    sub.waitingVideoKeyFrame = true;
                    continue;
                }
                if (avcPacketType != 1) {
                    continue;
                }
                if (sub.waitingVideoKeyFrame) {
                    if (!sub.hasVideoSequenceHeader) {
                        continue;
                    }
                    if (!keyFrame) {
                        continue;
                    }
                    if (!containsAvccIdr(payload)) {
                        continue;
                    }
                    ByteBuf vsh = rtmpVideoSeqHeader;
                    if (vsh != null && vsh.isReadable()) {
                        RtmpWriter.writeMedia(sub.ctx, RtmpConstants.CSID_VIDEO, RtmpConstants.TYPE_VIDEO, sub.messageStreamId, 0, vsh.retainedDuplicate());
                        videoOutTcpPackets.incrementAndGet();
                        videoOutTcpBytes.addAndGet(vsh.readableBytes());
                        sub.hasVideoSequenceHeader = true;
                    }
                    if (!sub.hasVideoSequenceHeader) {
                        continue;
                    }
                    sub.waitingVideoKeyFrame = false;
                }
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

    private void relayRtmpToHttpFlvSubscribers(int typeId, ByteBuf payload, int timestamp) {
        List<HttpFlvSubscriber> snapshot = new ArrayList<HttpFlvSubscriber>(httpFlvSubscribers);
        for (HttpFlvSubscriber sub : snapshot) {
            if (sub == null || !sub.ctx.channel().isActive()) {
                httpFlvSubscribers.remove(sub);
                continue;
            }
            if (!sub.ctx.channel().isWritable() && shouldDropForBackpressure(typeId, payload)) {
                rtmpDroppedPackets.incrementAndGet();
                continue;
            }
            if (typeId == RtmpConstants.TYPE_VIDEO) {
                if (payload == null || payload.readableBytes() < 2) {
                    continue;
                }
                int frameType = (payload.getUnsignedByte(payload.readerIndex()) >> 4) & 0x0F;
                int avcPacketType = payload.getUnsignedByte(payload.readerIndex() + 1);
                boolean keyFrame = frameType == 1;
                boolean sequenceHeader = avcPacketType == 0;
                if (sequenceHeader) {
                    FlvWriter.writeTag(sub.ctx, typeId, timestamp, payload.retainedDuplicate());
                    videoOutTcpPackets.incrementAndGet();
                    videoOutTcpBytes.addAndGet(payload.readableBytes());
                    sub.hasVideoSequenceHeader = true;
                    sub.waitingVideoKeyFrame = true;
                    continue;
                }
                if (avcPacketType != 1) {
                    continue;
                }
                if (sub.waitingVideoKeyFrame) {
                    if (!sub.hasVideoSequenceHeader) {
                        continue;
                    }
                    if (!keyFrame) {
                        continue;
                    }
                    if (!containsAvccIdr(payload)) {
                        continue;
                    }
                    ByteBuf vsh = resolveHttpFlvVideoSeqHeader();
                    if (vsh != null && vsh.isReadable()) {
                        FlvWriter.writeTag(sub.ctx, RtmpConstants.TYPE_VIDEO, 0, vsh.retainedDuplicate());
                        videoOutTcpPackets.incrementAndGet();
                        videoOutTcpBytes.addAndGet(vsh.readableBytes());
                        sub.hasVideoSequenceHeader = true;
                    }
                    if (!sub.hasVideoSequenceHeader) {
                        continue;
                    }
                    sub.waitingVideoKeyFrame = false;
                }
            }
            FlvWriter.writeTag(sub.ctx, typeId, timestamp, payload.retainedDuplicate());
            if (typeId == RtmpConstants.TYPE_VIDEO) {
                videoOutTcpPackets.incrementAndGet();
                videoOutTcpBytes.addAndGet(payload.readableBytes());
            } else if (typeId == RtmpConstants.TYPE_AUDIO) {
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
        if (subscribers.isEmpty()
                && udpVideoSubscribers.isEmpty()
                && udpAudioSubscribers.isEmpty()
                && rtmpSubscribers.isEmpty()
                && httpFlvSubscribers.isEmpty()) {
            statsLogAtMs = now;
            return;
        }
        statsLogAtMs = now;
        log.info(
                "RTP relay stats stream={} "
                        + "video(in={}pkts/{}B out=tcp:{}pkts/{}B udp:{}pkts/{}B subs=udp:{}) "
                        + "audio(in={}pkts/{}B out=tcp:{}pkts/{}B udp:{}pkts/{}B subs=udp:{}) "
                        + "subs=tcp:{}, rtmp={}, flv={}, rtmpDrop={}",
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
                httpFlvSubscribers.size(),
                rtmpDroppedPackets.get());
    }

    public void shutdown() {
        if (frameProcessorStarted) {
            frameProcessor.onPublishStop(key);
            frameProcessorStarted = false;
        }
        h264.reset();
        rtspFlvAccessUnitNormalizer.close();
        for (Channel ch : subscribers) {
            ch.close();
        }
        subscribers.clear();
        rtmpSubscribers.clear();
        httpFlvSubscribers.clear();
        udpVideoSubscribers.clear();
        udpAudioSubscribers.clear();
        ReferenceCountUtil.safeRelease(rtmpMetadata);
        ReferenceCountUtil.safeRelease(rtmpVideoSeqHeader);
        ReferenceCountUtil.safeRelease(rtmpAudioSeqHeader);
        ReferenceCountUtil.safeRelease(rtmpLastVideoKeyFrame);
        ReferenceCountUtil.safeRelease(flvVideoSeqHeader);
        ReferenceCountUtil.safeRelease(flvAudioSeqHeader);
        ReferenceCountUtil.safeRelease(flvLastVideoKeyFrame);
        rtmpMetadata = null;
        rtmpVideoSeqHeader = null;
        rtmpAudioSeqHeader = null;
        rtmpLastVideoKeyFrame = null;
        flvVideoSeqHeader = null;
        flvAudioSeqHeader = null;
        flvLastVideoKeyFrame = null;
        rtspVideoBaseRtpTs = Integer.MIN_VALUE;
        rtspSps = null;
        rtspPps = null;
        rtmpAvcNalLengthSize = 4;
    }
}
