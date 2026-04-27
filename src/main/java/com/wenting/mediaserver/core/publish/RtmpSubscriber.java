package com.wenting.mediaserver.core.publish;

import io.netty.channel.ChannelHandlerContext;

public class RtmpSubscriber {
    final ChannelHandlerContext ctx;
    final int messageStreamId;
    volatile boolean waitingVideoKeyFrame = true;
    volatile boolean hasVideoSequenceHeader;
    RtmpSubscriber(ChannelHandlerContext ctx, int messageStreamId) {
        this.ctx = ctx;
        this.messageStreamId = messageStreamId;
    }
    @Override
    public boolean equals(Object o) {
        return o instanceof RtmpSubscriber && ((RtmpSubscriber) o).ctx == ctx;
    }
    @Override
    public int hashCode() {
            return System.identityHashCode(ctx);
        }
}