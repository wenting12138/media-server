package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.EncodedMediaPacket;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;
import com.wenting.mediaserver.protocol.rtp.H264RtpPacketizer;
import com.wenting.mediaserver.protocol.rtp.RtpUdpMediaPlane;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal WebRTC media bridge:
 * fan-out published encoded packets to WHEP sessions by stream key and record readiness stats.
 */
public final class WebRtcStreamFrameProcessor implements StreamFrameProcessor {

    private final WebRtcSessionManager sessionManager;
    private final RtpUdpMediaPlane rtpUdpMediaPlane;
    private final boolean allowPlainRelay;
    private final Map<String, SessionRelayState> relayStates = new ConcurrentHashMap<String, SessionRelayState>();

    public WebRtcStreamFrameProcessor(WebRtcSessionManager sessionManager) {
        this(sessionManager, null, true);
    }

    public WebRtcStreamFrameProcessor(WebRtcSessionManager sessionManager, RtpUdpMediaPlane rtpUdpMediaPlane) {
        this(sessionManager, rtpUdpMediaPlane, true);
    }

    public WebRtcStreamFrameProcessor(
            WebRtcSessionManager sessionManager,
            RtpUdpMediaPlane rtpUdpMediaPlane,
            boolean allowPlainRelay) {
        this.sessionManager = sessionManager;
        this.rtpUdpMediaPlane = rtpUdpMediaPlane;
        this.allowPlainRelay = allowPlainRelay;
    }

    @Override
    public void onPublishStart(StreamKey key, String sdpText) {
        // no-op in phase-3 minimal media bridge.
    }

    @Override
    public void onPacket(StreamKey key, EncodedMediaPacket packet) {
        if (sessionManager == null || key == null || packet == null) {
            return;
        }
        if (packet.trackType() == null) {
            return;
        }
        boolean video = packet.trackType() == EncodedMediaPacket.TrackType.VIDEO;
        boolean audio = packet.trackType() == EncodedMediaPacket.TrackType.AUDIO;
        if (!video && !audio) {
            return;
        }
        List<WebRtcSession> sessions = sessionManager.listSessions();
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        boolean codecConfig = false;
        boolean keyFrame = false;
        if (video) {
            codecConfig = isVideoCodecConfig(packet);
            keyFrame = isVideoKeyFrame(packet);
        }

        String track = video ? "video" : "audio";
        String source = packet.sourceProtocol() == null ? null : packet.sourceProtocol().name().toLowerCase(Locale.ROOT);
        String format = packet.payloadFormat() == null ? null : packet.payloadFormat().name().toLowerCase(Locale.ROOT);
        for (WebRtcSession session : sessions) {
            if (session == null || session.streamKey() == null || !key.equals(session.streamKey())) {
                continue;
            }
            session.onMediaPacket(track, source, format, codecConfig, keyFrame);
            maybeRelayRtmpVideo(session, packet, video);
            maybeRelayPlainRtp(session, packet, video);
        }
    }

    @Override
    public void onPublishStop(StreamKey key) {
        // no-op in phase-3 minimal media bridge.
        if (key == null || relayStates.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SessionRelayState> entry : relayStates.entrySet()) {
            SessionRelayState state = entry.getValue();
            if (state != null && key.equals(state.streamKey)) {
                relayStates.remove(entry.getKey());
            }
        }
    }

    private void maybeRelayRtmpVideo(WebRtcSession session, EncodedMediaPacket packet, boolean video) {
        if (!video || session == null || packet == null) {
            return;
        }
        if (rtpUdpMediaPlane == null || !session.canPlainRtpRelay()) {
            return;
        }
        if (packet.sourceProtocol() != EncodedMediaPacket.SourceProtocol.RTMP
                || packet.payloadFormat() != EncodedMediaPacket.PayloadFormat.RTMP_TAG
                || packet.codecType() != EncodedMediaPacket.CodecType.H264) {
            return;
        }
        InetSocketAddress destination = session.selectedRtpCandidate();
        ByteBuf payload = packet.payload();
        if (destination == null || payload == null || !payload.isReadable() || payload.readableBytes() < 5) {
            return;
        }
        int ri = payload.readerIndex();
        int first = payload.getUnsignedByte(ri);
        int codecId = first & 0x0F;
        if (codecId != 7) {
            return;
        }
        int frameType = (first >> 4) & 0x0F;
        int avcPacketType = payload.getUnsignedByte(ri + 1);

        SessionRelayState state = relayState(session);
        if (avcPacketType == 0) {
            parseAvcSequenceHeader(payload, state);
            return;
        }
        if (avcPacketType != 1) {
            return;
        }

        int compositionTime = readSigned24(
                payload.getUnsignedByte(ri + 2),
                payload.getUnsignedByte(ri + 3),
                payload.getUnsignedByte(ri + 4));
        int ptsMs = packet.timestamp() + compositionTime;
        if (ptsMs < 0) {
            ptsMs = 0;
        }
        int timestamp90k = state.toRtpTimestamp90k(ptsMs);

        AvccAccessUnit accessUnit = decodeAvccVideo(payload, state, frameType == 1);
        if (accessUnit == null || accessUnit.nalus.isEmpty()) {
            return;
        }
        if (frameType == 1) {
            maybeSendCodecConfigBeforeKeyFrame(state, session, destination, timestamp90k, accessUnit);
        }
        for (int i = 0; i < accessUnit.nalus.size(); i++) {
            byte[] nalu = accessUnit.nalus.get(i);
            if (nalu == null || nalu.length == 0) {
                continue;
            }
            final boolean marker = (i == accessUnit.nalus.size() - 1);
            state.videoPacketizer.packetize(
                    io.netty.buffer.Unpooled.wrappedBuffer(nalu),
                    timestamp90k,
                    marker,
                    new java.util.function.Consumer<ByteBuf>() {
                        @Override
                        public void accept(ByteBuf rtp) {
                            ByteBuf protectedPacket = session.protectOutboundRtp(rtp, allowPlainRelay);
                            ReferenceCountUtil.safeRelease(rtp);
                            if (protectedPacket == null || !protectedPacket.isReadable()) {
                                ReferenceCountUtil.safeRelease(protectedPacket);
                                return;
                            }
                            int bytes = protectedPacket.readableBytes();
                            rtpUdpMediaPlane.sendRtpTo(destination, protectedPacket);
                            session.onPlainRtpRelayed(bytes);
                        }
                    });
        }
    }

    private void maybeRelayPlainRtp(WebRtcSession session, EncodedMediaPacket packet, boolean video) {
        if (session == null || packet == null || !video) {
            return;
        }
        if (rtpUdpMediaPlane == null) {
            return;
        }
        if (!session.canPlainRtpRelay()) {
            return;
        }
        if (packet.sourceProtocol() != EncodedMediaPacket.SourceProtocol.RTSP
                || packet.payloadFormat() != EncodedMediaPacket.PayloadFormat.RTP_PACKET) {
            return;
        }
        InetSocketAddress destination = session.selectedRtpCandidate();
        if (destination == null) {
            return;
        }
        ByteBuf payload = packet.payload();
        if (payload == null || !payload.isReadable()) {
            return;
        }
        ByteBuf protectedPacket = session.protectOutboundRtp(payload, allowPlainRelay);
        if (protectedPacket == null || !protectedPacket.isReadable()) {
            io.netty.util.ReferenceCountUtil.safeRelease(protectedPacket);
            return;
        }
        int bytes = protectedPacket.readableBytes();
        rtpUdpMediaPlane.sendRtpTo(destination, protectedPacket);
        session.onPlainRtpRelayed(bytes);
    }

    private static boolean isVideoCodecConfig(EncodedMediaPacket packet) {
        if (packet == null || packet.trackType() != EncodedMediaPacket.TrackType.VIDEO) {
            return false;
        }
        ByteBuf payload = packet.payload();
        if (payload == null || !payload.isReadable()) {
            return false;
        }
        if (packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTMP
                && packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTMP_TAG) {
            if (payload.readableBytes() < 2) {
                return false;
            }
            int codecId = payload.getUnsignedByte(payload.readerIndex()) & 0x0F;
            int avcPacketType = payload.getUnsignedByte(payload.readerIndex() + 1);
            return codecId == 7 && avcPacketType == 0;
        }
        if (packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTSP
                && packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTP_PACKET) {
            int nalType = readH264NalTypeFromRtp(payload);
            return nalType == 7 || nalType == 8;
        }
        return false;
    }

    private static boolean isVideoKeyFrame(EncodedMediaPacket packet) {
        if (packet == null || packet.trackType() != EncodedMediaPacket.TrackType.VIDEO) {
            return false;
        }
        ByteBuf payload = packet.payload();
        if (payload == null || !payload.isReadable()) {
            return false;
        }
        if (packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTMP
                && packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTMP_TAG) {
            if (payload.readableBytes() < 2) {
                return false;
            }
            int frameType = (payload.getUnsignedByte(payload.readerIndex()) >> 4) & 0x0F;
            int codecId = payload.getUnsignedByte(payload.readerIndex()) & 0x0F;
            int avcPacketType = payload.getUnsignedByte(payload.readerIndex() + 1);
            return codecId == 7 && frameType == 1 && avcPacketType == 1;
        }
        if (packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTSP
                && packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTP_PACKET) {
            return containsIdrFromRtp(payload);
        }
        return false;
    }

    private static boolean containsIdrFromRtp(ByteBuf rtp) {
        int payloadOffset = readRtpPayloadOffset(rtp);
        if (payloadOffset < 0 || payloadOffset >= rtp.writerIndex()) {
            return false;
        }
        int nalHeader = rtp.getUnsignedByte(payloadOffset);
        int nalType = nalHeader & 0x1F;
        if (nalType == 5) {
            return true;
        }
        if (nalType == 24) {
            int offset = payloadOffset + 1;
            int end = rtp.writerIndex();
            while (offset + 2 <= end) {
                int naluLen = rtp.getUnsignedShort(offset);
                offset += 2;
                if (naluLen <= 0 || offset + naluLen > end) {
                    return false;
                }
                int t = rtp.getUnsignedByte(offset) & 0x1F;
                if (t == 5) {
                    return true;
                }
                offset += naluLen;
            }
            return false;
        }
        if (nalType == 28 || nalType == 29) {
            if (payloadOffset + 1 >= rtp.writerIndex()) {
                return false;
            }
            int fuHeader = rtp.getUnsignedByte(payloadOffset + 1);
            boolean start = (fuHeader & 0x80) != 0;
            int originalType = fuHeader & 0x1F;
            return start && originalType == 5;
        }
        return false;
    }

    private static int readH264NalTypeFromRtp(ByteBuf rtp) {
        int payloadOffset = readRtpPayloadOffset(rtp);
        if (payloadOffset < 0 || payloadOffset >= rtp.writerIndex()) {
            return -1;
        }
        return rtp.getUnsignedByte(payloadOffset) & 0x1F;
    }

    private static int readRtpPayloadOffset(ByteBuf rtp) {
        if (rtp == null || rtp.readableBytes() < 12) {
            return -1;
        }
        int ri = rtp.readerIndex();
        int b0 = rtp.getUnsignedByte(ri);
        int cc = b0 & 0x0F;
        boolean extension = (b0 & 0x10) != 0;
        int offset = ri + 12 + (cc * 4);
        if (offset > rtp.writerIndex()) {
            return -1;
        }
        if (extension) {
            if (offset + 4 > rtp.writerIndex()) {
                return -1;
            }
            int extWords = rtp.getUnsignedShort(offset + 2);
            offset += 4 + (extWords * 4);
            if (offset > rtp.writerIndex()) {
                return -1;
            }
        }
        return offset;
    }

    private SessionRelayState relayState(WebRtcSession session) {
        String id = session == null ? null : session.id();
        if (id == null || id.trim().isEmpty()) {
            return new SessionRelayState(session == null ? null : session.streamKey());
        }
        SessionRelayState exists = relayStates.get(id);
        if (exists != null) {
            return exists;
        }
        SessionRelayState created = new SessionRelayState(session.streamKey());
        SessionRelayState prev = relayStates.putIfAbsent(id, created);
        return prev == null ? created : prev;
    }

    private static void parseAvcSequenceHeader(ByteBuf payload, SessionRelayState state) {
        if (payload == null || state == null) {
            return;
        }
        int base = payload.readerIndex() + 5;
        int end = payload.readerIndex() + payload.readableBytes();
        if (base + 6 > end) {
            return;
        }
        int lengthSizeMinusOne = payload.getUnsignedByte(base + 4) & 0x03;
        state.nalLengthSize = lengthSizeMinusOne + 1;
        int off = base + 5;
        int numSps = payload.getUnsignedByte(off) & 0x1F;
        off++;
        byte[] sps = null;
        byte[] pps = null;
        for (int i = 0; i < numSps; i++) {
            if (off + 2 > end) {
                return;
            }
            int len = ((payload.getUnsignedByte(off) << 8) | payload.getUnsignedByte(off + 1));
            off += 2;
            if (len <= 0 || off + len > end) {
                return;
            }
            byte[] nalu = new byte[len];
            payload.getBytes(off, nalu);
            if (i == 0) {
                sps = nalu;
            }
            off += len;
        }
        if (off + 1 > end) {
            return;
        }
        int numPps = payload.getUnsignedByte(off);
        off++;
        for (int i = 0; i < numPps; i++) {
            if (off + 2 > end) {
                return;
            }
            int len = ((payload.getUnsignedByte(off) << 8) | payload.getUnsignedByte(off + 1));
            off += 2;
            if (len <= 0 || off + len > end) {
                return;
            }
            byte[] nalu = new byte[len];
            payload.getBytes(off, nalu);
            if (i == 0) {
                pps = nalu;
            }
            off += len;
        }
        if (sps != null && sps.length > 0) {
            state.sps = sps;
        }
        if (pps != null && pps.length > 0) {
            state.pps = pps;
        }
    }

    private static AvccAccessUnit decodeAvccVideo(ByteBuf payload, SessionRelayState state, boolean keyFrame) {
        if (payload == null || state == null || payload.readableBytes() < 5) {
            return null;
        }
        int off = payload.readerIndex() + 5;
        int end = payload.readerIndex() + payload.readableBytes();
        int nalLengthSize = state.nalLengthSize <= 0 ? 4 : state.nalLengthSize;
        List<byte[]> nals = new ArrayList<byte[]>();
        boolean hasSps = false;
        boolean hasPps = false;
        boolean hasIdr = false;
        while (off + nalLengthSize <= end) {
            int nalLen = 0;
            for (int i = 0; i < nalLengthSize; i++) {
                nalLen = (nalLen << 8) | payload.getUnsignedByte(off + i);
            }
            off += nalLengthSize;
            if (nalLen <= 0 || off + nalLen > end) {
                break;
            }
            byte[] nalu = new byte[nalLen];
            payload.getBytes(off, nalu);
            off += nalLen;
            if (nalu.length == 0) {
                continue;
            }
            int nalType = nalu[0] & 0x1F;
            if (nalType == 7) {
                hasSps = true;
                state.sps = nalu;
            } else if (nalType == 8) {
                hasPps = true;
                state.pps = nalu;
            } else if (nalType == 5) {
                hasIdr = true;
            }
            nals.add(nalu);
        }
        if (nals.isEmpty()) {
            return null;
        }
        return new AvccAccessUnit(nals, keyFrame, hasIdr, hasSps, hasPps);
    }

    private void maybeSendCodecConfigBeforeKeyFrame(
            SessionRelayState state,
            WebRtcSession session,
            InetSocketAddress destination,
            int timestamp90k,
            AvccAccessUnit accessUnit) {
        if (state == null || session == null || destination == null || accessUnit == null) {
            return;
        }
        if (!accessUnit.keyFrame || !accessUnit.hasIdr) {
            return;
        }
        if (!accessUnit.hasSps && state.sps != null && state.sps.length > 0) {
            sendSingleNalu(state, state.sps, timestamp90k, false, session, destination);
        }
        if (!accessUnit.hasPps && state.pps != null && state.pps.length > 0) {
            sendSingleNalu(state, state.pps, timestamp90k, false, session, destination);
        }
    }

    private void sendSingleNalu(
            SessionRelayState state,
            byte[] nalu,
            int timestamp90k,
            boolean marker,
            WebRtcSession session,
            InetSocketAddress destination) {
        if (state == null || nalu == null || nalu.length == 0 || session == null || destination == null) {
            return;
        }
        state.videoPacketizer.packetize(
                io.netty.buffer.Unpooled.wrappedBuffer(nalu),
                timestamp90k,
                marker,
                new java.util.function.Consumer<ByteBuf>() {
                    @Override
                    public void accept(ByteBuf rtp) {
                        ByteBuf protectedPacket = session.protectOutboundRtp(rtp, allowPlainRelay);
                        ReferenceCountUtil.safeRelease(rtp);
                        if (protectedPacket == null || !protectedPacket.isReadable()) {
                            ReferenceCountUtil.safeRelease(protectedPacket);
                            return;
                        }
                        int bytes = protectedPacket.readableBytes();
                        rtpUdpMediaPlane.sendRtpTo(destination, protectedPacket);
                        session.onPlainRtpRelayed(bytes);
                    }
                });
    }

    private static int readSigned24(int b0, int b1, int b2) {
        int value = ((b0 & 0xFF) << 16) | ((b1 & 0xFF) << 8) | (b2 & 0xFF);
        if ((value & 0x800000) != 0) {
            value |= 0xFF000000;
        }
        return value;
    }

    private static final class SessionRelayState {
        private final StreamKey streamKey;
        private final H264RtpPacketizer videoPacketizer = new H264RtpPacketizer();
        private volatile int firstRtmpPtsMs = Integer.MIN_VALUE;
        private volatile int nalLengthSize = 4;
        private volatile byte[] sps;
        private volatile byte[] pps;

        private SessionRelayState(StreamKey streamKey) {
            this.streamKey = streamKey;
        }

        private int toRtpTimestamp90k(int ptsMs) {
            if (firstRtmpPtsMs == Integer.MIN_VALUE) {
                firstRtmpPtsMs = ptsMs;
            }
            int delta = ptsMs - firstRtmpPtsMs;
            if (delta < 0) {
                delta = 0;
            }
            return (int) (((long) delta * 90L) & 0xFFFFFFFFL);
        }
    }

    private static final class AvccAccessUnit {
        private final List<byte[]> nalus;
        private final boolean keyFrame;
        private final boolean hasIdr;
        private final boolean hasSps;
        private final boolean hasPps;

        private AvccAccessUnit(
                List<byte[]> nalus,
                boolean keyFrame,
                boolean hasIdr,
                boolean hasSps,
                boolean hasPps) {
            this.nalus = nalus;
            this.keyFrame = keyFrame;
            this.hasIdr = hasIdr;
            this.hasSps = hasSps;
            this.hasPps = hasPps;
        }
    }
}
