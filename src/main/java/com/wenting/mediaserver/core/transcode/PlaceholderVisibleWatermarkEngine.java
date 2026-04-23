package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Placeholder for upcoming pure-Java visible watermark codec path.
 * Current behavior is passthrough with a startup warning.
 */
final class PlaceholderVisibleWatermarkEngine implements VisibleWatermarkEngine {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderVisibleWatermarkEngine.class);
    private final AtomicBoolean warned = new AtomicBoolean(false);

    @Override
    public ByteBuf apply(StreamKey streamKey, ByteBuf rtmpH264Payload, int timestampMs) {
        if (warned.compareAndSet(false, true)) {
            log.warn(
                    "Visible watermark engine is enabled but codec implementation is not installed yet; stream={} will passthrough",
                    streamKey != null ? streamKey.path() : "(unknown)");
        }
        return rtmpH264Payload;
    }
}
