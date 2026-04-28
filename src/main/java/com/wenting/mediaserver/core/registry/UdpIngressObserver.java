package com.wenting.mediaserver.core.registry;

import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;

public interface UdpIngressObserver {
    void onDatagram(InetSocketAddress sender, InetSocketAddress recipient, ByteBuf payload);
}