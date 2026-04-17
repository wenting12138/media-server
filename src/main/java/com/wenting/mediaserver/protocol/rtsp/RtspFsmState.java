package com.wenting.mediaserver.protocol.rtsp;

/**
 * RTSP session lifecycle for one TCP connection (publish or play path).
 */
public enum RtspFsmState {
    /** No active RTSP media session. */
    IDLE,
    /** Publisher: after successful ANNOUNCE, before RECORD (SETUP may repeat). */
    PUBLISHER_NEGOTIATING,
    /** Publisher: after RECORD registered the stream. */
    PUBLISHER_LIVE,
    /** Subscriber: after successful DESCRIBE, before PLAY (SETUP may repeat). */
    SUBSCRIBER_NEGOTIATING,
    /** Subscriber: after PLAY (additional DESCRIBE/SETUP/PLAY allowed as before). */
    SUBSCRIBER_LIVE;

    /** Publish (push) path, negotiating or already recording. */
    public boolean isPublisherSide() {
        return this == PUBLISHER_NEGOTIATING || this == PUBLISHER_LIVE;
    }

    /** Play (pull) path, negotiating or already playing. */
    public boolean isSubscriberSide() {
        return this == SUBSCRIBER_NEGOTIATING || this == SUBSCRIBER_LIVE;
    }
}
