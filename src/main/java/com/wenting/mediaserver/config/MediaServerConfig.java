package com.wenting.mediaserver.config;

/**
 * Runtime configuration. Extend with YAML/properties when needed.
 */
public final class MediaServerConfig {

    private static final int DEFAULT_HTTP = 18080;
    private static final int DEFAULT_RTSP = 1554;
    private static final int DEFAULT_RTP_PORT_MIN = 20000;
    private static final int DEFAULT_RTP_PORT_MAX = 30000;

    private final int httpPort;
    private final int rtspPort;
    private final int rtpPortMin;
    private final int rtpPortMax;

    public MediaServerConfig(int httpPort, int rtspPort, int rtpPortMin, int rtpPortMax) {
        this.httpPort = httpPort;
        this.rtspPort = rtspPort;
        this.rtpPortMin = rtpPortMin;
        this.rtpPortMax = rtpPortMax;
    }

    public static MediaServerConfig fromEnvironment() {
        int http = parsePort(System.getenv("MEDIA_HTTP_PORT"), DEFAULT_HTTP);
        int rtsp = parsePort(System.getenv("MEDIA_RTSP_PORT"), DEFAULT_RTSP);
        int rtpMin = parsePort(System.getenv("MEDIA_RTP_PORT_MIN"), DEFAULT_RTP_PORT_MIN);
        int rtpMax = parsePort(System.getenv("MEDIA_RTP_PORT_MAX"), DEFAULT_RTP_PORT_MAX);
        if (rtpMin > rtpMax) {
            int t = rtpMin;
            rtpMin = rtpMax;
            rtpMax = t;
        }
        if (((rtpMax - rtpMin) + 1) < 2) {
            rtpMin = DEFAULT_RTP_PORT_MIN;
            rtpMax = DEFAULT_RTP_PORT_MAX;
        }
        return new MediaServerConfig(http, rtsp, rtpMin, rtpMax);
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int p = Integer.parseInt(raw.trim());
            if (p <= 0 || p > 65535) {
                return fallback;
            }
            return p;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int httpPort() {
        return httpPort;
    }

    public int rtspPort() {
        return rtspPort;
    }

    public int rtpPortMin() {
        return rtpPortMin;
    }

    public int rtpPortMax() {
        return rtpPortMax;
    }

    public String version() {
        String v = MediaServerConfig.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.1.0-SNAPSHOT";
    }

    public String serverId() {
        return "media-server";
    }
}
