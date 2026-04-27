package com.wenting.mediaserver.core.publish;

import io.netty.channel.ChannelHandlerContext;

public class HttpFlvSubscriber {
    final ChannelHandlerContext ctx;
    volatile boolean waitingVideoKeyFrame = true;
    volatile boolean hasVideoSequenceHeader;
    HttpFlvSubscriber(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }
    @Override
    public boolean equals(Object o) {
        return o instanceof HttpFlvSubscriber && ((HttpFlvSubscriber) o).ctx == ctx;
    }
    @Override
    public int hashCode() {
        return System.identityHashCode(ctx);
    }
}
