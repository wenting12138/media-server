package com.wenting.mediaserver.core.model;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents one publisher or subscriber session bound to a {@link StreamKey}.
 */
public final class MediaSession {

    public enum Role {
        PUBLISHER,
        SUBSCRIBER
    }

    private final String id = UUID.randomUUID().toString();
    private final StreamKey streamKey;
    private final Role role;
    private final Instant createdAt = Instant.now();
    private final AtomicReference<Instant> lastActiveAt = new AtomicReference<>(createdAt);

    public MediaSession(StreamKey streamKey, Role role) {
        this.streamKey = streamKey;
        this.role = role;
    }

    public String id() {
        return id;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public Role role() {
        return role;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActiveAt() {
        return lastActiveAt.get();
    }

    public void touch() {
        lastActiveAt.set(Instant.now());
    }
}
