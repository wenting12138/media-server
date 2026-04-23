package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Injects H.264 SEI user_data_unregistered NAL into RTMP AVC NALU packets.
 * This is a pure-Java bitstream-level watermark step (no decode/re-encode).
 */
final class H264SeiTimestampWatermarkInjector {

    private static final byte[] WATERMARK_UUID = new byte[]{
            0x57, 0x31, 0x6e, 0x74, 0x69, 0x6e, 0x67, 0x4d,
            0x65, 0x64, 0x69, 0x61, 0x53, 0x65, 0x72, 0x76
    };
    private static final ThreadLocal<SimpleDateFormat> TS_FMT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt;
    });

    private final Map<StreamKey, Integer> nalLengthSizeByStream = new ConcurrentHashMap<StreamKey, Integer>();

    ByteBuf injectIfNeeded(StreamKey key, ByteBuf rtmpVideoPayload, int timestampMs) {
        if (rtmpVideoPayload == null || !rtmpVideoPayload.isReadable()) {
            return null;
        }
        int reader = rtmpVideoPayload.readerIndex();
        int readable = rtmpVideoPayload.readableBytes();
        if (readable < 5) {
            return rtmpVideoPayload.retainedDuplicate();
        }
        int frameType = (rtmpVideoPayload.getUnsignedByte(reader) >> 4) & 0x0F;
        int codecId = rtmpVideoPayload.getUnsignedByte(reader) & 0x0F;
        int avcPacketType = rtmpVideoPayload.getUnsignedByte(reader + 1);
        if (codecId != 7) {
            return rtmpVideoPayload.retainedDuplicate();
        }
        if (avcPacketType == 0) {
            updateNalLengthSizeFromSequenceHeader(key, rtmpVideoPayload);
            return rtmpVideoPayload.retainedDuplicate();
        }
        if (avcPacketType != 1 || frameType != 1) {
            return rtmpVideoPayload.retainedDuplicate();
        }

        int nalLengthSize = nalLengthSizeByStream.getOrDefault(key, Integer.valueOf(4)).intValue();
        byte[] seiNal = buildSeiNal(timestampMs);
        int outSize = readable + nalLengthSize + seiNal.length;
        ByteBuf out = Unpooled.buffer(outSize);
        out.writeBytes(rtmpVideoPayload, reader, 5);
        writeNalLength(out, seiNal.length, nalLengthSize);
        out.writeBytes(seiNal);
        out.writeBytes(rtmpVideoPayload, reader + 5, readable - 5);
        return out;
    }

    void clear(StreamKey key) {
        if (key != null) {
            nalLengthSizeByStream.remove(key);
        }
    }

    private void updateNalLengthSizeFromSequenceHeader(StreamKey key, ByteBuf payload) {
        int reader = payload.readerIndex();
        if (payload.readableBytes() < 10) {
            return;
        }
        int lengthSizeMinusOne = payload.getUnsignedByte(reader + 9) & 0x03;
        int nalLengthSize = lengthSizeMinusOne + 1;
        if (nalLengthSize >= 1 && nalLengthSize <= 4) {
            nalLengthSizeByStream.put(key, Integer.valueOf(nalLengthSize));
        }
    }

    private byte[] buildSeiNal(int timestampMs) {
        String text = "ts=" + TS_FMT.get().format(new Date()) + ",dts=" + timestampMs;
        byte[] message = text.getBytes(StandardCharsets.UTF_8);
        byte[] seiPayload = new byte[WATERMARK_UUID.length + message.length];
        System.arraycopy(WATERMARK_UUID, 0, seiPayload, 0, WATERMARK_UUID.length);
        System.arraycopy(message, 0, seiPayload, WATERMARK_UUID.length, message.length);
        byte[] escapedPayload = escapeRbsp(seiPayload);

        ByteBuf nal = Unpooled.buffer(2 + escapedPayload.length + 8);
        nal.writeByte(0x06); // nal_unit_type = 6 (SEI)
        writeSeiPayloadType(nal, 5); // user_data_unregistered
        writeSeiPayloadSize(nal, escapedPayload.length);
        nal.writeBytes(escapedPayload);
        nal.writeByte(0x80); // rbsp_trailing_bits

        byte[] out = new byte[nal.readableBytes()];
        nal.readBytes(out);
        nal.release();
        return out;
    }

    private void writeSeiPayloadType(ByteBuf out, int payloadType) {
        while (payloadType >= 0xFF) {
            out.writeByte(0xFF);
            payloadType -= 0xFF;
        }
        out.writeByte(payloadType);
    }

    private void writeSeiPayloadSize(ByteBuf out, int payloadSize) {
        while (payloadSize >= 0xFF) {
            out.writeByte(0xFF);
            payloadSize -= 0xFF;
        }
        out.writeByte(payloadSize);
    }

    private void writeNalLength(ByteBuf out, int length, int nalLengthSize) {
        for (int i = nalLengthSize - 1; i >= 0; i--) {
            out.writeByte((length >> (i * 8)) & 0xFF);
        }
    }

    private byte[] escapeRbsp(byte[] raw) {
        ByteBuf escaped = Unpooled.buffer(raw.length + 8);
        int zeroCount = 0;
        for (byte value : raw) {
            int b = value & 0xFF;
            if (zeroCount >= 2 && b <= 0x03) {
                escaped.writeByte(0x03);
                zeroCount = 0;
            }
            escaped.writeByte(b);
            if (b == 0x00) {
                zeroCount++;
            } else {
                zeroCount = 0;
            }
        }
        byte[] out = new byte[escaped.readableBytes()];
        escaped.readBytes(out);
        escaped.release();
        return out;
    }
}
