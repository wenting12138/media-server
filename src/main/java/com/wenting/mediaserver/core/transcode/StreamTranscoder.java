package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.registry.StreamRegistry;

/**
 * Pluggable stream transcode extension point.
 */
public interface StreamTranscoder extends AutoCloseable {

    String name();

    void onPublishStart(PublishContext context);

    void onPacket(PublishContext context, EncodedMediaPacket packet);

    void onPublishStop(PublishContext context);

    default void bindRegistry(StreamRegistry registry) {
    }

    @Override
    void close();
}
