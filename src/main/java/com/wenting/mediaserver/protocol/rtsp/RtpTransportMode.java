package com.wenting.mediaserver.protocol.rtsp;

/**
 * How RTP is carried for the video track on this RTSP connection.
 */
public enum RtpTransportMode {
    /** RTP/AVP/TCP interleaved on the RTSP connection. */
    TCP_INTERLEAVED,
    /** RTP/AVP unicast UDP (RFC 2326 {@code client_port} / {@code server_port}). */
    UDP
}
