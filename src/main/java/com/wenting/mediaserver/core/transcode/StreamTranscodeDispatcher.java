package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges stream lifecycle callbacks to a pluggable stream transcoder.
 */
public final class StreamTranscodeDispatcher implements StreamFrameProcessor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamTranscodeDispatcher.class);

    private final StreamTranscoder transcoder;
    private final Map<StreamKey, PublishContext> contexts = new ConcurrentHashMap<StreamKey, PublishContext>();

    public StreamTranscodeDispatcher(StreamTranscoder transcoder) {
        this.transcoder = transcoder == null ? new NoopStreamTranscoder() : transcoder;
        log.info("Stream transcoder selected: {}", this.transcoder.name());
    }

    @Override
    public void onPublishStart(StreamKey key, String sdpText) {
        PublishContext context = new PublishContext(key, sdpText);
        contexts.put(key, context);
        transcoder.onPublishStart(context);
    }

    @Override
    public void onPacket(StreamKey key, EncodedMediaPacket packet) {
        PublishContext context = contexts.get(key);
        if (context == null) {
            return;
        }
        transcoder.onPacket(context, packet);
    }

    @Override
    public void onPublishStop(StreamKey key) {
        PublishContext context = contexts.remove(key);
        if (context == null) {
            return;
        }
        transcoder.onPublishStop(context);
    }

    @Override
    public void close() {
        transcoder.close();
        contexts.clear();
    }
}
