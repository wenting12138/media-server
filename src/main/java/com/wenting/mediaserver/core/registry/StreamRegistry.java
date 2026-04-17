package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory published streams index. One live publisher per {@link StreamKey}.
 */
public final class StreamRegistry {

    private final Map<StreamKey, PublishedStream> published = new ConcurrentHashMap<StreamKey, PublishedStream>();

    /**
     * @return empty if another publisher already holds the key
     */
    public Optional<PublishedStream> tryPublish(StreamKey key, String sessionId, String sdp, io.netty.channel.Channel publisherChannel) {
        MediaSession session = new MediaSession(key, sessionId, MediaSession.Role.PUBLISHER);
        PublishedStream stream = new PublishedStream(key, session);
        stream.setSdp(sdp);
        stream.setPublisherChannel(publisherChannel);
        PublishedStream prev = published.putIfAbsent(key, stream);
        if (prev != null) {
            return Optional.empty();
        }
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
