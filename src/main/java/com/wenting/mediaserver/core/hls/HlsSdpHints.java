package com.wenting.mediaserver.core.hls;

final class HlsSdpHints {
    private final byte[] sps;
    private final byte[] pps;
    private final HlsAacConfig aacConfig;

    HlsSdpHints(byte[] sps, byte[] pps, HlsAacConfig aacConfig) {
        this.sps = sps;
        this.pps = pps;
        this.aacConfig = aacConfig;
    }

    byte[] sps() {
        return sps;
    }

    byte[] pps() {
        return pps;
    }

    HlsAacConfig aacConfig() {
        return aacConfig;
    }
}
