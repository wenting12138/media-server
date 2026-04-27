package com.wenting.mediaserver.core.hls;

final class HlsAacConfig {
    private final int payloadType;
    private final int clockRate;
    private final int channelConfig;
    private final int audioObjectType;
    private final int sampleRateIndex;
    private final int sizeLength;
    private final int indexLength;
    private final int indexDeltaLength;

    HlsAacConfig(
            int payloadType,
            int clockRate,
            int channelConfig,
            int audioObjectType,
            int sampleRateIndex,
            int sizeLength,
            int indexLength,
            int indexDeltaLength) {
        this.payloadType = payloadType;
        this.clockRate = Math.max(1, clockRate);
        this.channelConfig = Math.max(1, channelConfig);
        this.audioObjectType = Math.max(1, audioObjectType);
        this.sampleRateIndex = Math.max(0, Math.min(12, sampleRateIndex));
        this.sizeLength = Math.max(1, sizeLength);
        this.indexLength = Math.max(0, indexLength);
        this.indexDeltaLength = Math.max(0, indexDeltaLength);
    }

    int payloadType() {
        return payloadType;
    }

    int clockRate() {
        return clockRate;
    }

    int channelConfig() {
        return channelConfig;
    }

    int audioObjectType() {
        return audioObjectType;
    }

    int sampleRateIndex() {
        return sampleRateIndex;
    }

    int sizeLength() {
        return sizeLength;
    }

    int indexLength() {
        return indexLength;
    }

    int indexDeltaLength() {
        return indexDeltaLength;
    }

    int frameDuration90k() {
        return (int) ((1024L * HlsStreamFrameProcessor.CLOCK_90K) / clockRate);
    }
}
