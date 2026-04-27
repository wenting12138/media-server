package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.config.MediaServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamTranscoderFactoryTest {

    @Test
    void createHybridTranscoder() {
        MediaServerConfig config = new MediaServerConfig(
                18080,
                1554,
                11935,
                20000,
                30000,
                true,
                "ffmpeg",
                "__wm",
                "127.0.0.1",
                1024,
                "hybrid",
                false);
        StreamTranscoder transcoder = StreamTranscoderFactory.create(config);
        assertTrue(transcoder instanceof HybridStreamTranscoder);
        transcoder.close();
    }
}
