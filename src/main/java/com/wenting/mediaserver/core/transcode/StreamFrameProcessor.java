package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;

/**
 * Pluggable sink for publisher-side frames. Default implementation is no-op.
 */
public interface StreamFrameProcessor {

    StreamFrameProcessor NOOP = new StreamFrameProcessor() {
        @Override
        public void onPublishStart(StreamKey key, String sdpText) {
        }

        @Override
        public void onPacket(StreamKey key, EncodedMediaPacket packet) {
        }

        @Override
        public void onPublishStop(StreamKey key) {
        }
    };

    void onPublishStart(StreamKey key, String sdpText);

    void onPacket(StreamKey key, EncodedMediaPacket packet);

    void onPublishStop(StreamKey key);
}
