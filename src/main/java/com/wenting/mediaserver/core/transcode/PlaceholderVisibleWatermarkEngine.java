package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.core.model.StreamKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NONE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable;
import static org.bytedeco.ffmpeg.global.avutil.av_image_copy;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

/**
 * Real visible watermark path for RTMP/H264:
 * decode AVCC NALU packets -> draw timestamp on Y plane -> encode H264 -> repack AVCC.
 */
final class PlaceholderVisibleWatermarkEngine implements VisibleWatermarkEngine {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderVisibleWatermarkEngine.class);
    private static final ThreadLocal<SimpleDateFormat> TS_FMT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt;
    });
    private static final int[] ASCII_5X7 = buildAscii5x7();

    private final Map<StreamKey, StreamState> states = new ConcurrentHashMap<StreamKey, StreamState>();

    @Override
    public ByteBuf apply(StreamKey streamKey, ByteBuf rtmpH264Payload, int timestampMs) {
        if (streamKey == null || rtmpH264Payload == null || !rtmpH264Payload.isReadable()) {
            return rtmpH264Payload;
        }
        StreamState st = states.computeIfAbsent(streamKey, k -> new StreamState());
        try {
            return transcode(st, streamKey, rtmpH264Payload, timestampMs);
        } catch (Exception e) {
            log.warn("Visible watermark failed, fallback passthrough stream={}", streamKey.path(), e);
            return rtmpH264Payload;
        }
    }

    @Override
    public void clear(StreamKey streamKey) {
        if (streamKey == null) {
            return;
        }
        StreamState st = states.remove(streamKey);
        if (st != null) {
            st.release();
        }
    }

    @Override
    public List<ByteBuf> pollPrefixPackets(StreamKey streamKey) {
        if (streamKey == null) {
            return Collections.emptyList();
        }
        StreamState st = states.get(streamKey);
        if (st == null) {
            return Collections.emptyList();
        }
        ByteBuf seq = st.pollPendingSequenceHeader();
        if (seq == null) {
            return Collections.emptyList();
        }
        List<ByteBuf> out = new ArrayList<ByteBuf>(1);
        out.add(seq);
        return out;
    }

    private ByteBuf transcode(StreamState st, StreamKey streamKey, ByteBuf payload, int timestampMs) {
        int reader = payload.readerIndex();
        int readable = payload.readableBytes();
        if (readable < 5) {
            return payload;
        }
        int frameType = (payload.getUnsignedByte(reader) >> 4) & 0x0F;
        int codecId = payload.getUnsignedByte(reader) & 0x0F;
        int avcPacketType = payload.getUnsignedByte(reader + 1);
        if (codecId != 7) {
            return payload;
        }
        if (avcPacketType == 0) {
            st.parseSequenceHeader(payload);
            return payload;
        }
        if (avcPacketType != 1) {
            return payload;
        }
        byte[] annexb = st.avccToAnnexb(payload, frameType == 1);
        if (annexb == null || annexb.length == 0) {
            return payload;
        }
        if (!st.ensureDecoder()) {
            return payload;
        }
        if (!st.decodePacket(annexb, timestampMs)) {
            return payload;
        }
        byte[] encodedAnnexb = st.encodeOneFrame(streamKey, timestampMs);
        if (encodedAnnexb == null || encodedAnnexb.length == 0) {
            return payload;
        }
        ByteBuf out = st.annexbToAvccPayload(encodedAnnexb, timestampMs);
        if (out != null && st.appliedLogged.compareAndSet(false, true)) {
            log.info("Visible watermark applied stream={} in={}B out={}B ts={}",
                    streamKey.path(), payload.readableBytes(), out.readableBytes(), timestampMs);
        }
        return out == null ? payload : out;
    }

    private static final class StreamState {
        private AVCodecContext decCtx;
        private AVCodecContext encCtx;
        private AVPacket decPkt;
        private AVPacket encPkt;
        private AVFrame decFrame;
        private AVFrame encFrame;
        private SwsContext sws;
        private int nalLengthSize = 4;
        private byte[] sps;
        private byte[] pps;
        private boolean decoderReady;
        private boolean encoderReady;
        private long encPts;
        private byte[] encSps;
        private byte[] encPps;
        private ByteBuf pendingSequenceHeader;
        private byte[] lastSequenceHeaderBytes;
        private final AtomicBoolean appliedLogged = new AtomicBoolean(false);

        private void parseSequenceHeader(ByteBuf payload) {
            int base = payload.readerIndex() + 5;
            int end = payload.readerIndex() + payload.readableBytes();
            if (base + 6 > end) {
                return;
            }
            int lengthSizeMinusOne = payload.getUnsignedByte(base + 4) & 0x03;
            nalLengthSize = lengthSizeMinusOne + 1;
            int off = base + 5;
            int numSps = payload.getUnsignedByte(off) & 0x1F;
            off++;
            for (int i = 0; i < numSps; i++) {
                if (off + 2 > end) {
                    return;
                }
                int len = ((payload.getUnsignedByte(off) << 8) | payload.getUnsignedByte(off + 1));
                off += 2;
                if (off + len > end) {
                    return;
                }
                byte[] data = new byte[len];
                payload.getBytes(off, data);
                if (i == 0) {
                    sps = data;
                }
                off += len;
            }
            if (off + 1 > end) {
                return;
            }
            int numPps = payload.getUnsignedByte(off);
            off++;
            for (int i = 0; i < numPps; i++) {
                if (off + 2 > end) {
                    return;
                }
                int len = ((payload.getUnsignedByte(off) << 8) | payload.getUnsignedByte(off + 1));
                off += 2;
                if (off + len > end) {
                    return;
                }
                byte[] data = new byte[len];
                payload.getBytes(off, data);
                if (i == 0) {
                    pps = data;
                }
                off += len;
            }
        }

        private boolean ensureDecoder() {
            if (decoderReady) {
                return true;
            }
            AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_H264);
            if (codec == null) {
                return false;
            }
            decCtx = avcodec_alloc_context3(codec);
            decPkt = av_packet_alloc();
            decFrame = av_frame_alloc();
            if (decCtx == null || decPkt == null || decFrame == null) {
                return false;
            }
            int rc = avcodec_open2(decCtx, codec, (AVDictionary) null);
            if (rc < 0) {
                return false;
            }
            decoderReady = true;
            return true;
        }

        private boolean decodePacket(byte[] annexb, int timestampMs) {
            int rc = av_packet_unrefAndNewData(decPkt, annexb);
            if (rc < 0) {
                return false;
            }
            decPkt.pts(timestampMs);
            decPkt.dts(timestampMs);
            rc = avcodec_send_packet(decCtx, decPkt);
            av_packet_unref(decPkt);
            return rc >= 0;
        }

        private byte[] encodeOneFrame(StreamKey streamKey, int timestampMs) {
            int rc;
            while ((rc = avcodec_receive_frame(decCtx, decFrame)) >= 0) {
                if (!ensureEncoder(decFrame, streamKey)) {
                    return null;
                }
                AVFrame frameForEncode = prepareFrameForEncode(decFrame);
                if (frameForEncode == null) {
                    return null;
                }
                drawTimestamp(frameForEncode, streamKey, timestampMs);
                frameForEncode.pts(encPts++);
                rc = avcodec_send_frame(encCtx, frameForEncode);
                if (rc < 0) {
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
                while ((rc = avcodec_receive_packet(encCtx, encPkt)) >= 0) {
                    byte[] chunk = new byte[encPkt.size()];
                    encPkt.data().position(0).get(chunk);
                    appendNormalizedAnnexbChunk(baos, chunk);
                    av_packet_unref(encPkt);
                }
                byte[] merged = baos.toByteArray();
                if (merged.length > 0) {
                    return merged;
                }
                if (rc != ffErrEagain() && rc != AVERROR_EOF) {
                    return null;
                }
            }
            if (rc == ffErrEagain() || rc == AVERROR_EOF) {
                return null;
            }
            return null;
        }

        private boolean ensureEncoder(AVFrame frame, StreamKey streamKey) {
            if (encoderReady) {
                return true;
            }
            AVCodec codec = avcodec_find_encoder_by_name("libx264");
            if (codec == null) {
                codec = avcodec_find_encoder(AV_CODEC_ID_H264);
            }
            if (codec == null) {
                return false;
            }
            String codecName = codec.name() != null ? codec.name().getString() : "";
            encCtx = avcodec_alloc_context3(codec);
            encPkt = av_packet_alloc();
            encFrame = av_frame_alloc();
            if (encCtx == null || encPkt == null || encFrame == null) {
                return false;
            }
            int width = frame.width();
            int height = frame.height();
            int srcFmt = frame.format();
            int encFmt = selectEncoderPixFmt(codec, srcFmt);
            encCtx.width(width);
            encCtx.height(height);
            encCtx.pix_fmt(encFmt);
            encCtx.time_base().num(1).den(1000);
            encCtx.framerate().num(25).den(1);
            encCtx.gop_size(25);
            encCtx.max_b_frames(0);

            AVDictionary dict = new AVDictionary();
            if ("libx264".equals(codecName)) {
                av_dict_set(dict, "preset", "ultrafast", 0);
                av_dict_set(dict, "tune", "zerolatency", 0);
                av_dict_set(dict, "profile", "baseline", 0);
                av_dict_set(dict, "level", "3.1", 0);
                av_dict_set(dict, "x264-params",
                        "annexb=1:repeat-headers=1:aud=0:scenecut=0:keyint=25:min-keyint=25:bframes=0:cabac=0:ref=1:weightp=0:8x8dct=0",
                        0);
            }
            int rc = avcodec_open2(encCtx, codec, dict);
            av_dict_free(dict);
            if (rc < 0) {
                log.warn("Visible watermark encoder open failed with options stream={} codec={} rc={}, retrying without options",
                        streamKey.path(), codecName, rc);
                rc = avcodec_open2(encCtx, codec, (AVDictionary) null);
                if (rc < 0) {
                    return false;
                }
            }

            encFrame.format(encFmt);
            encFrame.width(width);
            encFrame.height(height);
            rc = av_frame_get_buffer(encFrame, 32);
            if (rc < 0) {
                return false;
            }
            encoderReady = true;
            log.info("Visible watermark encoder ready stream={} {}x{} srcFmt={} encFmt={}",
                    streamKey.path(), width, height, srcFmt, encFmt);
            return true;
        }

        private int selectEncoderPixFmt(AVCodec codec, int srcFmt) {
            IntPointer pixFmts = codec.pix_fmts();
            if (pixFmts == null) {
                return srcFmt;
            }
            int fallback = AV_PIX_FMT_YUV420P;
            for (int i = 0; ; i++) {
                int pf = pixFmts.get(i);
                if (pf == AV_PIX_FMT_NONE) {
                    break;
                }
                if (pf == srcFmt) {
                    return srcFmt;
                }
                if (pf == AV_PIX_FMT_YUV420P) {
                    fallback = AV_PIX_FMT_YUV420P;
                }
            }
            return fallback;
        }

        private AVFrame prepareFrameForEncode(AVFrame src) {
            if (encCtx.pix_fmt() == src.format()) {
                int rc = av_frame_make_writable(src);
                return rc < 0 ? null : src;
            }
            int rc = av_frame_make_writable(encFrame);
            if (rc < 0) {
                return null;
            }
            sws = sws_getCachedContext(
                    sws,
                    src.width(),
                    src.height(),
                    src.format(),
                    encFrame.width(),
                    encFrame.height(),
                    encFrame.format(),
                    SWS_BILINEAR,
                    null,
                    null,
                    (DoublePointer) null);
            if (sws == null) {
                return null;
            }
            sws_scale(
                    sws,
                    new PointerPointer(src.data()),
                    src.linesize(),
                    0,
                    src.height(),
                    new PointerPointer(encFrame.data()),
                    encFrame.linesize());
            return encFrame;
        }

        private void drawTimestamp(AVFrame frame, StreamKey streamKey, int timestampMs) {
            String text = TS_FMT.get().format(new Date()) + " " + streamKey.path() + " t=" + timestampMs;
            byte[] chars = text.getBytes(StandardCharsets.US_ASCII);
            int width = frame.width();
            int scale = chooseScale(width);
            int x = Math.max(12, 10 * scale);
            int y = Math.max(12, 8 * scale);
            BytePointer yPlane = frame.data(0);
            int stride = frame.linesize(0);
            int height = frame.height();
            int glyphAdvance = 6 * scale;
            for (int i = 0; i < chars.length; i++) {
                int c = chars[i] & 0xFF;
                if (c >= ASCII_5X7.length) {
                    c = '?';
                }
                int bits = ASCII_5X7[c];
                int gx = x + i * glyphAdvance;
                int shadowOffset = Math.max(1, scale / 2);
                drawGlyph(yPlane, stride, width, height, gx + shadowOffset, y + shadowOffset, bits, 24, scale);
                drawGlyph(yPlane, stride, width, height, gx, y, bits, 230, scale);
            }
        }

        private int chooseScale(int width) {
            if (width >= 1920) {
                return 4;
            }
            if (width >= 1280) {
                return 3;
            }
            if (width >= 854) {
                return 2;
            }
            return 1;
        }

        private void drawGlyph(BytePointer yPlane, int stride, int width, int height, int x, int y, int bits, int value, int scale) {
            for (int row = 0; row < 7; row++) {
                for (int col = 0; col < 5; col++) {
                    int bit = row * 5 + col;
                    if (((bits >> bit) & 0x01) == 0) {
                        continue;
                    }
                    int baseX = x + col * scale;
                    int baseY = y + row * scale;
                    for (int sy = 0; sy < scale; sy++) {
                        for (int sx = 0; sx < scale; sx++) {
                            int px = baseX + sx;
                            int py = baseY + sy;
                            if (px < 0 || py < 0 || px >= width || py >= height) {
                                continue;
                            }
                            long idx = (long) py * stride + px;
                            yPlane.put(idx, (byte) value);
                        }
                    }
                }
            }
        }

        private byte[] avccToAnnexb(ByteBuf payload, boolean keyFrame) {
            int off = payload.readerIndex() + 5;
            int end = payload.readerIndex() + payload.readableBytes();
            byte[] out = new byte[payload.readableBytes() * 2 + 128];
            int wp = 0;
            if (keyFrame) {
                if (sps != null && sps.length > 0) {
                    wp = appendStartCodeNalu(out, wp, sps);
                }
                if (pps != null && pps.length > 0) {
                    wp = appendStartCodeNalu(out, wp, pps);
                }
            }
            while (off + nalLengthSize <= end) {
                int len = 0;
                for (int i = 0; i < nalLengthSize; i++) {
                    len = (len << 8) | payload.getUnsignedByte(off + i);
                }
                off += nalLengthSize;
                if (len <= 0 || off + len > end) {
                    break;
                }
                if (wp + 4 + len > out.length) {
                    byte[] grown = new byte[Math.max(out.length * 2, wp + 4 + len + 64)];
                    System.arraycopy(out, 0, grown, 0, wp);
                    out = grown;
                }
                out[wp++] = 0x00;
                out[wp++] = 0x00;
                out[wp++] = 0x00;
                out[wp++] = 0x01;
                payload.getBytes(off, out, wp, len);
                wp += len;
                off += len;
            }
            if (wp == 0) {
                return null;
            }
            byte[] exact = new byte[wp];
            System.arraycopy(out, 0, exact, 0, wp);
            return exact;
        }

        private int appendStartCodeNalu(byte[] out, int wp, byte[] nalu) {
            if (wp + 4 + nalu.length > out.length) {
                return wp;
            }
            out[wp++] = 0x00;
            out[wp++] = 0x00;
            out[wp++] = 0x00;
            out[wp++] = 0x01;
            System.arraycopy(nalu, 0, out, wp, nalu.length);
            wp += nalu.length;
            return wp;
        }

        private ByteBuf annexbToAvccPayload(byte[] annexb, int timestampMs) {
            List<byte[]> nalus = splitAnnexbNalUnits(annexb);
            if (nalus.isEmpty()) {
                return null;
            }
            List<byte[]> keep = new ArrayList<byte[]>(nalus.size());
            boolean key = false;
            boolean hasVcl = false;
            int total = 5;
            for (byte[] nalu : nalus) {
                if (nalu.length == 0) {
                    continue;
                }
                int type = nalu[0] & 0x1F;
                if (type == 9) {
                    // AUD is optional and may confuse some downstream parsers in mixed pipelines.
                    continue;
                }
                if (type == 7) {
                    encSps = nalu;
                    keep.add(nalu);
                    total += 4 + nalu.length;
                    continue;
                }
                if (type == 8) {
                    encPps = nalu;
                    keep.add(nalu);
                    total += 4 + nalu.length;
                    continue;
                }
                if (type == 5 || type == 1) {
                    if (type == 5) {
                        key = true;
                    }
                    hasVcl = true;
                    keep.add(nalu);
                    total += 4 + nalu.length;
                    continue;
                }
                if (type >= 2 && type <= 4) {
                    hasVcl = true;
                    keep.add(nalu);
                    total += 4 + nalu.length;
                }
            }
            if (keep.isEmpty()) {
                return null;
            }
            if (encSps != null && encPps != null) {
                pushPendingSequenceHeader(buildSequenceHeader(encSps, encPps));
            }
            ByteBuf out = Unpooled.buffer(total);
            out.writeByte(key ? 0x17 : 0x27);
            out.writeByte(0x01);
            out.writeByte(0x00);
            out.writeByte(0x00);
            out.writeByte(0x00);
            for (byte[] nalu : keep) {
                if (nalu.length == 0) {
                    continue;
                }
                out.writeInt(nalu.length);
                out.writeBytes(nalu);
            }
            return out;
        }

        private ByteBuf pollPendingSequenceHeader() {
            ByteBuf seq = pendingSequenceHeader;
            pendingSequenceHeader = null;
            return seq;
        }

        private void pushPendingSequenceHeader(ByteBuf seq) {
            if (seq == null) {
                return;
            }
            byte[] seqBytes = toByteArray(seq);
            if (isSameSequenceHeader(seqBytes)) {
                seq.release();
                return;
            }
            lastSequenceHeaderBytes = seqBytes;
            if (pendingSequenceHeader != null) {
                pendingSequenceHeader.release();
            }
            pendingSequenceHeader = seq;
        }

        private boolean isSameSequenceHeader(byte[] current) {
            if (current == null || lastSequenceHeaderBytes == null) {
                return false;
            }
            if (current.length != lastSequenceHeaderBytes.length) {
                return false;
            }
            for (int i = 0; i < current.length; i++) {
                if (current[i] != lastSequenceHeaderBytes[i]) {
                    return false;
                }
            }
            return true;
        }

        private byte[] toByteArray(ByteBuf buf) {
            int len = buf.readableBytes();
            byte[] out = new byte[len];
            buf.getBytes(buf.readerIndex(), out);
            return out;
        }

        private ByteBuf buildSequenceHeader(byte[] spsNalu, byte[] ppsNalu) {
            if (spsNalu == null || spsNalu.length < 4 || ppsNalu == null || ppsNalu.length == 0) {
                return null;
            }
            int size = 5 + 6 + 2 + spsNalu.length + 1 + 2 + ppsNalu.length;
            ByteBuf seq = Unpooled.buffer(size);
            seq.writeByte(0x17);
            seq.writeByte(0x00);
            seq.writeByte(0x00);
            seq.writeByte(0x00);
            seq.writeByte(0x00);
            seq.writeByte(0x01);
            seq.writeByte(spsNalu[1] & 0xFF);
            seq.writeByte(spsNalu[2] & 0xFF);
            seq.writeByte(spsNalu[3] & 0xFF);
            seq.writeByte(0xFF); // lengthSizeMinusOne = 3 (4 bytes)
            seq.writeByte(0xE1); // 1 SPS
            seq.writeShort(spsNalu.length);
            seq.writeBytes(spsNalu);
            seq.writeByte(0x01); // 1 PPS
            seq.writeShort(ppsNalu.length);
            seq.writeBytes(ppsNalu);
            return seq;
        }

        private List<byte[]> splitAnnexbNalUnits(byte[] bytes) {
            List<byte[]> list = new ArrayList<byte[]>();
            int i = 0;
            while (i + 3 < bytes.length) {
                int start = findStartCode(bytes, i);
                if (start < 0) {
                    break;
                }
                int prefix = bytes[start + 2] == 0x01 ? 3 : 4;
                int naluStart = start + prefix;
                int next = findStartCode(bytes, naluStart);
                int naluEnd = next < 0 ? bytes.length : next;
                if (naluEnd > naluStart) {
                    int len = naluEnd - naluStart;
                    byte[] nalu = new byte[len];
                    System.arraycopy(bytes, naluStart, nalu, 0, len);
                    list.add(nalu);
                }
                i = naluEnd;
            }
            return list;
        }

        private int findStartCode(byte[] bytes, int from) {
            for (int i = Math.max(0, from); i + 3 < bytes.length; i++) {
                if (bytes[i] == 0x00 && bytes[i + 1] == 0x00) {
                    if (bytes[i + 2] == 0x01) {
                        return i;
                    }
                    if (i + 3 < bytes.length && bytes[i + 2] == 0x00 && bytes[i + 3] == 0x01) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private void appendNormalizedAnnexbChunk(ByteArrayOutputStream out, byte[] chunk) {
            if (chunk == null || chunk.length == 0) {
                return;
            }
            if (looksLikeAnnexb(chunk)) {
                out.write(chunk, 0, chunk.length);
                return;
            }
            int off = 0;
            while (off + 4 <= chunk.length) {
                int len = ((chunk[off] & 0xFF) << 24)
                        | ((chunk[off + 1] & 0xFF) << 16)
                        | ((chunk[off + 2] & 0xFF) << 8)
                        | (chunk[off + 3] & 0xFF);
                off += 4;
                if (len <= 0 || off + len > chunk.length) {
                    out.write(chunk, 0, chunk.length);
                    return;
                }
                out.write(0x00);
                out.write(0x00);
                out.write(0x00);
                out.write(0x01);
                out.write(chunk, off, len);
                off += len;
            }
        }

        private boolean looksLikeAnnexb(byte[] bytes) {
            if (bytes.length < 4) {
                return false;
            }
            for (int i = 0; i + 3 < bytes.length; i++) {
                if (bytes[i] == 0x00 && bytes[i + 1] == 0x00) {
                    if (bytes[i + 2] == 0x01) {
                        return true;
                    }
                    if (i + 3 < bytes.length && bytes[i + 2] == 0x00 && bytes[i + 3] == 0x01) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void release() {
            if (pendingSequenceHeader != null) {
                pendingSequenceHeader.release();
                pendingSequenceHeader = null;
            }
            lastSequenceHeaderBytes = null;
            if (sws != null) {
                sws_freeContext(sws);
                sws = null;
            }
            if (decFrame != null) {
                AVFrame tmp = decFrame;
                av_frame_free(tmp);
                decFrame = null;
            }
            if (encFrame != null) {
                AVFrame tmp = encFrame;
                av_frame_free(tmp);
                encFrame = null;
            }
            if (decPkt != null) {
                AVPacket tmp = decPkt;
                av_packet_free(tmp);
                decPkt = null;
            }
            if (encPkt != null) {
                AVPacket tmp = encPkt;
                av_packet_free(tmp);
                encPkt = null;
            }
            if (decCtx != null) {
                AVCodecContext tmp = decCtx;
                avcodec_free_context(tmp);
                decCtx = null;
            }
            if (encCtx != null) {
                AVCodecContext tmp = encCtx;
                avcodec_free_context(tmp);
                encCtx = null;
            }
            decoderReady = false;
            encoderReady = false;
        }
    }

    private static int av_packet_unrefAndNewData(AVPacket packet, byte[] data) {
        av_packet_unref(packet);
        int rc = org.bytedeco.ffmpeg.global.avcodec.av_new_packet(packet, data.length);
        if (rc < 0) {
            return rc;
        }
        packet.data().position(0).put(data);
        return 0;
    }

    private static int ffErrEagain() {
        return -AVERROR_EAGAIN();
    }

    private static int[] buildAscii5x7() {
        int[] m = new int[128];
        put(m, '0', "11111", "10001", "10011", "10101", "11001", "10001", "11111");
        put(m, '1', "00100", "01100", "00100", "00100", "00100", "00100", "01110");
        put(m, '2', "11111", "00001", "00001", "11111", "10000", "10000", "11111");
        put(m, '3', "11111", "00001", "00001", "01111", "00001", "00001", "11111");
        put(m, '4', "10001", "10001", "10001", "11111", "00001", "00001", "00001");
        put(m, '5', "11111", "10000", "10000", "11111", "00001", "00001", "11111");
        put(m, '6', "11111", "10000", "10000", "11111", "10001", "10001", "11111");
        put(m, '7', "11111", "00001", "00001", "00010", "00100", "01000", "01000");
        put(m, '8', "11111", "10001", "10001", "11111", "10001", "10001", "11111");
        put(m, '9', "11111", "10001", "10001", "11111", "00001", "00001", "11111");
        put(m, '-', "00000", "00000", "00000", "11111", "00000", "00000", "00000");
        put(m, ':', "00000", "00100", "00100", "00000", "00100", "00100", "00000");
        put(m, '.', "00000", "00000", "00000", "00000", "00000", "00100", "00100");
        put(m, '/', "00001", "00010", "00100", "01000", "10000", "00000", "00000");
        put(m, ' ', "00000", "00000", "00000", "00000", "00000", "00000", "00000");
        put(m, 't', "00100", "00100", "11111", "00100", "00100", "00100", "00011");
        put(m, '=', "00000", "11111", "00000", "11111", "00000", "00000", "00000");
        put(m, '?', "11111", "00001", "00010", "00100", "00100", "00000", "00100");
        for (char c = 'a'; c <= 'z'; c++) {
            m[c] = m[Character.toUpperCase(c)];
        }
        put(m, 'A', "01110", "10001", "10001", "11111", "10001", "10001", "10001");
        put(m, 'B', "11110", "10001", "10001", "11110", "10001", "10001", "11110");
        put(m, 'C', "01111", "10000", "10000", "10000", "10000", "10000", "01111");
        put(m, 'D', "11110", "10001", "10001", "10001", "10001", "10001", "11110");
        put(m, 'E', "11111", "10000", "10000", "11111", "10000", "10000", "11111");
        put(m, 'F', "11111", "10000", "10000", "11111", "10000", "10000", "10000");
        put(m, 'G', "01111", "10000", "10000", "10011", "10001", "10001", "01111");
        put(m, 'H', "10001", "10001", "10001", "11111", "10001", "10001", "10001");
        put(m, 'I', "11111", "00100", "00100", "00100", "00100", "00100", "11111");
        put(m, 'J', "00001", "00001", "00001", "00001", "10001", "10001", "01110");
        put(m, 'K', "10001", "10010", "10100", "11000", "10100", "10010", "10001");
        put(m, 'L', "10000", "10000", "10000", "10000", "10000", "10000", "11111");
        put(m, 'M', "10001", "11011", "10101", "10001", "10001", "10001", "10001");
        put(m, 'N', "10001", "11001", "10101", "10011", "10001", "10001", "10001");
        put(m, 'O', "01110", "10001", "10001", "10001", "10001", "10001", "01110");
        put(m, 'P', "11110", "10001", "10001", "11110", "10000", "10000", "10000");
        put(m, 'Q', "01110", "10001", "10001", "10001", "10101", "10010", "01101");
        put(m, 'R', "11110", "10001", "10001", "11110", "10100", "10010", "10001");
        put(m, 'S', "01111", "10000", "10000", "01110", "00001", "00001", "11110");
        put(m, 'T', "11111", "00100", "00100", "00100", "00100", "00100", "00100");
        put(m, 'U', "10001", "10001", "10001", "10001", "10001", "10001", "01110");
        put(m, 'V', "10001", "10001", "10001", "10001", "10001", "01010", "00100");
        put(m, 'W', "10001", "10001", "10001", "10101", "10101", "10101", "01010");
        put(m, 'X', "10001", "10001", "01010", "00100", "01010", "10001", "10001");
        put(m, 'Y', "10001", "10001", "01010", "00100", "00100", "00100", "00100");
        put(m, 'Z', "11111", "00001", "00010", "00100", "01000", "10000", "11111");
        return m;
    }

    private static void put(int[] map, int ch, String r0, String r1, String r2, String r3, String r4, String r5, String r6) {
        String[] rows = new String[]{r0, r1, r2, r3, r4, r5, r6};
        int bits = 0;
        for (int y = 0; y < 7; y++) {
            String row = rows[y];
            for (int x = 0; x < 5; x++) {
                if (row.charAt(x) == '1') {
                    bits |= (1 << (y * 5 + x));
                }
            }
        }
        map[ch] = bits;
    }
}
