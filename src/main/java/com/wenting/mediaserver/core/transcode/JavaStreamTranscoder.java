package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.rtp.H264RtpDepacketizer;
import com.wenting.mediaserver.protocol.rtp.H264RtpPacketizer;
import com.wenting.mediaserver.protocol.rtp.RtpHeader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Base64;
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
        StreamKey derivedKey = new StreamKey(sourceKey.app(), sourceKey.stream() + outputSuffix);
        String sid = "java-transcode-" + UUID.randomUUID().toString().replace("-", "");
        Optional<PublishedStream> published = localRegistry.tryPublish(derivedKey, sid, context.sdpText(), null);
        if (!published.isPresent()) {
            log.warn("Java transcoder derived stream already exists: {}", derivedKey.path());
            return;
        }
        derivedBySource.put(sourceKey, new DerivedStreamHandle(derivedKey, sid, published.get()));
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
                relayRtp(handle.stream, packet.trackType(), payload);
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
            }
        }
        derivedBySource.clear();
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

    private void relayRtp(PublishedStream target, EncodedMediaPacket.TrackType trackType, ByteBuf rtpPacket) {
        if (trackType == EncodedMediaPacket.TrackType.VIDEO) {
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
