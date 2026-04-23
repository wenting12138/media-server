package com.wenting.mediaserver.core.transcode;

public final class NoopStreamTranscoder implements StreamTranscoder {
    @Override
    public String name() {
        return "noop";
    }

    @Override
    public void onPublishStart(PublishContext context) {
    }

    @Override
    public void onPacket(PublishContext context, EncodedMediaPacket packet) {
    }

    @Override
    public void onPublishStop(PublishContext context) {
    }

    @Override
    public void close() {
    }
}
