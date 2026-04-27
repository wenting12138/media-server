package com.wenting.mediaserver.core.hls;

import com.wenting.mediaserver.protocol.rtp.RtpHeader;
import io.netty.buffer.ByteBuf;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

final class HlsCodecUtil {

    private HlsCodecUtil() {
    }

    static int readSigned24(int b0, int b1, int b2) {
        int value = ((b0 & 0xFF) << 16) | ((b1 & 0xFF) << 8) | (b2 & 0xFF);
        if ((value & 0x800000) != 0) {
            value |= 0xFF000000;
        }
        return value;
    }

    static void writeAnnexbNal(ByteArrayOutputStream out, byte[] nalu) {
        if (out == null || nalu == null || nalu.length == 0) {
            return;
        }
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(nalu, 0, nalu.length);
    }

    static HlsAacConfig parseAacConfigFromAsc(ByteBuf payload, int offset, int length) {
        if (payload == null || length < 2) {
            return null;
        }
        int end = payload.readerIndex() + payload.readableBytes();
        if (offset < payload.readerIndex() || offset + 2 > end) {
            return null;
        }
        int bits = ((payload.getUnsignedByte(offset) & 0xFF) << 8)
                | (payload.getUnsignedByte(offset + 1) & 0xFF);
        int audioObjectType = (bits >> 11) & 0x1F;
        int sampleRateIndex = (bits >> 7) & 0x0F;
        int channelConfig = (bits >> 3) & 0x0F;
        if (audioObjectType <= 0) {
            audioObjectType = 2;
        }
        if (sampleRateIndex < 0 || sampleRateIndex > 12) {
            sampleRateIndex = 3;
        }
        if (channelConfig <= 0) {
            channelConfig = 2;
        }
        int sampleRate = sampleRateFromIndex(sampleRateIndex);
        return new HlsAacConfig(
                -1,
                sampleRate,
                channelConfig,
                audioObjectType,
                sampleRateIndex,
                13,
                3,
                3);
    }

    static HlsSdpHints parseSdpHints(String sdpText) {
        if (sdpText == null || sdpText.trim().isEmpty()) {
            return null;
        }
        byte[][] sets = parseSpropParameterSets(sdpText);
        byte[] sps = sets == null ? null : sets[0];
        byte[] pps = sets == null ? null : sets[1];
        HlsAacConfig aac = parseAacConfigFromSdp(sdpText);
        if ((sps == null || sps.length == 0)
                && (pps == null || pps.length == 0)
                && aac == null) {
            return null;
        }
        return new HlsSdpHints(sps, pps, aac);
    }

    static HlsAacConfig parseAacConfigFromSdp(String sdpText) {
        if (sdpText == null || sdpText.trim().isEmpty()) {
            return null;
        }
        String[] lines = sdpText.split("\r\n|\n");
        int payloadType = -1;
        String rtpmap = null;
        String fmtp = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.toLowerCase(Locale.ROOT).startsWith("m=audio ")) {
                continue;
            }
            String[] parts = line.split(" ");
            if (parts.length >= 4) {
                payloadType = parseIntSafe(parts[3].trim(), -1);
            }
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j] == null ? "" : lines[j].trim();
                if (l.startsWith("m=")) {
                    break;
                }
                String lower = l.toLowerCase(Locale.ROOT);
                if (lower.startsWith("a=rtpmap:")) {
                    String rest = l.substring("a=rtpmap:".length());
                    int sp = rest.indexOf(' ');
                    if (sp > 0) {
                        int pt = parseIntSafe(rest.substring(0, sp).trim(), -1);
                        if (pt == payloadType) {
                            rtpmap = rest.substring(sp + 1).trim();
                        }
                    }
                } else if (lower.startsWith("a=fmtp:")) {
                    String rest = l.substring("a=fmtp:".length());
                    int sp = rest.indexOf(' ');
                    if (sp > 0) {
                        int pt = parseIntSafe(rest.substring(0, sp).trim(), -1);
                        if (pt == payloadType) {
                            fmtp = rest.substring(sp + 1).trim();
                        }
                    }
                }
            }
            break;
        }
        if (payloadType < 0 || rtpmap == null) {
            return null;
        }
        String upper = rtpmap.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("MPEG4-GENERIC/")) {
            return null;
        }
        String[] mapParts = rtpmap.split("/");
        int clockRate = mapParts.length >= 2 ? parseIntSafe(mapParts[1], 48000) : 48000;
        int channelConfig = mapParts.length >= 3 ? Math.max(1, parseIntSafe(mapParts[2], 2)) : 2;
        int sizeLength = parseFmtpInt(fmtp, "sizelength", 13);
        int indexLength = parseFmtpInt(fmtp, "indexlength", 3);
        int indexDeltaLength = parseFmtpInt(fmtp, "indexdeltalength", 3);
        byte[] asc = decodeHex(parseFmtpString(fmtp, "config"));

        int audioObjectType = 2;
        int sampleRateIndex = sampleRateIndex(clockRate);
        if (asc != null && asc.length >= 2) {
            int bits = ((asc[0] & 0xFF) << 8) | (asc[1] & 0xFF);
            audioObjectType = Math.max(1, (bits >> 11) & 0x1F);
            sampleRateIndex = (bits >> 7) & 0x0F;
            int ch = (bits >> 3) & 0x0F;
            if (ch > 0) {
                channelConfig = ch;
            }
        }
        return new HlsAacConfig(
                payloadType,
                clockRate,
                channelConfig,
                audioObjectType,
                sampleRateIndex,
                sizeLength,
                indexLength,
                indexDeltaLength);
    }

    static byte[][] parseSpropParameterSets(String sdpText) {
        if (sdpText == null || sdpText.isEmpty()) {
            return null;
        }
        String[] lines = sdpText.split("\r\n|\n");
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase();
            if (!lower.startsWith("a=fmtp:") || lower.indexOf("sprop-parameter-sets=") < 0) {
                continue;
            }
            int keyIndex = lower.indexOf("sprop-parameter-sets=");
            String value = line.substring(keyIndex + "sprop-parameter-sets=".length());
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) {
                value = value.substring(0, semicolon);
            }
            String[] parts = value.split(",");
            byte[] sps = decodeB64(parts, 0);
            byte[] pps = decodeB64(parts, 1);
            return new byte[][]{sps, pps};
        }
        return null;
    }

    static byte[] decodeB64(String[] parts, int idx) {
        if (parts == null || idx < 0 || idx >= parts.length) {
            return null;
        }
        String value = parts[idx] == null ? "" : parts[idx].trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static int parseFmtpInt(String fmtp, String key, int fallback) {
        String raw = parseFmtpString(fmtp, key);
        return parseIntSafe(raw, fallback);
    }

    static String parseFmtpString(String fmtp, String key) {
        if (fmtp == null || fmtp.trim().isEmpty() || key == null || key.trim().isEmpty()) {
            return null;
        }
        String[] parts = fmtp.split(";");
        String target = key.trim().toLowerCase(Locale.ROOT);
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (!target.equals(k)) {
                continue;
            }
            String v = token.substring(eq + 1).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    static int parseIntSafe(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static byte[] decodeHex(String value) {
        if (value == null) {
            return null;
        }
        String hex = value.trim();
        if (hex.isEmpty() || (hex.length() & 1) != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static int sampleRateIndex(int sampleRate) {
        final int[] rates = new int[]{
                96000, 88200, 64000, 48000, 44100, 32000,
                24000, 22050, 16000, 12000, 11025, 8000, 7350
        };
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == sampleRate) {
                return i;
            }
        }
        return 3;
    }

    static int sampleRateFromIndex(int idx) {
        final int[] rates = new int[]{
                96000, 88200, 64000, 48000, 44100, 32000,
                24000, 22050, 16000, 12000, 11025, 8000, 7350
        };
        if (idx < 0 || idx >= rates.length) {
            return 48000;
        }
        return rates[idx];
    }

    static int readRtpTimestamp(ByteBuf rtpPacket) {
        if (rtpPacket == null || rtpPacket.readableBytes() < 12) {
            return Integer.MIN_VALUE;
        }
        int ri = rtpPacket.readerIndex();
        if (ri + 8 > rtpPacket.writerIndex()) {
            return Integer.MIN_VALUE;
        }
        return rtpPacket.getInt(ri + 4);
    }

    static int readRtpPayloadType(ByteBuf rtpPacket) {
        if (rtpPacket == null || rtpPacket.readableBytes() < 2) {
            return -1;
        }
        int ri = rtpPacket.readerIndex();
        if (ri + 2 > rtpPacket.writerIndex()) {
            return -1;
        }
        return rtpPacket.getUnsignedByte(ri + 1) & 0x7F;
    }

    static List<byte[]> extractAacAccessUnits(ByteBuf rtpPacket, HlsAacConfig config) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (rtpPacket == null || config == null) {
            return out;
        }
        int hdrLen = RtpHeader.headerLength(rtpPacket);
        if (hdrLen < 0 || rtpPacket.readableBytes() <= hdrLen + 2) {
            return out;
        }
        int payloadStart = rtpPacket.readerIndex() + hdrLen;
        int payloadEnd = rtpPacket.readerIndex() + rtpPacket.readableBytes();
        int auHeaderBits = rtpPacket.getUnsignedShort(payloadStart);
        int auHeaderBytes = (auHeaderBits + 7) / 8;
        int headerStart = payloadStart + 2;
        int dataStart = headerStart + auHeaderBytes;
        if (dataStart > payloadEnd) {
            return out;
        }
        int bitPos = 0;
        int dataOff = dataStart;
        int index = 0;
        while (bitPos < auHeaderBits) {
            int indexBits = index == 0 ? config.indexLength() : config.indexDeltaLength();
            if (bitPos + config.sizeLength() + indexBits > auHeaderBits) {
                break;
            }
            int auSize = readBits(rtpPacket, headerStart, bitPos, config.sizeLength());
            bitPos += config.sizeLength();
            if (indexBits > 0) {
                bitPos += indexBits;
            }
            if (auSize <= 0 || dataOff + auSize > payloadEnd) {
                break;
            }
            byte[] frame = new byte[auSize];
            rtpPacket.getBytes(dataOff, frame);
            out.add(frame);
            dataOff += auSize;
            index++;
        }
        return out;
    }

    static int readBits(ByteBuf buf, int baseOffset, int bitOffset, int bitLength) {
        int value = 0;
        for (int i = 0; i < bitLength; i++) {
            int absoluteBit = bitOffset + i;
            int byteIndex = baseOffset + (absoluteBit >> 3);
            int bitIndex = 7 - (absoluteBit & 0x07);
            int bit = (buf.getUnsignedByte(byteIndex) >> bitIndex) & 0x01;
            value = (value << 1) | bit;
        }
        return value;
    }

    static String safePathPart(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "default";
        }
        String value = raw.trim();
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-'
                    || c == '.') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.length() == 0 ? "default" : out.toString();
    }
}
