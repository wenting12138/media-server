package com.wenting.mediaserver.core.hls;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.EncodedMediaPacket;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-Java HLS processor.
 * Supports RTSP(RTP/H264+AAC) and RTMP(H264+AAC) inputs.
 */
public final class HlsStreamFrameProcessor implements StreamFrameProcessor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HlsStreamFrameProcessor.class);

    static final int CLOCK_90K = 90000;

    private final boolean enabled;
    private final Path rootDir;
    private final int segmentDuration90k;
    private final int listSize;
    private final boolean deleteSegments;
    private final Map<StreamKey, HlsStreamSession> sessions = new ConcurrentHashMap<StreamKey, HlsStreamSession>();

    public HlsStreamFrameProcessor(MediaServerConfig config) {
        this.enabled = config != null && config.hlsEnabled();
        this.rootDir = Paths.get(config == null ? "hls" : config.hlsRoot()).toAbsolutePath().normalize();
        int seconds = config == null ? 2 : config.hlsSegmentSeconds();
        this.segmentDuration90k = Math.max(1, seconds) * CLOCK_90K;
        this.listSize = Math.max(2, config == null ? 6 : config.hlsListSize());
        this.deleteSegments = config == null || config.hlsDeleteSegments();
        if (enabled) {
            try {
                Files.createDirectories(rootDir);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create HLS root dir: " + rootDir, e);
            }
            log.info("HLS enabled root={} segment={}s list={} deleteSegments={}",
                    rootDir, segmentDuration90k / CLOCK_90K, listSize, deleteSegments);
        } else {
            log.info("HLS disabled");
        }
    }

    public Path rootDir() {
        return rootDir;
    }

    @Override
    public void onPublishStart(StreamKey key, String sdpText) {
        if (!enabled || key == null) {
            return;
        }
        HlsStreamSession session = new HlsStreamSession(
                key,
                sdpText,
                rootDir,
                segmentDuration90k,
                listSize,
                deleteSegments);
        HlsStreamSession previous = sessions.putIfAbsent(key, session);
        if (previous == null) {
            log.info("HLS session start stream={} dir={}", key.path(), session.streamDir());
        }
    }

    @Override
    public void onPacket(StreamKey key, EncodedMediaPacket packet) {
        if (!enabled || key == null || packet == null) {
            return;
        }
        HlsStreamSession session = sessions.get(key);
        if (session == null) {
            return;
        }
        if (packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTSP
                && packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTP_PACKET) {
            if (packet.trackType() == EncodedMediaPacket.TrackType.VIDEO
                    && packet.codecType() == EncodedMediaPacket.CodecType.H264) {
                session.onVideoRtpPacket(packet.payload());
                return;
            }
            if (packet.trackType() == EncodedMediaPacket.TrackType.AUDIO) {
                session.onAudioRtpPacket(packet.payload(), System.nanoTime());
            }
            return;
        }
        if (packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTMP
                && packet.payloadFormat() == EncodedMediaPacket.PayloadFormat.RTMP_TAG) {
            if (packet.trackType() == EncodedMediaPacket.TrackType.VIDEO
                    && packet.codecType() == EncodedMediaPacket.CodecType.H264) {
                session.onRtmpVideoTag(packet.payload(), packet.timestamp());
                return;
            }
            if (packet.trackType() == EncodedMediaPacket.TrackType.AUDIO
                    && packet.codecType() == EncodedMediaPacket.CodecType.AAC) {
                session.onRtmpAudioTag(packet.payload(), packet.timestamp());
            }
        }
    }

    @Override
    public void onPublishStop(StreamKey key) {
        if (!enabled || key == null) {
            return;
        }
        HlsStreamSession session = sessions.remove(key);
        if (session != null) {
            session.close();
            log.info("HLS session stop stream={}", key.path());
        }
    }

    @Override
    public void close() {
        for (HlsStreamSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }
}
