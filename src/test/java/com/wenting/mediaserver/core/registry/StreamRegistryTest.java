package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamRegistryTest {

    @Test
    void tryPublishSucceedsOncePerKey() {
        StreamRegistry r = new StreamRegistry();
        StreamKey key = new StreamKey("live", "cam1");
        EmbeddedChannel ch = new EmbeddedChannel();
        assertTrue(r.tryPublish(key, null, "v=0\r\n", ch).isPresent());
        assertFalse(r.tryPublish(key, null, "v=0\r\n", ch).isPresent());
        assertEquals(1, r.publisherCount());
    }

    @Test
    void unpublishRemovesKey() {
        StreamRegistry r = new StreamRegistry();
        StreamKey key = new StreamKey("live", "cam2");
        EmbeddedChannel ch = new EmbeddedChannel();
        PublishedStream ps = r.tryPublish(key, null, "v=0\r\n", ch).get();
        r.unpublish(key, ps.publisherSession().id());
        assertFalse(r.published(key).isPresent());
    }
}
