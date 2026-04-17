package com.wenting.mediaserver;

import com.wenting.mediaserver.bootstrap.MediaServerBootstrap;
import com.wenting.mediaserver.config.MediaServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Starts HTTP admin API and protocol listeners defined in {@link MediaServerConfig}.
 */
public final class MediaServerApplication {

    private static final Logger log = LoggerFactory.getLogger(MediaServerApplication.class);

    public static void main(String[] args) throws Exception {
        MediaServerConfig config = MediaServerConfig.fromEnvironment();
        log.info("Starting media-server (http={}, rtsp={})", config.httpPort(), config.rtspPort());
        try (MediaServerBootstrap bootstrap = new MediaServerBootstrap(config)) {
            bootstrap.start().sync();
        }
    }

    private MediaServerApplication() {
    }
}
