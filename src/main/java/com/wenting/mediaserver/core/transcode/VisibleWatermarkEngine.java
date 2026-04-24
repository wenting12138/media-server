package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import io.netty.buffer.ByteBuf;

import java.util.Collections;
import java.util.List;

/**
 * Hook for visible watermark transcoding (decode -> draw -> encode).
 */
interface VisibleWatermarkEngine {

    ByteBuf apply(StreamKey streamKey, ByteBuf rtmpH264Payload, int timestampMs);

    default void clear(StreamKey streamKey) {
    }

    default List<ByteBuf> pollPrefixPackets(StreamKey streamKey) {
        return Collections.emptyList();
    }
}
