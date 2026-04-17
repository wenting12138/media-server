package com.wenting.mediaserver.protocol.rtsp.sdp;

/**
 * Parsed H264 video media line from SDP (single track MVP).
 */
public final class SdpMedia {

    private final int payloadType;
    private final String rtpmap; // e.g. H264/90000
    private final String fmtp; // optional a=fmtp line value

    public SdpMedia(int payloadType, String rtpmap, String fmtp) {
        this.payloadType = payloadType;
        this.rtpmap = rtpmap;
        this.fmtp = fmtp;
    }

    public int payloadType() {
        return payloadType;
    }

    public String rtpmap() {
        return rtpmap;
    }

    public String fmtp() {
        return fmtp;
    }

    public boolean isH264() {
        return rtpmap != null && rtpmap.toUpperCase(java.util.Locale.ROOT).startsWith("H264/");
    }
}
