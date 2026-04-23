package com.wenting.mediaserver.protocol.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

/**
 * Normalized RTMP media/data packet used by internal processing pipelines.
 */
public final class RtmpMediaPacket {

    public enum Kind {
        VIDEO,
        AUDIO,
        DATA
    }

    private final Kind kind;
    private final int typeId;
    private final int timestamp;
    private final int messageStreamId;
    private final ByteBuf payload;

    public RtmpMediaPacket(Kind kind, int typeId, int timestamp, int messageStreamId, ByteBuf payload) {
        this.kind = kind;
        this.typeId = typeId;
        this.timestamp = timestamp;
        this.messageStreamId = messageStreamId;
        this.payload = payload;
    }

    public Kind kind() {
        return kind;
    }

    public int typeId() {
        return typeId;
    }

    public int timestamp() {
        return timestamp;
    }

    public int messageStreamId() {
        return messageStreamId;
    }

    public ByteBuf payload() {
        return payload;
    }

    public void release() {
        ReferenceCountUtil.safeRelease(payload);
    }
}
