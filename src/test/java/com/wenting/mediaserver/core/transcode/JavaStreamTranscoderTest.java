package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaStreamTranscoderTest {

    @Test
    void javaTranscoderPublishesDerivedStreamAndRelaysRtp() {
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
                "java",
                false);
        StreamTranscodeDispatcher dispatcher = new StreamTranscodeDispatcher(StreamTranscoderFactory.create(config));
        StreamRegistry registry = new StreamRegistry(dispatcher, config.transcodeOutputSuffix());
        dispatcher.bindRegistry(registry);

        StreamKey source = new StreamKey("live", "cam_java");
        StreamKey derived = new StreamKey("live", "cam_java__wm");

        EmbeddedChannel publisher = new EmbeddedChannel();
        PublishedStream sourceStream = registry.tryPublish(source, "src-sid", "v=0\r\n", publisher).orElse(null);
        assertNotNull(sourceStream);
        PublishedStream derivedStream = registry.published(derived).orElse(null);
        assertNotNull(derivedStream);
        assertEquals(derived, registry.publishedForPlayback(source).get().key());

        EmbeddedChannel rtspSubscriber = new EmbeddedChannel();
        derivedStream.addSubscriber(rtspSubscriber);

        ByteBuf rtp = Unpooled.wrappedBuffer(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x65, 0x01, 0x02, 0x03
        });
        try {
            sourceStream.onPublisherVideoRtp(rtp);
        } finally {
            rtp.release();
        }

        Object outbound = rtspSubscriber.readOutbound();
        assertNotNull(outbound);
        assertTrue(outbound instanceof ByteBuf);
        ByteBuf framed = (ByteBuf) outbound;
        try {
            assertTrue(framed.readableBytes() > 16);
        } finally {
            framed.release();
        }

        registry.unpublish(source, sourceStream.publisherSession().id());
        assertFalse(registry.published(source).isPresent());
        assertFalse(registry.published(derived).isPresent());
    }
}
