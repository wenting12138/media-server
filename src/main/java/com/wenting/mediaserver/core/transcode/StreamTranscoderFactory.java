package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.config.MediaServerConfig;

public final class StreamTranscoderFactory {
    private StreamTranscoderFactory() {
    }

    public static StreamTranscoder create(MediaServerConfig config) {
        if (!config.transcodeEnabled()) {
            return new NoopStreamTranscoder();
        }
        String name = config.rtmpTranscoder();
        if ("ffmpeg".equalsIgnoreCase(name)) {
            return new FfmpegTranscodeProcessor(config);
        }
        if ("hybrid".equalsIgnoreCase(name) || "mixed".equalsIgnoreCase(name)) {
            return new HybridStreamTranscoder(
                    new JavaStreamTranscoder(config),
                    new FfmpegTranscodeProcessor(config));
        }
        if ("java".equalsIgnoreCase(name) || "purejava".equalsIgnoreCase(name)) {
            return new JavaStreamTranscoder(config);
        }
        if ("noop".equalsIgnoreCase(name)) {
            return new NoopStreamTranscoder();
        }
        return new NoopStreamTranscoder();
    }
}
