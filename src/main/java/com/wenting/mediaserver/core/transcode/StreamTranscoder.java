package com.wenting.mediaserver.core.transcode;

/**
 * Pluggable stream transcode extension point.
 */
public interface StreamTranscoder extends AutoCloseable {

    String name();

    void onPublishStart(PublishContext context);

    void onPacket(PublishContext context, EncodedMediaPacket packet);

    void onPublishStop(PublishContext context);

    @Override
    void close();
}
