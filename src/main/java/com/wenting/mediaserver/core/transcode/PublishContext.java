package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;

public final class PublishContext {
    private final StreamKey streamKey;
    private final String sdpText;
    private final PublishSourceProtocol source;

    public PublishContext(StreamKey streamKey, String sdpText) {
        this.streamKey = streamKey;
        this.sdpText = sdpText;
        this.source = (sdpText != null && !sdpText.trim().isEmpty())
                ? PublishSourceProtocol.RTSP
                : PublishSourceProtocol.RTMP;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String sdpText() {
        return sdpText;
    }

    public PublishSourceProtocol source() {
        return source;
    }
}
