package com.wenting.mediaserver.protocol.rtsp;

/**
 * High-level RTSP TCP connection role (replaces the former {@code Side} field on the handler).
 * Derived from {@link RtspFsmState}.
 */
public enum RtspConnectionRole {
    /** Only generic signaling (e.g. OPTIONS) or torn down. */
    NONE,
    /** Publish path: ANNOUNCE … RECORD. */
    PUBLISHER,
    /** Play path: DESCRIBE … PLAY. */
    SUBSCRIBER;

    public static RtspConnectionRole fromState(RtspFsmState state) {
        if (state == null || state == RtspFsmState.IDLE) {
            return NONE;
        }
        if (state.isPublisherSide()) {
            return PUBLISHER;
        }
        if (state.isSubscriberSide()) {
            return SUBSCRIBER;
        }
        return NONE;
    }
}
