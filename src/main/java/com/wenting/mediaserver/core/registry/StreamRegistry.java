package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory published streams index. One live publisher per {@link StreamKey}.
 */
public final class StreamRegistry {

    private final StreamFrameProcessor frameProcessor;
    private final String playbackSuffix;
    private final Map<StreamKey, PublishedStream> published = new ConcurrentHashMap<StreamKey, PublishedStream>();

    public StreamRegistry() {
        this(StreamFrameProcessor.NOOP, "__wm");
    }

    public StreamRegistry(StreamFrameProcessor frameProcessor) {
        this(frameProcessor, "__wm");
    }

    public StreamRegistry(StreamFrameProcessor frameProcessor, String playbackSuffix) {
        this.frameProcessor = frameProcessor == null ? StreamFrameProcessor.NOOP : frameProcessor;
        this.playbackSuffix = playbackSuffix == null ? "__wm" : playbackSuffix;
    }

    /**
     * @return empty if another publisher already holds the key
     */
    public Optional<PublishedStream> tryPublish(StreamKey key, String sessionId, String sdp, io.netty.channel.Channel publisherChannel) {
        MediaSession session = new MediaSession(key, sessionId, MediaSession.Role.PUBLISHER);
        PublishedStream stream = new PublishedStream(key, session, frameProcessor);
        stream.setSdp(sdp);
        stream.setPublisherChannel(publisherChannel);
        PublishedStream prev = published.putIfAbsent(key, stream);
        if (prev != null) {
            return Optional.empty();
        }
        stream.markPublishStarted();
        return Optional.of(stream);
    }

    public void unpublish(StreamKey key, String publisherSessionId) {
        published.compute(key, (k, current) -> {
            if (current == null) {
                return null;
            }
            if (current.publisherSession().id().equals(publisherSessionId)) {
                current.shutdown();
                return null;
            }
            return current;
        });
    }

    public Optional<PublishedStream> published(StreamKey key) {
        return Optional.ofNullable(published.get(key));
    }

    public Optional<PublishedStream> publishedForPlayback(StreamKey requested) {
        if (requested == null) {
            return Optional.empty();
        }
        if (playbackSuffix != null && !playbackSuffix.isEmpty() && !requested.stream().endsWith(playbackSuffix)) {
            StreamKey derived = new StreamKey(requested.app(), requested.stream() + playbackSuffix);
            PublishedStream preferred = published.get(derived);
            if (preferred != null) {
                return Optional.of(preferred);
            }
        }
        return Optional.ofNullable(published.get(requested));
    }

    public List<StreamKey> listPublishedKeys() {
        return new ArrayList<StreamKey>(published.keySet());
    }

    public int publisherCount() {
        return published.size();
    }

    public Optional<MediaSession> publisherOf(StreamKey key) {
        return published(key).map(PublishedStream::publisherSession);
    }
}
