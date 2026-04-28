package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;

/**
 * Classifies UDP ingress packets received on WebRTC transport socket.
 */
public final class WebRtcUdpPacketClassifier {

    private static final int STUN_MAGIC_COOKIE = 0x2112A442;

    public enum PacketType {
        STUN,
        DTLS,
        RTCP,
        RTP,
        UNKNOWN
    }

    private WebRtcUdpPacketClassifier() {
    }

    public static PacketType classify(ByteBuf payload) {
        if (payload == null || !payload.isReadable()) {
            return PacketType.UNKNOWN;
        }
        if (isStun(payload)) {
            return PacketType.STUN;
        }
        if (isDtls(payload)) {
            return PacketType.DTLS;
        }
        if (isRtpOrRtcp(payload)) {
            int b1 = payload.getUnsignedByte(payload.readerIndex() + 1);
            if (b1 >= 192 && b1 <= 223) {
                return PacketType.RTCP;
            }
            return PacketType.RTP;
        }
        return PacketType.UNKNOWN;
    }

    public static boolean isDtlsClientHello(ByteBuf payload) {
        if (!isDtls(payload) || payload == null || payload.readableBytes() < 14) {
            return false;
        }
        int ri = payload.readerIndex();
        int contentType = payload.getUnsignedByte(ri);
        if (contentType != 22) {
            return false;
        }
        int handshakeType = payload.getUnsignedByte(ri + 13);
        return handshakeType == 1;
    }

    private static boolean isStun(ByteBuf payload) {
        if (payload == null || payload.readableBytes() < 20) {
            return false;
        }
        int ri = payload.readerIndex();
        int first = payload.getUnsignedByte(ri);
        if ((first & 0xC0) != 0) {
            return false;
        }
        return payload.getInt(ri + 4) == STUN_MAGIC_COOKIE;
    }

    private static boolean isDtls(ByteBuf payload) {
        if (payload == null || !payload.isReadable()) {
            return false;
        }
        int b0 = payload.getUnsignedByte(payload.readerIndex());
        return b0 >= 20 && b0 <= 63;
    }

    private static boolean isRtpOrRtcp(ByteBuf payload) {
        if (payload == null || payload.readableBytes() < 2) {
            return false;
        }
        int b0 = payload.getUnsignedByte(payload.readerIndex());
        return b0 >= 128 && b0 <= 191;
    }
}
