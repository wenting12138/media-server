package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import io.netty.buffer.ByteBuf;

final class NoopVisibleWatermarkEngine implements VisibleWatermarkEngine {

    @Override
    public ByteBuf apply(StreamKey streamKey, ByteBuf rtmpH264Payload, int timestampMs) {
        return rtmpH264Payload;
    }
}
