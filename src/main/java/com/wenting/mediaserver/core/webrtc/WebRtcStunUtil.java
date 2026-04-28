package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.zip.CRC32;

final class WebRtcStunUtil {

    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int ATTR_USERNAME = 0x0006;
    private static final int ATTR_MESSAGE_INTEGRITY = 0x0008;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final int ATTR_FINGERPRINT = 0x8028;
    private static final int BINDING_SUCCESS = 0x0101;
    private static final int FINGERPRINT_XOR = 0x5354554E;

    private WebRtcStunUtil() {
    }

    static String username(ByteBuf stun) {
        if (stun == null || stun.readableBytes() < 20) {
            return null;
        }
        int ri = stun.readerIndex();
        int msgLen = stun.getUnsignedShort(ri + 2);
        int end = Math.min(ri + 20 + msgLen, ri + stun.readableBytes());
        int off = ri + 20;
        while (off + 4 <= end) {
            int type = stun.getUnsignedShort(off);
            int len = stun.getUnsignedShort(off + 2);
            off += 4;
            if (len < 0 || off + len > end) {
                return null;
            }
            if (type == ATTR_USERNAME) {
                return stun.toString(off, len, CharsetUtil.UTF_8);
            }
            off += paddedLength(len);
        }
        return null;
    }

    static String localUfragFromUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        String value = username.trim();
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return value;
        }
        return value.substring(0, colon).trim();
    }

    static ByteBuf bindingSuccessResponse(ByteBuf request, InetSocketAddress mappedAddress, String localIcePwd) {
        if (request == null || request.readableBytes() < 20 || mappedAddress == null || localIcePwd == null || localIcePwd.isEmpty()) {
            return null;
        }
        InetAddress addr = mappedAddress.getAddress();
        if (addr == null || addr.getAddress() == null || addr.getAddress().length != 4) {
            return null;
        }
        byte[] transaction = new byte[12];
        request.getBytes(request.readerIndex() + 8, transaction);

        ByteBuf body = Unpooled.buffer();
        body.writeShort(ATTR_XOR_MAPPED_ADDRESS);
        body.writeShort(8);
        body.writeByte(0);
        body.writeByte(0x01);
        body.writeShort(mappedAddress.getPort() ^ (MAGIC_COOKIE >>> 16));
        byte[] ip = addr.getAddress();
        body.writeByte((ip[0] & 0xFF) ^ 0x21);
        body.writeByte((ip[1] & 0xFF) ^ 0x12);
        body.writeByte((ip[2] & 0xFF) ^ 0xA4);
        body.writeByte((ip[3] & 0xFF) ^ 0x42);

        int lenThroughMi = body.readableBytes() + 24;
        ByteBuf responseForMi = Unpooled.buffer(20 + lenThroughMi);
        writeHeader(responseForMi, lenThroughMi, transaction);
        responseForMi.writeBytes(body, body.readerIndex(), body.readableBytes());
        byte[] mi = hmacSha1(responseForMi, localIcePwd);
        responseForMi.writeShort(ATTR_MESSAGE_INTEGRITY);
        responseForMi.writeShort(20);
        responseForMi.writeBytes(mi);

        int finalLen = responseForMi.readableBytes() - 20 + 8;
        responseForMi.setShort(2, finalLen);
        long crc = crc32(responseForMi) ^ FINGERPRINT_XOR;
        responseForMi.writeShort(ATTR_FINGERPRINT);
        responseForMi.writeShort(4);
        responseForMi.writeInt((int) crc);
        body.release();
        return responseForMi;
    }

    private static void writeHeader(ByteBuf out, int bodyLength, byte[] transaction) {
        out.writeShort(BINDING_SUCCESS);
        out.writeShort(bodyLength);
        out.writeInt(MAGIC_COOKIE);
        out.writeBytes(transaction);
    }

    private static byte[] hmacSha1(ByteBuf message, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(CharsetUtil.UTF_8), "HmacSHA1"));
            byte[] bytes = new byte[message.readableBytes()];
            message.getBytes(message.readerIndex(), bytes);
            return mac.doFinal(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("failed to calculate stun message-integrity", e);
        }
    }

    private static long crc32(ByteBuf message) {
        CRC32 crc = new CRC32();
        byte[] bytes = new byte[message.readableBytes()];
        message.getBytes(message.readerIndex(), bytes);
        crc.update(bytes);
        return crc.getValue();
    }

    private static int paddedLength(int len) {
        return (len + 3) & ~3;
    }
}
