package com.wenting.mediaserver.config;

/**
 * Runtime configuration. Extend with YAML/properties when needed.
 */
public final class MediaServerConfig {

    private static final int DEFAULT_HTTP = 18080;
    private static final int DEFAULT_RTSP = 1554;

    private final int httpPort;
    private final int rtspPort;

    public MediaServerConfig(int httpPort, int rtspPort) {
        this.httpPort = httpPort;
        this.rtspPort = rtspPort;
    }

    public static MediaServerConfig fromEnvironment() {
        int http = parsePort(System.getenv("MEDIA_HTTP_PORT"), DEFAULT_HTTP);
        int rtsp = parsePort(System.getenv("MEDIA_RTSP_PORT"), DEFAULT_RTSP);
        return new MediaServerConfig(http, rtsp);
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

    public String version() {
        String v = MediaServerConfig.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.1.0-SNAPSHOT";
    }

    public String serverId() {
        return "java-media-server";
    }
}
