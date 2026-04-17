package com.wenting.mediaserver.protocol.rtmp;

import io.netty.buffer.ByteBuf;

final class RtmpMessage {
    private final int typeId;
    private final int timestamp;
    private final int messageStreamId;
    private final ByteBuf payload;

    RtmpMessage(int typeId, int timestamp, int messageStreamId, ByteBuf payload) {
        this.typeId = typeId;
        this.timestamp = timestamp;
        this.messageStreamId = messageStreamId;
        this.payload = payload;
    }

    int typeId() {
        return typeId;
    }

    int timestamp() {
        return timestamp;
    }

    int messageStreamId() {
        return messageStreamId;
    }

    ByteBuf payload() {
        return payload;
    }
}
