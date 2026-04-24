package com.wenting.mediaserver.core.model;

import java.util.Objects;

/**
 * Logical stream identity: application name + stream name (similar to ZLM vhost/app/stream concepts).
 */
public final class StreamKey {

    private final StreamProtocol protocol;
    private final String app;
    private final String stream;

    public StreamKey(StreamProtocol protocol, String app, String stream) {
        this.protocol = protocol == null ? StreamProtocol.UNKNOWN : protocol;
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(stream, "stream");
        if (app.trim().isEmpty() || stream.trim().isEmpty()) {
            throw new IllegalArgumentException("app and stream must be non-blank");
        }
        this.app = app;
        this.stream = stream;
    }

    public StreamKey(String app, String stream) {
        this(StreamProtocol.UNKNOWN, app, stream);
    }

    public StreamProtocol protocol() {
        return protocol;
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
        return protocol == streamKey.protocol
                && app.equals(streamKey.app)
                && stream.equals(streamKey.stream);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, app, stream);
    }

    @Override
    public String toString() {
        return protocol.name().toLowerCase() + ":" + path();
    }
}
