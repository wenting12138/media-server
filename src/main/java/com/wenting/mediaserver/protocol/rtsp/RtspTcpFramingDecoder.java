package com.wenting.mediaserver.protocol.rtsp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Demultiplexes RTSP-over-TCP: plain RTSP text messages and interleaved binary ($ ch len payload).
 */
public final class RtspTcpFramingDecoder extends ByteToMessageDecoder {

    private static final byte DOLLAR = 0x24;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        while (in.isReadable()) {
            int r0 = in.readerIndex();
            byte first = in.getByte(r0);
            if (first == DOLLAR) {
                if (in.readableBytes() < 4) {
                    return;
                }
                in.skipBytes(1);
                int channel = in.readUnsignedByte();
                int len = in.readUnsignedShort();
                if (in.readableBytes() < len) {
                    in.readerIndex(r0);
                    return;
                }
                ByteBuf payload = in.readRetainedSlice(len);
                out.add(new InterleavedRtpPacket(channel, payload));
                continue;
            }

            RtspRequestMessage msg = tryParseRtsp(in, r0);
            if (msg == null) {
                return;
            }
            out.add(msg);
        }
    }

    /**
     * @return parsed message or null if incomplete; advances {@code in} on success
     */
    private RtspRequestMessage tryParseRtsp(ByteBuf in, int startRidx) {
        int searchStart = startRidx;
        int writer = in.writerIndex();
        int hdr = indexOfDoubleCrlf(in, searchStart, writer);
        if (hdr < 0) {
            return null;
        }
        int bodyStart = hdr + 4;
        int contentLen = parseContentLength(in, searchStart, hdr + 2);
        int need = bodyStart + contentLen;
        if (writer < need) {
            return null;
        }
        int totalLen = need - startRidx;
        ByteBuf composite = in.retainedSlice(startRidx, totalLen);
        in.skipBytes(totalLen);
        return RtspRequestMessage.parse(composite);
    }

    private static int parseContentLength(ByteBuf in, int from, int hdrEnd) {
        int idx = from;
        while (idx < hdrEnd) {
            int lineEnd = indexOfCrlf(in, idx, hdrEnd);
            if (lineEnd < 0) {
                break;
            }
            String line = in.toString(idx, lineEnd - idx, io.netty.util.CharsetUtil.US_ASCII);
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                if ("content-length".equals(name)) {
                    try {
                        return Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
            idx = lineEnd + 2;
        }
        return 0;
    }

    private static int indexOfCrlf(ByteBuf buf, int start, int end) {
        for (int i = start; i + 1 < end; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfDoubleCrlf(ByteBuf buf, int start, int end) {
        for (int i = start; i + 3 < end; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n'
                    && buf.getByte(i + 2) == '\r' && buf.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

}
