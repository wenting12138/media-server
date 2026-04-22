package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import io.netty.util.AttributeKey;

/**
 * RTMP session state machine.
 *
 * States:
 *   HANDSHAKE → CONNECTED → STREAM_CREATED → PUBLISHING
 *                                      └ → PLAYING
 *
 * Transitions:
 *   - handshakeComplete : HANDSHAKE → CONNECTED
 *   - connect           : CONNECTED → CONNECTED (idempotent, updates app)
 *   - createStream      : CONNECTED → STREAM_CREATED
 *   - publish           : STREAM_CREATED → PUBLISHING
 *   - play              : STREAM_CREATED → PLAYING
 *   - closeStream       : PUBLISHING|PLAYING → STREAM_CREATED
 *   - close             : any → CLOSED
 */
public final class RtmpSession {

    public enum State {
        HANDSHAKE,
        CONNECTED,
        STREAM_CREATED,
        PUBLISHING,
        PLAYING,
        CLOSED
    }

    static final AttributeKey<RtmpSession> SESSION_KEY =
            AttributeKey.valueOf("RTMP_SESSION");

    private volatile State state = State.HANDSHAKE;

    private String app = "live";
    private StreamKey publishingKey;
    private String publisherSessionId;
    private PublishedStream publishingStream;
    private PublishedStream playingStream;
    private int playingMessageStreamId = 1;

    public State state() {
        return state;
    }

    public boolean isClosed() {
        return state == State.CLOSED;
    }

    /** HANDSHAKE → CONNECTED */
    public boolean handshakeComplete() {
        if (state != State.HANDSHAKE) {
            return false;
        }
        state = State.CONNECTED;
        return true;
    }

    /** CONNECTED → CONNECTED (updates app) */
    public boolean connect(String app) {
        if (state != State.CONNECTED) {
            return false;
        }
        if (app != null && !app.isEmpty()) {
            this.app = app;
        }
        return true;
    }

    /** CONNECTED → STREAM_CREATED */
    public boolean createStream() {
        if (state != State.CONNECTED) {
            return false;
        }
        state = State.STREAM_CREATED;
        return true;
    }

    /** STREAM_CREATED → PUBLISHING */
    public boolean publish(StreamKey key, String sessionId, PublishedStream stream) {
        if (state != State.STREAM_CREATED) {
            return false;
        }
        state = State.PUBLISHING;
        this.publishingKey = key;
        this.publisherSessionId = sessionId;
        this.publishingStream = stream;
        return true;
    }

    /** STREAM_CREATED → PLAYING */
    public boolean play(PublishedStream stream, int messageStreamId) {
        if (state != State.STREAM_CREATED) {
            return false;
        }
        state = State.PLAYING;
        this.playingStream = stream;
        this.playingMessageStreamId = messageStreamId;
        return true;
    }

    /** PUBLISHING|PLAYING → STREAM_CREATED */
    public boolean closeStream() {
        if (state == State.PUBLISHING) {
            publishingKey = null;
            publisherSessionId = null;
            publishingStream = null;
            state = State.STREAM_CREATED;
            return true;
        }
        if (state == State.PLAYING) {
            playingStream = null;
            state = State.STREAM_CREATED;
            return true;
        }
        return false;
    }

    /** any → CLOSED */
    public boolean close() {
        if (state == State.CLOSED) {
            return false;
        }
        state = State.CLOSED;
        publishingKey = null;
        publisherSessionId = null;
        publishingStream = null;
        playingStream = null;
        playingMessageStreamId = 1;
        return true;
    }

    public String app() {
        return app;
    }

    public StreamKey publishingKey() {
        return publishingKey;
    }

    public String publisherSessionId() {
        return publisherSessionId;
    }

    public PublishedStream publishingStream() {
        return publishingStream;
    }

    public PublishedStream playingStream() {
        return playingStream;
    }

    public int playingMessageStreamId() {
        return playingMessageStreamId;
    }
}
