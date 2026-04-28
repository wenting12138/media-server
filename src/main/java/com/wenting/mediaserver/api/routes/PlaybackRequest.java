package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.core.model.StreamProtocol;

public final class PlaybackRequest {
    public final String app;
    public final String stream;
    public final StreamProtocol protocol;

    public PlaybackRequest(String app, String stream, StreamProtocol protocol) {
        this.app = app;
        this.stream = stream;
        this.protocol = protocol;
    }
}
