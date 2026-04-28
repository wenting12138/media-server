package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;

public final class PlaybackStreamResolver {

    private final StreamRegistry registry;

    public PlaybackStreamResolver(StreamRegistry registry) {
        this.registry = registry;
    }

    public PublishedStream resolve(String app, String stream, StreamProtocol forceProtocol) {
        if (registry == null || app == null || stream == null) {
            return null;
        }
        if (forceProtocol != null) {
            StreamKey forced = new StreamKey(forceProtocol, app, stream);
            java.util.Optional<PublishedStream> found = registry.publishedForPlayback(forced);
            return found.orElse(null);
        }
        StreamProtocol[] order = new StreamProtocol[] {
                StreamProtocol.RTMP,
                StreamProtocol.RTSP,
                StreamProtocol.UNKNOWN
        };
        for (StreamProtocol protocol : order) {
            StreamKey key = new StreamKey(protocol, app, stream);
            java.util.Optional<PublishedStream> found = registry.publishedForPlayback(key);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }
}
