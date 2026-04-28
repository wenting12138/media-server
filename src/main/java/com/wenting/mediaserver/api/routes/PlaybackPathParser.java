package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.core.model.StreamProtocol;

public final class PlaybackPathParser {

    private PlaybackPathParser() {
    }

    public static PlaybackRequest parseHttpFlvRequest(String path) {
        if (path == null || !path.startsWith("/live/") || !path.endsWith(".flv")) {
            return null;
        }
        String rel = path.substring("/live/".length(), path.length() - ".flv".length());
        if (rel.isEmpty()) {
            return null;
        }
        return parseCommon(rel);
    }

    public static PlaybackRequest parseWhepPlayRequest(String path) {
        if (path == null || !path.startsWith("/whep/")) {
            return null;
        }
        if (path.startsWith("/whep/session/")) {
            return null;
        }
        String rel = path.substring("/whep/".length());
        if (rel.isEmpty()) {
            return null;
        }
        return parseCommon(rel);
    }

    public static String parseWhepSessionId(String path) {
        if (path == null || !path.startsWith("/whep/session/")) {
            return null;
        }
        String id = path.substring("/whep/session/".length()).trim();
        return id.isEmpty() ? null : id;
    }

    private static PlaybackRequest parseCommon(String rel) {
        String[] parts = rel.split("/");
        StreamProtocol forceProtocol = null;
        String app;
        String stream;
        if (parts.length == 1) {
            app = "live";
            stream = parts[0];
        } else if (parts.length == 2) {
            StreamProtocol maybeProtocol = parseProtocol(parts[0]);
            if (maybeProtocol != null) {
                forceProtocol = maybeProtocol;
                app = "live";
                stream = parts[1];
            } else {
                app = parts[0];
                stream = parts[1];
            }
        } else if (parts.length == 3) {
            StreamProtocol maybeProtocol = parseProtocol(parts[0]);
            if (maybeProtocol == null) {
                return null;
            }
            forceProtocol = maybeProtocol;
            app = parts[1];
            stream = parts[2];
        } else {
            return null;
        }
        if (app.trim().isEmpty() || stream.trim().isEmpty()) {
            return null;
        }
        return new PlaybackRequest(app, stream, forceProtocol);
    }

    public static StreamProtocol parseProtocol(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        if ("rtsp".equals(v)) {
            return StreamProtocol.RTSP;
        }
        if ("rtmp".equals(v)) {
            return StreamProtocol.RTMP;
        }
        if ("unknown".equals(v)) {
            return StreamProtocol.UNKNOWN;
        }
        return null;
    }
}
