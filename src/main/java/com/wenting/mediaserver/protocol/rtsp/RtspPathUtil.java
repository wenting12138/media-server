package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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
        return new StreamKey(segs.get(0), segs.get(1));
    }
}
