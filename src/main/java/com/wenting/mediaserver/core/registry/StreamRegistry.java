package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.StreamKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory index of active streams and sessions. Replace with clustered store when scaling out.
 */
public final class StreamRegistry {

    private final Map<StreamKey, MediaSession> publishers = new ConcurrentHashMap<>();
    private final Map<String, MediaSession> sessionsById = new ConcurrentHashMap<>();

    public Optional<MediaSession> publisherOf(StreamKey key) {
        return Optional.ofNullable(publishers.get(key));
    }

    public MediaSession registerPublisher(StreamKey key) {
        MediaSession session = new MediaSession(key, MediaSession.Role.PUBLISHER);
        MediaSession previous = publishers.putIfAbsent(key, session);
        if (previous != null) {
            return previous;
        }
        sessionsById.put(session.id(), session);
        return session;
    }

    public void unregisterPublisher(StreamKey key, String sessionId) {
        publishers.compute(key, (k, current) -> {
            if (current != null && current.id().equals(sessionId)) {
                sessionsById.remove(sessionId);
                return null;
            }
            return current;
        });
    }

    public List<StreamKey> listPublishedKeys() {
        return new ArrayList<>(publishers.keySet());
    }

    public int publisherCount() {
        return publishers.size();
    }
}
