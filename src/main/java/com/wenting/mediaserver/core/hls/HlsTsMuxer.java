package com.wenting.mediaserver.core.hls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class HlsTsMuxer {
    private static final int TS_PACKET_SIZE = 188;
    private static final int TS_PAYLOAD_MAX = 184;
    private static final int PID_PAT = 0x0000;
    private static final int PID_PMT = 0x0100;
    private static final int PID_VIDEO = 0x0101;
    private static final int PID_AUDIO = 0x0102;

    private int ccPat;
    private int ccPmt;
    private int ccVideo;
    private int ccAudio;
    private final HlsAacConfig aacConfig;

    HlsTsMuxer(HlsAacConfig aacConfig) {
        this.aacConfig = aacConfig;
    }

    void writePatPmt(OutputStream out) throws IOException {
        writePsiPacket(out, PID_PAT, buildPatSection(), true);
        writePsiPacket(out, PID_PMT, buildPmtSection(), true);
    }

    void writeH264AccessUnit(OutputStream out, byte[] annexbAccessUnit, long pts90k) throws IOException {
        byte[] pes = buildPes(annexbAccessUnit, pts90k, true);
        writeTsPayload(out, PID_VIDEO, pes, true);
    }

    void writeAacFrame(OutputStream out, byte[] rawAac, long pts90k) throws IOException {
        if (aacConfig == null) {
            return;
        }
        byte[] adts = buildAdtsFrame(rawAac, aacConfig);
        byte[] pes = buildPes(adts, pts90k, false);
        writeTsPayload(out, PID_AUDIO, pes, true);
    }

    private void writePsiPacket(OutputStream out, int pid, byte[] section, boolean unitStart) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(section.length + 1);
        payload.write(0x00);
        payload.write(section);
        writeTsPayload(out, pid, payload.toByteArray(), unitStart);
    }

    private void writeTsPayload(OutputStream out, int pid, byte[] payload, boolean firstUnitStart) throws IOException {
        int off = 0;
        boolean start = firstUnitStart;
        while (off < payload.length) {
            int remaining = payload.length - off;
            int payloadLen = Math.min(TS_PAYLOAD_MAX, remaining);
            int cc = nextCc(pid);
            byte[] packet = buildTsPacket(pid, start, cc, payload, off, payloadLen);
            out.write(packet);
            off += payloadLen;
            start = false;
        }
    }

    private byte[] buildTsPacket(
            int pid,
            boolean payloadUnitStart,
            int continuityCounter,
            byte[] payload,
            int payloadOffset,
            int payloadLength) {
        byte[] packet = new byte[TS_PACKET_SIZE];
        packet[0] = 0x47;
        packet[1] = (byte) (((payloadUnitStart ? 0x40 : 0x00) | ((pid >> 8) & 0x1F)) & 0xFF);
        packet[2] = (byte) (pid & 0xFF);
        int pos = 4;
        if (payloadLength == TS_PAYLOAD_MAX) {
            packet[3] = (byte) (0x10 | (continuityCounter & 0x0F));
        } else {
            packet[3] = (byte) (0x30 | (continuityCounter & 0x0F));
            int adaptationLength = 183 - payloadLength;
            packet[pos++] = (byte) (adaptationLength & 0xFF);
            if (adaptationLength > 0) {
                packet[pos++] = 0x00;
                for (int i = 1; i < adaptationLength; i++) {
                    packet[pos++] = (byte) 0xFF;
                }
            }
        }
        System.arraycopy(payload, payloadOffset, packet, pos, payloadLength);
        return packet;
    }

    private int nextCc(int pid) {
        if (pid == PID_PAT) {
            int value = ccPat;
            ccPat = (ccPat + 1) & 0x0F;
            return value;
        }
        if (pid == PID_PMT) {
            int value = ccPmt;
            ccPmt = (ccPmt + 1) & 0x0F;
            return value;
        }
        if (pid == PID_AUDIO) {
            int value = ccAudio;
            ccAudio = (ccAudio + 1) & 0x0F;
            return value;
        }
        int value = ccVideo;
        ccVideo = (ccVideo + 1) & 0x0F;
        return value;
    }

    private byte[] buildPes(byte[] payload, long pts90k, boolean video) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 32);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(video ? 0xE0 : 0xC0);
        out.write(0x00);
        out.write(0x00);
        out.write(0x80);
        out.write(0x80);
        out.write(0x05);
        writePts(out, pts90k);
        out.write(payload);
        return out.toByteArray();
    }

    private void writePts(ByteArrayOutputStream out, long pts90k) {
        long pts = pts90k & 0x1FFFFFFFFL;
        out.write((int) (((0x2 << 4) | (((pts >> 30) & 0x07) << 1) | 0x01) & 0xFF));
        out.write((int) ((pts >> 22) & 0xFF));
        out.write((int) (((((pts >> 15) & 0x7F) << 1) | 0x01) & 0xFF));
        out.write((int) ((pts >> 7) & 0xFF));
        out.write((int) ((((pts & 0x7F) << 1) | 0x01) & 0xFF));
    }

    private byte[] buildPatSection() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        out.write(0x00);
        writeShort(out, 0xB000 | 0x000D);
        writeShort(out, 0x0001);
        out.write(0xC1);
        out.write(0x00);
        out.write(0x00);
        writeShort(out, 0x0001);
        writeShort(out, 0xE000 | PID_PMT);
        writeCrc(out);
        return out.toByteArray();
    }

    private byte[] buildPmtSection() throws IOException {
        boolean hasAudio = aacConfig != null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(hasAudio ? 96 : 64);
        out.write(0x02);
        int sectionLength = hasAudio ? 0x0017 : 0x0012;
        writeShort(out, 0xB000 | sectionLength);
        writeShort(out, 0x0001);
        out.write(0xC1);
        out.write(0x00);
        out.write(0x00);
        writeShort(out, 0xE000 | PID_VIDEO);
        writeShort(out, 0xF000);
        out.write(0x1B);
        writeShort(out, 0xE000 | PID_VIDEO);
        writeShort(out, 0xF000);
        if (hasAudio) {
            out.write(0x0F);
            writeShort(out, 0xE000 | PID_AUDIO);
            writeShort(out, 0xF000);
        }
        writeCrc(out);
        return out.toByteArray();
    }

    private void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private void writeCrc(ByteArrayOutputStream out) throws IOException {
        byte[] bytes = out.toByteArray();
        int crc = mpegCrc32(bytes, 0, bytes.length);
        out.write((crc >> 24) & 0xFF);
        out.write((crc >> 16) & 0xFF);
        out.write((crc >> 8) & 0xFF);
        out.write(crc & 0xFF);
    }

    private int mpegCrc32(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 24;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x80000000) != 0) {
                    crc = (crc << 1) ^ 0x04C11DB7;
                } else {
                    crc = crc << 1;
                }
            }
        }
        return crc;
    }

    private byte[] buildAdtsFrame(byte[] rawAac, HlsAacConfig config) {
        int frameLength = rawAac.length + 7;
        int profile = Math.max(1, config.audioObjectType()) - 1;
        int sfIndex = config.sampleRateIndex();
        int channel = config.channelConfig() & 0x07;
        byte[] out = new byte[frameLength];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xF1;
        out[2] = (byte) ((((profile & 0x03) << 6) | ((sfIndex & 0x0F) << 2) | ((channel >> 2) & 0x01)) & 0xFF);
        out[3] = (byte) ((((channel & 0x03) << 6) | ((frameLength >> 11) & 0x03)) & 0xFF);
        out[4] = (byte) ((frameLength >> 3) & 0xFF);
        out[5] = (byte) ((((frameLength & 0x07) << 5) | 0x1F) & 0xFF);
        out[6] = (byte) 0xFC;
        System.arraycopy(rawAac, 0, out, 7, rawAac.length);
        return out;
    }
}
