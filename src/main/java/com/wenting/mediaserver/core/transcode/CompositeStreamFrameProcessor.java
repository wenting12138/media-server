package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Fan-out stream frame callbacks to multiple processors.
 */
public final class CompositeStreamFrameProcessor implements StreamFrameProcessor, AutoCloseable {

    private final List<StreamFrameProcessor> processors;

    public CompositeStreamFrameProcessor(List<StreamFrameProcessor> processors) {
        this.processors = new ArrayList<StreamFrameProcessor>();
        if (processors != null) {
            for (StreamFrameProcessor processor : processors) {
                if (processor != null) {
                    this.processors.add(processor);
                }
            }
        }
    }

    @Override
    public void onPublishStart(StreamKey key, String sdpText) {
        for (StreamFrameProcessor processor : processors) {
            processor.onPublishStart(key, sdpText);
        }
    }

    @Override
    public void onPacket(StreamKey key, EncodedMediaPacket packet) {
        for (StreamFrameProcessor processor : processors) {
            processor.onPacket(key, packet);
        }
    }

    @Override
    public void onPublishStop(StreamKey key) {
        for (StreamFrameProcessor processor : processors) {
            processor.onPublishStop(key);
        }
    }

    @Override
    public void close() {
        for (StreamFrameProcessor processor : processors) {
            if (processor instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) processor).close();
                } catch (Exception ignore) {
                    // ignore close failure to continue other processors.
                }
            }
        }
    }
}
