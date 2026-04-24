package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid transcoder routing by publish protocol:
 * RTMP -> Java transcoder, RTSP -> FFmpeg transcoder.
 */
public final class HybridStreamTranscoder implements StreamTranscoder {

    private static final Logger log = LoggerFactory.getLogger(HybridStreamTranscoder.class);

    private final StreamTranscoder rtmpDelegate;
    private final StreamTranscoder rtspDelegate;
    private final Map<StreamKey, StreamTranscoder> owners = new ConcurrentHashMap<StreamKey, StreamTranscoder>();

    public HybridStreamTranscoder(StreamTranscoder rtmpDelegate, StreamTranscoder rtspDelegate) {
        this.rtmpDelegate = rtmpDelegate == null ? new NoopStreamTranscoder() : rtmpDelegate;
        this.rtspDelegate = rtspDelegate == null ? new NoopStreamTranscoder() : rtspDelegate;
    }

    @Override
    public String name() {
        return "hybrid(rtmp->" + rtmpDelegate.name() + ",rtsp->" + rtspDelegate.name() + ")";
    }

    @Override
    public void bindRegistry(StreamRegistry registry) {
        rtmpDelegate.bindRegistry(registry);
        rtspDelegate.bindRegistry(registry);
    }

    @Override
    public void onPublishStart(PublishContext context) {
        StreamKey key = context.streamKey();
        StreamTranscoder delegate = chooseByContext(context);
        owners.put(key, delegate);
        log.info("Hybrid transcoder route stream={} source={} delegate={}",
                key.path(),
                context.source(),
                delegate.name());
        delegate.onPublishStart(context);
    }

    @Override
    public void onPacket(PublishContext context, EncodedMediaPacket packet) {
        StreamTranscoder owner = owners.get(context.streamKey());
        if (owner == null) {
            owner = chooseByPacket(packet);
        }
        owner.onPacket(context, packet);
    }

    @Override
    public void onPublishStop(PublishContext context) {
        StreamTranscoder owner = owners.remove(context.streamKey());
        if (owner == null) {
            owner = chooseByContext(context);
        }
        owner.onPublishStop(context);
    }

    @Override
    public void close() {
        owners.clear();
        try {
            rtmpDelegate.close();
        } finally {
            rtspDelegate.close();
        }
    }

    private StreamTranscoder chooseByContext(PublishContext context) {
        if (context != null && context.source() == PublishSourceProtocol.RTSP) {
            return rtspDelegate;
        }
        return rtmpDelegate;
    }

    private StreamTranscoder chooseByPacket(EncodedMediaPacket packet) {
        if (packet != null && packet.sourceProtocol() == EncodedMediaPacket.SourceProtocol.RTSP) {
            return rtspDelegate;
        }
        return rtmpDelegate;
    }
}
