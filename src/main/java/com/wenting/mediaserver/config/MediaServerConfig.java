package com.wenting.mediaserver.config;

/**
 * Runtime configuration. Extend with YAML/properties when needed.
 */
public final class MediaServerConfig {

    private static final int DEFAULT_HTTP = 18080;
    private static final int DEFAULT_RTSP = 1554;
    private static final int DEFAULT_RTMP = 11935;
    private static final int DEFAULT_RTP_PORT_MIN = 20000;
    private static final int DEFAULT_RTP_PORT_MAX = 30000;
    private static final boolean DEFAULT_TRANSCODE_ENABLED = true;
    private static final String DEFAULT_FFMPEG_BIN = "ffmpeg";
    private static final String DEFAULT_TRANSCODE_OUTPUT_SUFFIX = "__wm";
    private static final String DEFAULT_TRANSCODE_INPUT_HOST = "127.0.0.1";
    private static final int DEFAULT_TRANSCODE_QUEUE_SIZE = 2048;
    private static final String DEFAULT_RTMP_TRANSCODER = "java";
    private static final boolean DEFAULT_JAVA_VISIBLE_WATERMARK_ENABLED = true;
    private static final boolean DEFAULT_HLS_ENABLED = true;
    private static final String DEFAULT_HLS_ROOT = "hls";
    private static final int DEFAULT_HLS_SEGMENT_SECONDS = 2;
    private static final int DEFAULT_HLS_LIST_SIZE = 6;
    private static final boolean DEFAULT_HLS_DELETE_SEGMENTS = true;

    private final int httpPort;
    private final int rtspPort;
    private final int rtmpPort;
    private final int rtpPortMin;
    private final int rtpPortMax;
    private final boolean transcodeEnabled;
    private final String ffmpegBin;
    private final String transcodeOutputSuffix;
    private final String transcodeInputHost;
    private final int transcodeQueueSize;
    private final String rtmpTranscoder;
    private final boolean javaVisibleWatermarkEnabled;
    private final boolean hlsEnabled;
    private final String hlsRoot;
    private final int hlsSegmentSeconds;
    private final int hlsListSize;
    private final boolean hlsDeleteSegments;

    public MediaServerConfig(int httpPort, int rtspPort, int rtmpPort, int rtpPortMin, int rtpPortMax) {
        this(
                httpPort,
                rtspPort,
                rtmpPort,
                rtpPortMin,
                rtpPortMax,
                DEFAULT_TRANSCODE_ENABLED,
                DEFAULT_FFMPEG_BIN,
                DEFAULT_TRANSCODE_OUTPUT_SUFFIX,
                DEFAULT_TRANSCODE_INPUT_HOST,
                DEFAULT_TRANSCODE_QUEUE_SIZE,
                DEFAULT_RTMP_TRANSCODER,
                DEFAULT_JAVA_VISIBLE_WATERMARK_ENABLED,
                DEFAULT_HLS_ENABLED,
                DEFAULT_HLS_ROOT,
                DEFAULT_HLS_SEGMENT_SECONDS,
                DEFAULT_HLS_LIST_SIZE,
                DEFAULT_HLS_DELETE_SEGMENTS);
    }

    public MediaServerConfig(
            int httpPort,
            int rtspPort,
            int rtmpPort,
            int rtpPortMin,
            int rtpPortMax,
            boolean transcodeEnabled,
            String ffmpegBin,
            String transcodeOutputSuffix,
            String transcodeInputHost,
            int transcodeQueueSize,
            String rtmpTranscoder) {
        this(
                httpPort,
                rtspPort,
                rtmpPort,
                rtpPortMin,
                rtpPortMax,
                transcodeEnabled,
                ffmpegBin,
                transcodeOutputSuffix,
                transcodeInputHost,
                transcodeQueueSize,
                rtmpTranscoder,
                DEFAULT_JAVA_VISIBLE_WATERMARK_ENABLED,
                DEFAULT_HLS_ENABLED,
                DEFAULT_HLS_ROOT,
                DEFAULT_HLS_SEGMENT_SECONDS,
                DEFAULT_HLS_LIST_SIZE,
                DEFAULT_HLS_DELETE_SEGMENTS);
    }

    public MediaServerConfig(
            int httpPort,
            int rtspPort,
            int rtmpPort,
            int rtpPortMin,
            int rtpPortMax,
            boolean transcodeEnabled,
            String ffmpegBin,
            String transcodeOutputSuffix,
            String transcodeInputHost,
            int transcodeQueueSize,
            String rtmpTranscoder,
            boolean javaVisibleWatermarkEnabled) {
        this(
                httpPort,
                rtspPort,
                rtmpPort,
                rtpPortMin,
                rtpPortMax,
                transcodeEnabled,
                ffmpegBin,
                transcodeOutputSuffix,
                transcodeInputHost,
                transcodeQueueSize,
                rtmpTranscoder,
                javaVisibleWatermarkEnabled,
                DEFAULT_HLS_ENABLED,
                DEFAULT_HLS_ROOT,
                DEFAULT_HLS_SEGMENT_SECONDS,
                DEFAULT_HLS_LIST_SIZE,
                DEFAULT_HLS_DELETE_SEGMENTS);
    }

    public MediaServerConfig(
            int httpPort,
            int rtspPort,
            int rtmpPort,
            int rtpPortMin,
            int rtpPortMax,
            boolean transcodeEnabled,
            String ffmpegBin,
            String transcodeOutputSuffix,
            String transcodeInputHost,
            int transcodeQueueSize,
            String rtmpTranscoder,
            boolean javaVisibleWatermarkEnabled,
            boolean hlsEnabled,
            String hlsRoot,
            int hlsSegmentSeconds,
            int hlsListSize,
            boolean hlsDeleteSegments) {
        this.httpPort = httpPort;
        this.rtspPort = rtspPort;
        this.rtmpPort = rtmpPort;
        this.rtpPortMin = rtpPortMin;
        this.rtpPortMax = rtpPortMax;
        this.transcodeEnabled = transcodeEnabled;
        this.ffmpegBin = ffmpegBin == null || ffmpegBin.trim().isEmpty() ? DEFAULT_FFMPEG_BIN : ffmpegBin.trim();
        this.transcodeOutputSuffix = transcodeOutputSuffix == null || transcodeOutputSuffix.trim().isEmpty()
                ? DEFAULT_TRANSCODE_OUTPUT_SUFFIX
                : transcodeOutputSuffix.trim();
        this.transcodeInputHost = transcodeInputHost == null || transcodeInputHost.trim().isEmpty()
                ? DEFAULT_TRANSCODE_INPUT_HOST
                : transcodeInputHost.trim();
        this.transcodeQueueSize = transcodeQueueSize <= 0 ? DEFAULT_TRANSCODE_QUEUE_SIZE : transcodeQueueSize;
        this.rtmpTranscoder = rtmpTranscoder == null || rtmpTranscoder.trim().isEmpty()
                ? DEFAULT_RTMP_TRANSCODER
                : rtmpTranscoder.trim();
        this.javaVisibleWatermarkEnabled = javaVisibleWatermarkEnabled;
        this.hlsEnabled = hlsEnabled;
        this.hlsRoot = hlsRoot == null || hlsRoot.trim().isEmpty() ? DEFAULT_HLS_ROOT : hlsRoot.trim();
        this.hlsSegmentSeconds = hlsSegmentSeconds <= 0 ? DEFAULT_HLS_SEGMENT_SECONDS : hlsSegmentSeconds;
        this.hlsListSize = hlsListSize <= 0 ? DEFAULT_HLS_LIST_SIZE : hlsListSize;
        this.hlsDeleteSegments = hlsDeleteSegments;
    }

    public static MediaServerConfig fromEnvironment() {
        int http = parsePort(System.getenv("MEDIA_HTTP_PORT"), DEFAULT_HTTP);
        int rtsp = parsePort(System.getenv("MEDIA_RTSP_PORT"), DEFAULT_RTSP);
        int rtmp = parsePort(System.getenv("MEDIA_RTMP_PORT"), DEFAULT_RTMP);
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
        boolean transcodeEnabled = parseBoolean(System.getenv("MEDIA_TRANSCODE_ENABLED"), DEFAULT_TRANSCODE_ENABLED);
        String ffmpegBin = parseString(System.getenv("MEDIA_FFMPEG_BIN"), DEFAULT_FFMPEG_BIN);
        String suffix = parseString(System.getenv("MEDIA_TRANSCODE_SUFFIX"), DEFAULT_TRANSCODE_OUTPUT_SUFFIX);
        String inputHost = parseString(System.getenv("MEDIA_TRANSCODE_INPUT_HOST"), DEFAULT_TRANSCODE_INPUT_HOST);
        int queueSize = parsePositiveInt(System.getenv("MEDIA_TRANSCODE_QUEUE_SIZE"), DEFAULT_TRANSCODE_QUEUE_SIZE);
        String rtmpTranscoder = parseString(System.getenv("MEDIA_RTMP_TRANSCODER"), DEFAULT_RTMP_TRANSCODER);
        boolean javaVisibleWatermarkEnabled = parseBoolean(
                System.getenv("MEDIA_JAVA_VISIBLE_WATERMARK_ENABLED"),
                DEFAULT_JAVA_VISIBLE_WATERMARK_ENABLED);
        boolean hlsEnabled = parseBoolean(System.getenv("MEDIA_HLS_ENABLED"), DEFAULT_HLS_ENABLED);
        String hlsRoot = parseString(System.getenv("MEDIA_HLS_ROOT"), DEFAULT_HLS_ROOT);
        int hlsSegmentSeconds = parsePositiveInt(System.getenv("MEDIA_HLS_SEGMENT_SECONDS"), DEFAULT_HLS_SEGMENT_SECONDS);
        int hlsListSize = parsePositiveInt(System.getenv("MEDIA_HLS_LIST_SIZE"), DEFAULT_HLS_LIST_SIZE);
        boolean hlsDeleteSegments = parseBoolean(System.getenv("MEDIA_HLS_DELETE_SEGMENTS"), DEFAULT_HLS_DELETE_SEGMENTS);
        return new MediaServerConfig(
                http,
                rtsp,
                rtmp,
                rtpMin,
                rtpMax,
                transcodeEnabled,
                ffmpegBin,
                suffix,
                inputHost,
                queueSize,
                rtmpTranscoder,
                javaVisibleWatermarkEnabled,
                hlsEnabled,
                hlsRoot,
                hlsSegmentSeconds,
                hlsListSize,
                hlsDeleteSegments);
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

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String v = raw.trim().toLowerCase();
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "off".equals(v)) {
            return false;
        }
        return fallback;
    }

    private static String parseString(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        return raw.trim();
    }

    public int httpPort() {
        return httpPort;
    }

    public int rtspPort() {
        return rtspPort;
    }

    public int rtmpPort() {
        return rtmpPort;
    }

    public int rtpPortMin() {
        return rtpPortMin;
    }

    public int rtpPortMax() {
        return rtpPortMax;
    }

    public boolean transcodeEnabled() {
        return transcodeEnabled;
    }

    public String ffmpegBin() {
        return ffmpegBin;
    }

    public String transcodeOutputSuffix() {
        return transcodeOutputSuffix;
    }

    public String transcodeInputHost() {
        return transcodeInputHost;
    }

    public int transcodeQueueSize() {
        return transcodeQueueSize;
    }

    public String rtmpTranscoder() {
        return rtmpTranscoder;
    }

    public boolean javaVisibleWatermarkEnabled() {
        return javaVisibleWatermarkEnabled;
    }

    public boolean hlsEnabled() {
        return hlsEnabled;
    }

    public String hlsRoot() {
        return hlsRoot;
    }

    public int hlsSegmentSeconds() {
        return hlsSegmentSeconds;
    }

    public int hlsListSize() {
        return hlsListSize;
    }

    public boolean hlsDeleteSegments() {
        return hlsDeleteSegments;
    }

    public String version() {
        String v = MediaServerConfig.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.1.0-SNAPSHOT";
    }

    public String serverId() {
        return "media-server";
    }
}
