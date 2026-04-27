package com.wenting.mediaserver.core.hls;

final class HlsRtmpAnnexbFrame {
    private final byte[] annexb;
    private final boolean hasIdr;

    HlsRtmpAnnexbFrame(byte[] annexb, boolean hasIdr) {
        this.annexb = annexb;
        this.hasIdr = hasIdr;
    }

    byte[] annexb() {
        return annexb;
    }

    boolean hasIdr() {
        return hasIdr;
    }
}
