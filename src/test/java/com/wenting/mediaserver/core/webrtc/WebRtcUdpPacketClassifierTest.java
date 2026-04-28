package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcUdpPacketClassifierTest {

    @Test
    void shouldClassifyStunDtlsRtpRtcp() {
        assertEquals(
                WebRtcUdpPacketClassifier.PacketType.STUN,
                WebRtcUdpPacketClassifier.classify(Unpooled.wrappedBuffer(stunBindingRequest())));
        assertEquals(
                WebRtcUdpPacketClassifier.PacketType.DTLS,
                WebRtcUdpPacketClassifier.classify(Unpooled.wrappedBuffer(dtlsClientHello())));
        assertEquals(
                WebRtcUdpPacketClassifier.PacketType.RTP,
                WebRtcUdpPacketClassifier.classify(Unpooled.wrappedBuffer(new byte[]{
                        (byte) 0x80, (byte) 96, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1
                })));
        assertEquals(
                WebRtcUdpPacketClassifier.PacketType.RTCP,
                WebRtcUdpPacketClassifier.classify(Unpooled.wrappedBuffer(new byte[]{
                        (byte) 0x80, (byte) 200, 0, 6, 0, 0, 0, 1
                })));
    }

    @Test
    void shouldDetectDtlsClientHello() {
        assertTrue(WebRtcUdpPacketClassifier.isDtlsClientHello(Unpooled.wrappedBuffer(dtlsClientHello())));
    }

    private static byte[] stunBindingRequest() {
        return new byte[]{
                0x00, 0x01, 0x00, 0x00,
                0x21, 0x12, (byte) 0xA4, 0x42,
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
                0x77, (byte) 0x88, 0x10, 0x20, 0x30, 0x40
        };
    }

    private static byte[] dtlsClientHello() {
        return new byte[]{
                22, (byte) 0xFE, (byte) 0xFD, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x14,
                0x01, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00
        };
    }
}
