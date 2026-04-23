package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.protocol.rtmp.RtmpMediaPacket;

/**
 * Pluggable sink for publisher-side frames. Default implementation is no-op.
 */
public interface StreamFrameProcessor {

    StreamFrameProcessor NOOP = new StreamFrameProcessor() {
        @Override
        public void onPublishStart(StreamKey key, String sdpText) {
        }

        @Override
        public void onRtmpPacket(StreamKey key, RtmpMediaPacket packet) {
        }

        @Override
        public void onPublishStop(StreamKey key) {
        }
    };

    void onPublishStart(StreamKey key, String sdpText);

    void onRtmpPacket(StreamKey key, RtmpMediaPacket packet);

    void onPublishStop(StreamKey key);
}
