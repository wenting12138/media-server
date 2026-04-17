package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.StreamKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StreamRegistryTest {

    @Test
    void registerPublisherIsIdempotentPerKey() {
        StreamRegistry r = new StreamRegistry();
        StreamKey key = new StreamKey("live", "cam1");
        MediaSession a = r.registerPublisher(key);
        MediaSession b = r.registerPublisher(key);
        assertEquals(a.id(), b.id());
        assertEquals(1, r.publisherCount());
    }

    @Test
    void unregisterPublisherRemovesKey() {
        StreamRegistry r = new StreamRegistry();
        StreamKey key = new StreamKey("live", "cam2");
        MediaSession s = r.registerPublisher(key);
        r.unregisterPublisher(key, s.id());
        assertFalse(r.publisherOf(key).isPresent());
    }
}
