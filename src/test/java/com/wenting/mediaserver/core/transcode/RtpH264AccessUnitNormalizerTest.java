package com.wenting.mediaserver.core.transcode;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpH264AccessUnitNormalizerTest {

    @Test
    void emitsAccessUnitOnMarkerWithSameTimestamp() {
        RtpH264AccessUnitNormalizer normalizer = new RtpH264AccessUnitNormalizer();
        ByteBuf p1 = rtp(1000, false, new byte[]{0x41, 0x11, 0x22});
        ByteBuf p2 = rtp(1000, true, new byte[]{0x41, 0x33, 0x44});
        try {
            assertTrue(normalizer.ingest(p1).isEmpty());
            List<RtpH264AccessUnitNormalizer.AccessUnit> out = normalizer.ingest(p2);
            assertEquals(1, out.size());
            RtpH264AccessUnitNormalizer.AccessUnit au = out.get(0);
            try {
                assertEquals(1000, au.timestamp90k());
                assertFalse(au.keyFrame());
                assertEquals(2, parseNalTypes(au.annexB()).size());
            } finally {
                au.release();
            }
        } finally {
            p1.release();
            p2.release();
            normalizer.close();
        }
    }

    @Test
    void prependsCachedSpsPpsBeforeKeyframeWhenMissing() {
        RtpH264AccessUnitNormalizer normalizer = new RtpH264AccessUnitNormalizer();
        ByteBuf sps = rtp(900, false, new byte[]{0x67, 0x42, 0x00, 0x1f});
        ByteBuf pps = rtp(900, true, new byte[]{0x68, (byte) 0xce, 0x06, (byte) 0xe2});
        ByteBuf idr = rtp(930, true, new byte[]{0x65, 0x55, 0x66});
        try {
            List<RtpH264AccessUnitNormalizer.AccessUnit> first = normalizer.ingest(sps);
            assertTrue(first.isEmpty());
            List<RtpH264AccessUnitNormalizer.AccessUnit> second = normalizer.ingest(pps);
            assertEquals(1, second.size());
            second.get(0).release();

            List<RtpH264AccessUnitNormalizer.AccessUnit> keyOut = normalizer.ingest(idr);
            assertEquals(1, keyOut.size());
            RtpH264AccessUnitNormalizer.AccessUnit au = keyOut.get(0);
            try {
                assertTrue(au.keyFrame());
                List<Integer> nalTypes = parseNalTypes(au.annexB());
                assertNotNull(nalTypes);
                assertTrue(nalTypes.size() >= 3);
                assertEquals(7, nalTypes.get(0).intValue());
                assertEquals(8, nalTypes.get(1).intValue());
                assertEquals(5, nalTypes.get(2).intValue());
            } finally {
                au.release();
            }
        } finally {
            sps.release();
            pps.release();
            idr.release();
            normalizer.close();
        }
    }

    @Test
    void prependsCachedSpsPpsBeforeNonKeyVclWhenMissing() {
        RtpH264AccessUnitNormalizer normalizer = new RtpH264AccessUnitNormalizer();
        ByteBuf sps = rtp(1200, false, new byte[]{0x67, 0x42, 0x00, 0x1f});
        ByteBuf pps = rtp(1200, true, new byte[]{0x68, (byte) 0xce, 0x06, (byte) 0xe2});
        ByteBuf p = rtp(1230, true, new byte[]{0x41, 0x11, 0x22});
        try {
            List<RtpH264AccessUnitNormalizer.AccessUnit> first = normalizer.ingest(sps);
            assertTrue(first.isEmpty());
            List<RtpH264AccessUnitNormalizer.AccessUnit> second = normalizer.ingest(pps);
            assertEquals(1, second.size());
            second.get(0).release();

            List<RtpH264AccessUnitNormalizer.AccessUnit> out = normalizer.ingest(p);
            assertEquals(1, out.size());
            RtpH264AccessUnitNormalizer.AccessUnit au = out.get(0);
            try {
                assertTrue(au.hasVcl());
                assertTrue(au.hasSps());
                assertTrue(au.hasPps());
                List<Integer> nalTypes = parseNalTypes(au.annexB());
                assertTrue(nalTypes.size() >= 3);
                assertEquals(7, nalTypes.get(0).intValue());
                assertEquals(8, nalTypes.get(1).intValue());
                assertEquals(1, nalTypes.get(2).intValue());
            } finally {
                au.release();
            }
        } finally {
            sps.release();
            pps.release();
            p.release();
            normalizer.close();
        }
    }

    @Test
    void seededParameterSetsArePrependedForFirstVcl() {
        RtpH264AccessUnitNormalizer normalizer = new RtpH264AccessUnitNormalizer();
        normalizer.seedParameterSets(
                new byte[]{0x67, 0x42, 0x00, 0x1f},
                new byte[]{0x68, (byte) 0xce, 0x06, (byte) 0xe2});
        ByteBuf p = rtp(1500, true, new byte[]{0x41, 0x11, 0x22});
        try {
            List<RtpH264AccessUnitNormalizer.AccessUnit> out = normalizer.ingest(p);
            assertEquals(1, out.size());
            RtpH264AccessUnitNormalizer.AccessUnit au = out.get(0);
            try {
                assertTrue(au.hasVcl());
                assertTrue(au.hasSps());
                assertTrue(au.hasPps());
                List<Integer> nalTypes = parseNalTypes(au.annexB());
                assertTrue(nalTypes.size() >= 3);
                assertEquals(7, nalTypes.get(0).intValue());
                assertEquals(8, nalTypes.get(1).intValue());
                assertEquals(1, nalTypes.get(2).intValue());
            } finally {
                au.release();
            }
        } finally {
            p.release();
            normalizer.close();
        }
    }

    private static ByteBuf rtp(int timestamp, boolean marker, byte[] payload) {
        ByteBuf b = Unpooled.buffer(12 + payload.length);
        b.writeByte(0x80);
        b.writeByte((marker ? 0x80 : 0x00) | 96);
        b.writeShort(1);
        b.writeInt(timestamp);
        b.writeInt(0x11223344);
        b.writeBytes(payload);
        return b;
    }

    private static List<Integer> parseNalTypes(ByteBuf annexB) {
        List<Integer> out = new ArrayList<Integer>();
        if (annexB == null || !annexB.isReadable()) {
            return out;
        }
        int ri = annexB.readerIndex();
        int end = ri + annexB.readableBytes();
        int i = ri;
        while (i + 4 <= end) {
            if (annexB.getUnsignedByte(i) == 0
                    && annexB.getUnsignedByte(i + 1) == 0
                    && annexB.getUnsignedByte(i + 2) == 0
                    && annexB.getUnsignedByte(i + 3) == 1) {
                if (i + 4 < end) {
                    out.add(annexB.getUnsignedByte(i + 4) & 0x1F);
                }
                i += 4;
                continue;
            }
            i++;
        }
        return out;
    }
}
