package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;

/**
 * Mutable per-connection RTSP session fields manipulated by {@link RtspStateMachine}.
 */
final class RtspSessionContext {

    private StreamKey streamKey;
    private String sdpText;
    private String rtspSessionId;
    private String publisherMediaSessionId;
    private int videoRtpInterleaved;
    private int videoRtcpInterleaved = 1;
    private int setupRound;
    private PublishedStream publishedStream;
    private PublishedStream subscribedStream;

    StreamKey streamKey() {
        return streamKey;
    }

    void setStreamKey(StreamKey streamKey) {
        this.streamKey = streamKey;
    }

    String sdpText() {
        return sdpText;
    }

    void setSdpText(String sdpText) {
        this.sdpText = sdpText;
    }

    String rtspSessionId() {
        return rtspSessionId;
    }

    void setRtspSessionId(String rtspSessionId) {
        this.rtspSessionId = rtspSessionId;
    }

    String publisherMediaSessionId() {
        return publisherMediaSessionId;
    }

    void setPublisherMediaSessionId(String publisherMediaSessionId) {
        this.publisherMediaSessionId = publisherMediaSessionId;
    }

    int videoRtpInterleaved() {
        return videoRtpInterleaved;
    }

    int videoRtcpInterleaved() {
        return videoRtcpInterleaved;
    }

    void setVideoInterleaved(int rtpCh, int rtcpCh) {
        this.videoRtpInterleaved = rtpCh;
        this.videoRtcpInterleaved = rtcpCh;
    }

    int setupRound() {
        return setupRound;
    }

    void incrementSetupRound() {
        this.setupRound++;
    }

    PublishedStream publishedStream() {
        return publishedStream;
    }

    void setPublishedStream(PublishedStream publishedStream) {
        this.publishedStream = publishedStream;
    }

    PublishedStream subscribedStream() {
        return subscribedStream;
    }

    void setSubscribedStream(PublishedStream subscribedStream) {
        this.subscribedStream = subscribedStream;
    }

    void clear() {
        this.streamKey = null;
        this.sdpText = null;
        this.rtspSessionId = null;
        this.publisherMediaSessionId = null;
        this.videoRtpInterleaved = 0;
        this.videoRtcpInterleaved = 1;
        this.setupRound = 0;
        this.publishedStream = null;
        this.subscribedStream = null;
    }
}
