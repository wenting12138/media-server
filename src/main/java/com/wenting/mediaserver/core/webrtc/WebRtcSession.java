package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;

/**
 * One WHEP/WebRTC playback signaling session.
 */
public final class WebRtcSession {

    private final String id;
    private final StreamKey streamKey;
    private final String offerSdp;
    private final String remoteAddress;
    private final long createdAtMs;
    private volatile long lastSeenAtMs;
    private volatile String answerSdp;

    WebRtcSession(String id, StreamKey streamKey, String offerSdp, String remoteAddress, long createdAtMs) {
        this.id = id;
        this.streamKey = streamKey;
        this.offerSdp = offerSdp;
        this.remoteAddress = remoteAddress;
        this.createdAtMs = createdAtMs;
        this.lastSeenAtMs = createdAtMs;
    }

    public String id() {
        return id;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String offerSdp() {
        return offerSdp;
    }

    public String answerSdp() {
        return answerSdp;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public long lastSeenAtMs() {
        return lastSeenAtMs;
    }

    public void setAnswerSdp(String answerSdp) {
        this.answerSdp = answerSdp;
        touch();
    }

    public void touch() {
        this.lastSeenAtMs = System.currentTimeMillis();
    }
}
