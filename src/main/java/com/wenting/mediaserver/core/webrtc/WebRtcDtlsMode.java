package com.wenting.mediaserver.core.webrtc;

import java.util.Locale;

public enum WebRtcDtlsMode {
    OFF,
    PSEUDO,
    REAL,
    STRICT;

    public static WebRtcDtlsMode parse(String raw, WebRtcDtlsMode fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback == null ? PSEUDO : fallback;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(v)) {
            return OFF;
        }
        if ("pseudo".equals(v)) {
            return PSEUDO;
        }
        if ("real".equals(v)) {
            return REAL;
        }
        if ("strict".equals(v)) {
            return STRICT;
        }
        return fallback == null ? PSEUDO : fallback;
    }
}
