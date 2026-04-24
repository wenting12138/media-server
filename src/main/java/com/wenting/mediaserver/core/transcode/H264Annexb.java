package com.wenting.mediaserver.core.transcode;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

final class H264Annexb {

    private H264Annexb() {
    }

    static List<ByteBuf> splitNalUnits(ByteBuf annexB) {
        List<ByteBuf> out = new ArrayList<ByteBuf>();
        if (annexB == null || !annexB.isReadable()) {
            return out;
        }
        int start = annexB.readerIndex();
        int end = start + annexB.readableBytes();
        int off = start;
        while (off < end) {
            int sc = findStartCode(annexB, off, end);
            if (sc < 0) {
                break;
            }
            int prefix = startCodeLength(annexB, sc, end);
            if (prefix <= 0) {
                break;
            }
            int naluStart = sc + prefix;
            int next = findStartCode(annexB, naluStart, end);
            int naluEnd = next < 0 ? end : next;
            if (naluEnd > naluStart) {
                out.add(annexB.retainedSlice(naluStart, naluEnd - naluStart));
            }
            off = naluEnd;
        }
        return out;
    }

    static int nalType(ByteBuf nalu) {
        if (nalu == null || !nalu.isReadable()) {
            return -1;
        }
        return nalu.getUnsignedByte(nalu.readerIndex()) & 0x1F;
    }

    static boolean isVcl(int nalType) {
        return nalType >= 1 && nalType <= 5;
    }

    private static int findStartCode(ByteBuf buf, int from, int end) {
        for (int i = Math.max(from, buf.readerIndex()); i + 3 < end; i++) {
            if (buf.getUnsignedByte(i) == 0 && buf.getUnsignedByte(i + 1) == 0) {
                int b2 = buf.getUnsignedByte(i + 2);
                if (b2 == 1) {
                    return i;
                }
                if (i + 3 < end && b2 == 0 && buf.getUnsignedByte(i + 3) == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int startCodeLength(ByteBuf buf, int index, int end) {
        if (index + 3 >= end) {
            return -1;
        }
        if (buf.getUnsignedByte(index) != 0 || buf.getUnsignedByte(index + 1) != 0) {
            return -1;
        }
        if (buf.getUnsignedByte(index + 2) == 1) {
            return 3;
        }
        if (index + 3 < end && buf.getUnsignedByte(index + 2) == 0 && buf.getUnsignedByte(index + 3) == 1) {
            return 4;
        }
        return -1;
    }
}
