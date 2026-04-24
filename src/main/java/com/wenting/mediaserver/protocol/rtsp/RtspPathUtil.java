package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Extracts {@link StreamKey} from RTSP URIs ({@code /app/stream} or {@code /app/stream/trackid=0}).
 */
public final class RtspPathUtil {

    private RtspPathUtil() {
    }

    public static StreamKey streamKeyFromRtspUri(String rtspUri) {
        URI u = URI.create(rtspUri.trim());
        String path = u.getPath();
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        String[] raw = p.split("/");
        List<String> segs = new ArrayList<String>();
        for (String s : raw) {
            if (!s.isEmpty()) {
                segs.add(s);
            }
        }
        if (segs.size() < 2) {
            throw new IllegalArgumentException("path must contain app/stream, was: " + path);
        }
        return new StreamKey(StreamProtocol.RTSP, segs.get(0), segs.get(1));
    }

    /**
     * Optional track id from the last path segment, e.g. {@code .../streamid=0} or {@code .../trackID=1} (Lavf/ffmpeg).
     */
    public static Optional<Integer> streamTrackIdFromRtspUri(String rtspUri) {
        URI u = URI.create(rtspUri.trim());
        String path = u.getPath();
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        String[] raw = p.split("/");
        List<String> segs = new ArrayList<String>();
        for (String s : raw) {
            if (!s.isEmpty()) {
                segs.add(s);
            }
        }
        if (segs.isEmpty()) {
            return Optional.empty();
        }
        String last = segs.get(segs.size() - 1);
        String low = last.toLowerCase(Locale.ROOT);
        if (low.startsWith("streamid=")) {
            return parseTrailingInt(last, "streamid=".length());
        }
        if (low.startsWith("trackid=")) {
            return parseTrailingInt(last, "trackid=".length());
        }
        return Optional.empty();
    }

    private static Optional<Integer> parseTrailingInt(String segment, int valueStart) {
        if (valueStart > segment.length()) {
            return Optional.empty();
        }
        String num = segment.substring(valueStart).trim();
        try {
            return Optional.of(Integer.parseInt(num));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
