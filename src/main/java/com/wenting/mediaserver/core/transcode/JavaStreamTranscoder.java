package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.rtp.H264RtpPacketizer;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-Java transcoder baseline: forwards encoded packets to a derived published stream.
 * Real decode/filter/re-encode processors can be added on top of this plugin.
 */
public final class JavaStreamTranscoder implements StreamTranscoder {

    private static final Logger log = LoggerFactory.getLogger(JavaStreamTranscoder.class);

    private final String outputSuffix;
    private final boolean visibleWatermarkEnabled;
    private final H264SeiTimestampWatermarkInjector seiWatermarkInjector = new H264SeiTimestampWatermarkInjector();
    private final VisibleWatermarkEngine visibleWatermarkEngine;
    private final Map<StreamKey, RtpH264AccessUnitNormalizer> rtpAuNormalizers =
            new ConcurrentHashMap<StreamKey, RtpH264AccessUnitNormalizer>();
    private final Map<StreamKey, H264RtpPacketizer> rtpPacketizers =
            new ConcurrentHashMap<StreamKey, H264RtpPacketizer>();
    private final Map<StreamKey, AtomicBoolean> rtpWatermarkApplyLogged =
            new ConcurrentHashMap<StreamKey, AtomicBoolean>();
    private final Map<StreamKey, AtomicBoolean> rtpWatermarkSkipLogged =
            new ConcurrentHashMap<StreamKey, AtomicBoolean>();
    private final Map<StreamKey, AtomicBoolean> rtpVisibleOutputActive =
            new ConcurrentHashMap<StreamKey, AtomicBoolean>();
    private final Map<StreamKey, AtomicBoolean> rtpVisibleDropLogged =
            new ConcurrentHashMap<StreamKey, AtomicBoolean>();
    private final Map<StreamKey, DerivedStreamHandle> derivedBySource = new ConcurrentHashMap<StreamKey, DerivedStreamHandle>();
    private volatile StreamRegistry registry;

    public JavaStreamTranscoder(MediaServerConfig config) {
        this.outputSuffix = config.transcodeOutputSuffix();
        this.visibleWatermarkEnabled = config.javaVisibleWatermarkEnabled();
        this.visibleWatermarkEngine = visibleWatermarkEnabled
                ? new PlaceholderVisibleWatermarkEngine()
                : new NoopVisibleWatermarkEngine();
        log.info("Java transcoder visible watermark enabled={}", visibleWatermarkEnabled);
    }

    @Override
    public String name() {
        return "java";
    }

    @Override
    public void bindRegistry(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onPublishStart(PublishContext context) {
        StreamKey sourceKey = context.streamKey();
        if (isDerivedStream(sourceKey)) {
            return;
        }
        StreamRegistry localRegistry = registry;
        if (localRegistry == null) {
            log.warn("Java transcoder registry not bound, skip start for {}", sourceKey.path());
            return;
        }
        StreamKey derivedKey = new StreamKey(sourceKey.protocol(), sourceKey.app(), sourceKey.stream() + outputSuffix);
        String sid = "java-transcode-" + UUID.randomUUID().toString().replace("-", "");
        Optional<PublishedStream> published = localRegistry.tryPublish(derivedKey, sid, context.sdpText(), null);
        if (!published.isPresent()) {
            log.warn("Java transcoder derived stream already exists: {}", derivedKey.path());
            return;
        }
        derivedBySource.put(sourceKey, new DerivedStreamHandle(derivedKey, sid, published.get()));
        seedRtpH264ParameterSetsFromSdp(context);
        log.info("Java transcoder start source={} derived={}", sourceKey.path(), derivedKey.path());
    }

    @Override
    public void onPacket(PublishContext context, EncodedMediaPacket packet) {
        StreamKey sourceKey = context.streamKey();
        if (isDerivedStream(sourceKey)) {
            return;
        }
        DerivedStreamHandle handle = derivedBySource.get(sourceKey);
        if (handle == null) {
            return;
        }
        ByteBuf payload = packet.payload().retainedDuplicate();
        ByteBuf transformed = payload;
        try {
            if (packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTMP_TAG) {
                transformed = transformRtmpPacket(sourceKey, packet, payload);
                if (packet.trackType() == EncodedMediaPacket.TrackType.VIDEO
                        && packet.codecType() == EncodedMediaPacket.CodecType.H264) {
                    List<ByteBuf> prefixPackets = visibleWatermarkEngine.pollPrefixPackets(sourceKey);
                    if (prefixPackets != null && !prefixPackets.isEmpty()) {
                        log.debug("Java transcoder emit {} prefix packet(s) for {}", prefixPackets.size(), sourceKey.path());
                        for (ByteBuf prefix : prefixPackets) {
                            try {
                                relayRtmp(handle.stream, EncodedMediaPacket.TrackType.VIDEO, prefix, 0, packet.messageStreamId());
                            } finally {
                                ReferenceCountUtil.safeRelease(prefix);
                            }
                        }
                    }
                }
                relayRtmp(handle.stream, packet.trackType(), transformed, packet.timestamp(), packet.messageStreamId());
                return;
            }
            if (packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTP_PACKET) {
                relayRtp(sourceKey, handle.stream, packet.trackType(), packet.codecType(), payload);
            }
        } finally {
            if (transformed != payload) {
                ReferenceCountUtil.safeRelease(transformed);
            }
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    @Override
    public void onPublishStop(PublishContext context) {
        StreamKey sourceKey = context.streamKey();
        DerivedStreamHandle handle = derivedBySource.remove(sourceKey);
        if (handle == null) {
            return;
        }
        StreamRegistry localRegistry = registry;
        if (localRegistry != null) {
            localRegistry.unpublish(handle.derivedKey, handle.publisherSessionId);
        }
        seiWatermarkInjector.clear(sourceKey);
        visibleWatermarkEngine.clear(sourceKey);
        closeRtpNormalizer(sourceKey);
        rtpPacketizers.remove(sourceKey);
        rtpWatermarkApplyLogged.remove(sourceKey);
        rtpWatermarkSkipLogged.remove(sourceKey);
        rtpVisibleOutputActive.remove(sourceKey);
        rtpVisibleDropLogged.remove(sourceKey);
        log.info("Java transcoder stop source={} derived={}", sourceKey.path(), handle.derivedKey.path());
    }

    @Override
    public void close() {
        StreamRegistry localRegistry = registry;
        if (localRegistry != null) {
            for (Map.Entry<StreamKey, DerivedStreamHandle> entry : derivedBySource.entrySet()) {
                DerivedStreamHandle handle = entry.getValue();
                localRegistry.unpublish(handle.derivedKey, handle.publisherSessionId);
                seiWatermarkInjector.clear(entry.getKey());
                visibleWatermarkEngine.clear(entry.getKey());
                closeRtpNormalizer(entry.getKey());
                rtpPacketizers.remove(entry.getKey());
                rtpWatermarkApplyLogged.remove(entry.getKey());
                rtpWatermarkSkipLogged.remove(entry.getKey());
                rtpVisibleOutputActive.remove(entry.getKey());
                rtpVisibleDropLogged.remove(entry.getKey());
            }
        }
        derivedBySource.clear();
        for (StreamKey key : rtpAuNormalizers.keySet()) {
            closeRtpNormalizer(key);
        }
        rtpAuNormalizers.clear();
        rtpPacketizers.clear();
        rtpWatermarkApplyLogged.clear();
        rtpWatermarkSkipLogged.clear();
        rtpVisibleOutputActive.clear();
        rtpVisibleDropLogged.clear();
    }

    private ByteBuf transformRtmpPacket(StreamKey sourceKey, EncodedMediaPacket packet, ByteBuf payload) {
        if (packet.trackType() != EncodedMediaPacket.TrackType.VIDEO
                || packet.codecType() != EncodedMediaPacket.CodecType.H264) {
            return payload;
        }
        if (visibleWatermarkEnabled) {
            ByteBuf visibleTransformed = visibleWatermarkEngine.apply(sourceKey, payload, packet.timestamp());
            return visibleTransformed == null ? payload : visibleTransformed;
        }
        ByteBuf seiTransformed = seiWatermarkInjector.injectIfNeeded(sourceKey, payload, packet.timestamp());
        ByteBuf withSei = seiTransformed == null ? payload : seiTransformed;
        return withSei;
    }

    private void relayRtmp(PublishedStream target, EncodedMediaPacket.TrackType trackType, ByteBuf payload, int timestamp, int messageStreamId) {
        int msid = messageStreamId <= 0 ? 1 : messageStreamId;
        if (trackType == EncodedMediaPacket.TrackType.VIDEO) {
            target.onPublisherRtmpVideo(payload, timestamp, msid);
            return;
        }
        if (trackType == EncodedMediaPacket.TrackType.AUDIO) {
            target.onPublisherRtmpAudio(payload, timestamp, msid);
            return;
        }
        if (trackType == EncodedMediaPacket.TrackType.DATA) {
            target.onPublisherRtmpData(payload, timestamp, msid);
        }
    }

    private void relayRtp(
            StreamKey sourceKey,
            PublishedStream target,
            EncodedMediaPacket.TrackType trackType,
            EncodedMediaPacket.CodecType codecType,
            ByteBuf rtpPacket) {
        if (trackType == EncodedMediaPacket.TrackType.VIDEO) {
            if (codecType == EncodedMediaPacket.CodecType.H264 && visibleWatermarkEnabled) {
                transcodeRtpH264WithVisibleWatermark(sourceKey, target, rtpPacket);
                return;
            }
            target.onPublisherVideoRtp(rtpPacket);
            return;
        }
        if (trackType == EncodedMediaPacket.TrackType.AUDIO) {
            target.onPublisherAudioRtp(rtpPacket);
        }
    }

    private boolean isDerivedStream(StreamKey key) {
        return key != null && key.stream().endsWith(outputSuffix);
    }

    private void transcodeRtpH264WithVisibleWatermark(StreamKey sourceKey, PublishedStream target, ByteBuf rtpPacket) {
        RtpH264AccessUnitNormalizer normalizer = rtpAuNormalizers.computeIfAbsent(
                sourceKey,
                key -> new RtpH264AccessUnitNormalizer());
        H264RtpPacketizer packetizer = rtpPacketizers.computeIfAbsent(
                sourceKey,
                key -> new H264RtpPacketizer());
        List<RtpH264AccessUnitNormalizer.AccessUnit> accessUnits = normalizer.ingest(rtpPacket);
        if (accessUnits.isEmpty()) {
            return;
        }
        for (RtpH264AccessUnitNormalizer.AccessUnit accessUnit : accessUnits) {
            if (accessUnit == null) {
                continue;
            }
            ByteBuf inputAnnexb = accessUnit.annexB();
            ByteBuf outputAnnexb = inputAnnexb;
            ByteBuf transformedAnnexb = null;
            try {
                int timestampMs = (int) ((accessUnit.timestamp90k() & 0xFFFFFFFFL) * 1000L / 90000L);
                if (accessUnit.hasVcl() && accessUnit.hasSps() && accessUnit.hasPps()) {
                    AtomicBoolean applyLogged = rtpWatermarkApplyLogged.computeIfAbsent(
                            sourceKey, key -> new AtomicBoolean(false));
                    if (applyLogged.compareAndSet(false, true)) {
                        log.info("Java transcoder apply visible watermark for {} ts={} (first AU ready)",
                                sourceKey.path(), timestampMs);
                    }
                    transformedAnnexb = visibleWatermarkEngine.applyAnnexB(
                            sourceKey,
                            inputAnnexb,
                            timestampMs,
                            accessUnit.keyFrame());
                    if (transformedAnnexb != null && transformedAnnexb.isReadable() && transformedAnnexb != inputAnnexb) {
                        outputAnnexb = transformedAnnexb;
                    }
                } else {
                    AtomicBoolean skipLogged = rtpWatermarkSkipLogged.computeIfAbsent(
                            sourceKey, key -> new AtomicBoolean(false));
                    if (skipLogged.compareAndSet(false, true)) {
                        log.warn("Java transcoder skip visible watermark for {}: hasVcl={} hasSps={} hasPps={}",
                                sourceKey.path(), accessUnit.hasVcl(), accessUnit.hasSps(), accessUnit.hasPps());
                    }
                }
                AtomicBoolean activeFlag = rtpVisibleOutputActive.computeIfAbsent(
                        sourceKey, key -> new AtomicBoolean(false));
                boolean transformedReady = outputAnnexb != inputAnnexb;
                if (!activeFlag.get()) {
                    if (transformedReady && containsIdr(outputAnnexb)) {
                        activeFlag.set(true);
                        log.info("Java transcoder RTP visible stream activated at IDR for {}", sourceKey.path());
                    } else if (transformedReady) {
                        outputAnnexb = inputAnnexb;
                    }
                } else if (!transformedReady) {
                    AtomicBoolean dropLogged = rtpVisibleDropLogged.computeIfAbsent(
                            sourceKey, key -> new AtomicBoolean(false));
                    if (dropLogged.compareAndSet(false, true)) {
                        log.warn("Java transcoder drop RTP AU after visible stream activation for {} (avoid mixed bitstream)",
                                sourceKey.path());
                    }
                    continue;
                }
                relayAnnexbAccessUnitAsRtp(target, packetizer, accessUnit.timestamp90k(), outputAnnexb);
            } finally {
                if (transformedAnnexb != null && transformedAnnexb != inputAnnexb) {
                    ReferenceCountUtil.safeRelease(transformedAnnexb);
                }
                accessUnit.release();
            }
        }
    }

    private boolean containsIdr(ByteBuf annexbAu) {
        if (annexbAu == null || !annexbAu.isReadable()) {
            return false;
        }
        List<ByteBuf> nals = H264Annexb.splitNalUnits(annexbAu);
        try {
            for (ByteBuf nal : nals) {
                if (H264Annexb.nalType(nal) == 5) {
                    return true;
                }
            }
            return false;
        } finally {
            for (ByteBuf nal : nals) {
                ReferenceCountUtil.safeRelease(nal);
            }
        }
    }

    private void relayAnnexbAccessUnitAsRtp(
            PublishedStream target,
            H264RtpPacketizer packetizer,
            int timestamp90k,
            ByteBuf annexbAccessUnit) {
        List<ByteBuf> nals = H264Annexb.splitNalUnits(annexbAccessUnit);
        if (nals.isEmpty()) {
            return;
        }
        int lastVclIndex = -1;
        for (int i = 0; i < nals.size(); i++) {
            int nalType = H264Annexb.nalType(nals.get(i));
            if (H264Annexb.isVcl(nalType)) {
                lastVclIndex = i;
            }
        }
        if (lastVclIndex < 0) {
            lastVclIndex = nals.size() - 1;
        }
        for (int i = 0; i < nals.size(); i++) {
            ByteBuf nal = nals.get(i);
            try {
                final boolean marker = i == lastVclIndex;
                packetizer.packetize(nal, timestamp90k, marker, target::onPublisherVideoRtp);
            } finally {
                ReferenceCountUtil.safeRelease(nal);
            }
        }
    }

    private void closeRtpNormalizer(StreamKey sourceKey) {
        RtpH264AccessUnitNormalizer normalizer = rtpAuNormalizers.remove(sourceKey);
        if (normalizer == null) {
            return;
        }
        RtpH264AccessUnitNormalizer.AccessUnit tail = normalizer.flush();
        if (tail != null) {
            tail.release();
        }
        normalizer.close();
    }

    private void relayBootstrapWhenNoSubscriber(
            StreamKey sourceKey,
            PublishedStream target,
            EncodedMediaPacket packet,
            ByteBuf payload) {
        if (packet.payloadFormat() != EncodedMediaPacket.PayloadFormat.RTMP_TAG) {
            return;
        }
        if (packet.trackType() == EncodedMediaPacket.TrackType.DATA) {
            relayRtmp(target, packet.trackType(), payload, packet.timestamp(), packet.messageStreamId());
            return;
        }
        if (packet.trackType() == EncodedMediaPacket.TrackType.VIDEO
                && packet.codecType() == EncodedMediaPacket.CodecType.H264
                && isRtmpVideoSequenceHeader(payload)) {
            ByteBuf maybeTransformed = transformRtmpPacket(sourceKey, packet, payload);
            try {
                relayRtmp(target, packet.trackType(), maybeTransformed, packet.timestamp(), packet.messageStreamId());
            } finally {
                if (maybeTransformed != payload) {
                    ReferenceCountUtil.safeRelease(maybeTransformed);
                }
            }
            return;
        }
        if (packet.trackType() == EncodedMediaPacket.TrackType.AUDIO
                && packet.codecType() == EncodedMediaPacket.CodecType.AAC
                && isRtmpAacSequenceHeader(payload)) {
            relayRtmp(target, packet.trackType(), payload, packet.timestamp(), packet.messageStreamId());
        }
    }

    private static boolean isRtmpVideoSequenceHeader(ByteBuf payload) {
        if (payload == null || payload.readableBytes() < 2) {
            return false;
        }
        int ri = payload.readerIndex();
        int codecId = payload.getUnsignedByte(ri) & 0x0F;
        int avcPacketType = payload.getUnsignedByte(ri + 1);
        return codecId == 7 && avcPacketType == 0;
    }

    private static boolean isRtmpAacSequenceHeader(ByteBuf payload) {
        if (payload == null || payload.readableBytes() < 2) {
            return false;
        }
        int ri = payload.readerIndex();
        int soundFormat = (payload.getUnsignedByte(ri) >> 4) & 0x0F;
        int aacPacketType = payload.getUnsignedByte(ri + 1);
        return soundFormat == 10 && aacPacketType == 0;
    }

    private void seedRtpH264ParameterSetsFromSdp(PublishContext context) {
        if (context == null || context.source() != PublishSourceProtocol.RTSP) {
            return;
        }
        String sdp = context.sdpText();
        if (sdp == null || sdp.trim().isEmpty()) {
            return;
        }
        byte[][] sets = parseSpropParameterSets(sdp);
        if (sets == null) {
            return;
        }
        byte[] sps = sets[0];
        byte[] pps = sets[1];
        if ((sps == null || sps.length == 0) && (pps == null || pps.length == 0)) {
            return;
        }
        RtpH264AccessUnitNormalizer normalizer = rtpAuNormalizers.computeIfAbsent(
                context.streamKey(),
                key -> new RtpH264AccessUnitNormalizer());
        normalizer.seedParameterSets(sps, pps);
        log.info("Java transcoder seeded RTP H264 parameter sets for {} from SDP (sps={}B, pps={}B)",
                context.streamKey().path(),
                sps == null ? 0 : sps.length,
                pps == null ? 0 : pps.length);
    }

    private static byte[][] parseSpropParameterSets(String sdpText) {
        if (sdpText == null || sdpText.isEmpty()) {
            return null;
        }
        String[] lines = sdpText.split("\r\n|\n");
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase();
            if (!lower.startsWith("a=fmtp:") || lower.indexOf("sprop-parameter-sets=") < 0) {
                continue;
            }
            int keyIndex = lower.indexOf("sprop-parameter-sets=");
            String value = line.substring(keyIndex + "sprop-parameter-sets=".length());
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) {
                value = value.substring(0, semicolon);
            }
            String[] parts = value.split(",");
            byte[] sps = decodeB64(parts, 0);
            byte[] pps = decodeB64(parts, 1);
            return new byte[][]{sps, pps};
        }
        return null;
    }

    private static byte[] decodeB64(String[] parts, int idx) {
        if (parts == null || idx < 0 || idx >= parts.length) {
            return null;
        }
        String v = parts[idx] == null ? "" : parts[idx].trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class DerivedStreamHandle {
        private final StreamKey derivedKey;
        private final String publisherSessionId;
        private final PublishedStream stream;

        private DerivedStreamHandle(StreamKey derivedKey, String publisherSessionId, PublishedStream stream) {
            this.derivedKey = derivedKey;
            this.publisherSessionId = publisherSessionId;
            this.stream = stream;
        }
    }
}
