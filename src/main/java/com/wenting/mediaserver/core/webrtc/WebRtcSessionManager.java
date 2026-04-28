package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory signaling sessions for WHEP/WebRTC playback.
 */
public final class WebRtcSessionManager {

    private static final long DEFAULT_SESSION_TTL_MS = 120_000L;

    private final long sessionTtlMs;
    private final Map<String, WebRtcSession> sessions = new ConcurrentHashMap<String, WebRtcSession>();

    public WebRtcSessionManager() {
        this(DEFAULT_SESSION_TTL_MS);
    }

    public WebRtcSessionManager(long sessionTtlMs) {
        this.sessionTtlMs = sessionTtlMs <= 0 ? DEFAULT_SESSION_TTL_MS : sessionTtlMs;
    }

    public WebRtcSession createPlaybackSession(StreamKey streamKey, String offerSdp, String remoteAddress) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        WebRtcSession session = new WebRtcSession(id, streamKey, offerSdp, remoteAddress, now);
        sessions.put(id, session);
        return session;
    }

    public WebRtcSession get(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return sessions.get(id.trim());
    }

    public WebRtcSession remove(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return sessions.remove(id.trim());
    }

    public int sessionCount() {
        return sessions.size();
    }

    public List<WebRtcSession> listSessions() {
        return new ArrayList<WebRtcSession>(sessions.values());
    }

    public int cleanupExpired(long nowMs) {
        List<String> expired = new ArrayList<String>();
        for (Map.Entry<String, WebRtcSession> entry : sessions.entrySet()) {
            WebRtcSession session = entry.getValue();
            if (session == null) {
                expired.add(entry.getKey());
                continue;
            }
            long idle = nowMs - session.lastSeenAtMs();
            if (idle > sessionTtlMs) {
                expired.add(entry.getKey());
            }
        }
        for (String id : expired) {
            sessions.remove(id);
        }
        return expired.size();
    }
}
