package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import io.netty.buffer.ByteBuf;

/**
 * Hook for visible watermark transcoding (decode -> draw -> encode).
 */
interface VisibleWatermarkEngine {

    ByteBuf apply(StreamKey streamKey, ByteBuf rtmpH264Payload, int timestampMs);
}
