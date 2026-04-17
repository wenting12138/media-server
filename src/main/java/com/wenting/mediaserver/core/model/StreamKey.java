package com.wenting.mediaserver.core.model;

import java.util.Objects;

/**
 * Logical stream identity: application name + stream name (similar to ZLM vhost/app/stream concepts).
 */
public final class StreamKey {

    private final String app;
    private final String stream;

    public StreamKey(String app, String stream) {
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(stream, "stream");
        if (app.trim().isEmpty() || stream.trim().isEmpty()) {
            throw new IllegalArgumentException("app and stream must be non-blank");
        }
        this.app = app;
        this.stream = stream;
    }

    public String app() {
        return app;
    }

    public String stream() {
        return stream;
    }

    public String path() {
        return app + "/" + stream;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        StreamKey streamKey = (StreamKey) o;
        return app.equals(streamKey.app) && stream.equals(streamKey.stream);
    }

    @Override
    public int hashCode() {
        return Objects.hash(app, stream);
    }

    @Override
    public String toString() {
        return path();
    }
}
