package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;
import com.wenting.mediaserver.protocol.rtmp.RtmpMediaPacket;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void frameProcessorLifecycleAndPacketsAreForwarded() {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();
        final AtomicInteger packets = new AtomicInteger();
        StreamFrameProcessor processor = new StreamFrameProcessor() {
            @Override
            public void onPublishStart(StreamKey key, String sdpText) {
                started.incrementAndGet();
            }

            @Override
            public void onRtmpPacket(StreamKey key, RtmpMediaPacket packet) {
                packets.incrementAndGet();
            }

            @Override
            public void onPublishStop(StreamKey key) {
                stopped.incrementAndGet();
            }
        };

        StreamRegistry r = new StreamRegistry(processor);
        StreamKey key = new StreamKey("live", "cam3");
        EmbeddedChannel ch = new EmbeddedChannel();
        PublishedStream ps = r.tryPublish(key, null, "v=0\r\n", ch).get();
        assertEquals(1, started.get());

        io.netty.buffer.ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{0x17, 0x00, 0x00, 0x00, 0x00});
        try {
            ps.onPublisherRtmpVideo(payload, 0, 1);
        } finally {
            payload.release();
        }
        assertEquals(1, packets.get());

        r.unpublish(key, ps.publisherSession().id());
        assertEquals(1, stopped.get());
    }

    @Test
    void playbackPrefersDerivedWatermarkStream() {
        StreamRegistry r = new StreamRegistry();
        EmbeddedChannel ch = new EmbeddedChannel();
        StreamKey original = new StreamKey("live", "cam4");
        StreamKey derived = new StreamKey("live", "cam4__wm");

        PublishedStream originalPs = r.tryPublish(original, null, "v=0\r\n", ch).get();
        PublishedStream derivedPs = r.tryPublish(derived, null, "v=0\r\n", ch).get();

        PublishedStream resolved = r.publishedForPlayback(original).orElse(null);
        assertTrue(resolved != null);
        assertEquals(derivedPs.key(), resolved.key());

        r.unpublish(original, originalPs.publisherSession().id());
        r.unpublish(derived, derivedPs.publisherSession().id());
    }
}
