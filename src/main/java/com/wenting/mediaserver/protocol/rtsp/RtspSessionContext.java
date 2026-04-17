package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.protocol.rtp.RtpUdpMediaPlane;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

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

    private RtpTransportMode rtpTransportMode = RtpTransportMode.TCP_INTERLEAVED;
    private RtpUdpMediaPlane.PublisherRtpReceiver publisherUdpReceiver;
    private final List<RtpUdpMediaPlane.PublisherRtpReceiver> publisherAuxUdpReceivers =
            new ArrayList<RtpUdpMediaPlane.PublisherRtpReceiver>();
    private InetSocketAddress subscriberUdpRtpDest;

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

    RtpTransportMode rtpTransportMode() {
        return rtpTransportMode;
    }

    void setRtpTransportMode(RtpTransportMode rtpTransportMode) {
        this.rtpTransportMode = rtpTransportMode;
    }

    RtpUdpMediaPlane.PublisherRtpReceiver publisherUdpReceiver() {
        return publisherUdpReceiver;
    }

    void setPublisherUdpReceiver(RtpUdpMediaPlane.PublisherRtpReceiver publisherUdpReceiver) {
        this.publisherUdpReceiver = publisherUdpReceiver;
    }

    void addPublisherAuxUdpReceiver(RtpUdpMediaPlane.PublisherRtpReceiver receiver) {
        if (receiver != null) {
            publisherAuxUdpReceivers.add(receiver);
        }
    }

    InetSocketAddress subscriberUdpRtpDest() {
        return subscriberUdpRtpDest;
    }

    void setSubscriberUdpRtpDest(InetSocketAddress subscriberUdpRtpDest) {
        this.subscriberUdpRtpDest = subscriberUdpRtpDest;
    }

    void clear() {
        if (publisherUdpReceiver != null) {
            publisherUdpReceiver.close();
            publisherUdpReceiver = null;
        }
        for (RtpUdpMediaPlane.PublisherRtpReceiver aux : publisherAuxUdpReceivers) {
            if (aux != null) {
                aux.close();
            }
        }
        publisherAuxUdpReceivers.clear();
        this.streamKey = null;
        this.sdpText = null;
        this.rtspSessionId = null;
        this.publisherMediaSessionId = null;
        this.videoRtpInterleaved = 0;
        this.videoRtcpInterleaved = 1;
        this.setupRound = 0;
        this.publishedStream = null;
        this.subscribedStream = null;
        this.rtpTransportMode = RtpTransportMode.TCP_INTERLEAVED;
        this.subscriberUdpRtpDest = null;
    }
}
