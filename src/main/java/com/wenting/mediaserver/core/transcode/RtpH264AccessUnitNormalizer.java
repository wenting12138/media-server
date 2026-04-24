package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.protocol.rtp.H264RtpDepacketizer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reassembles RTP/H264 packets into complete access units and normalizes output to AnnexB.
 * Also caches SPS/PPS and prepends them before keyframes when missing in the current AU.
 */
public final class RtpH264AccessUnitNormalizer implements AutoCloseable {

    private static final int START_CODE_LEN = 4;

    private final H264RtpDepacketizer depacketizer = new H264RtpDepacketizer();
    private final List<ByteBuf> pendingNals = new ArrayList<ByteBuf>();

    private int currentTimestamp90k = Integer.MIN_VALUE;
    private ByteBuf cachedSps;
    private ByteBuf cachedPps;

    public void seedParameterSets(byte[] spsNalu, byte[] ppsNalu) {
        if (spsNalu != null && spsNalu.length > 0) {
            ReferenceCountUtil.safeRelease(cachedSps);
            cachedSps = wrapAsAnnexbNal(spsNalu);
        }
        if (ppsNalu != null && ppsNalu.length > 0) {
            ReferenceCountUtil.safeRelease(cachedPps);
            cachedPps = wrapAsAnnexbNal(ppsNalu);
        }
    }

    public List<AccessUnit> ingest(ByteBuf rtpPacket) {
        if (rtpPacket == null || !rtpPacket.isReadable()) {
            return Collections.emptyList();
        }
        List<AccessUnit> out = new ArrayList<AccessUnit>(2);
        int ts = readTimestamp(rtpPacket);
        boolean marker = (rtpPacket.getUnsignedByte(rtpPacket.readerIndex() + 1) & 0x80) != 0;

        if (currentTimestamp90k != Integer.MIN_VALUE && ts != currentTimestamp90k && !pendingNals.isEmpty()) {
            out.add(emitCurrentAccessUnit(currentTimestamp90k));
            currentTimestamp90k = Integer.MIN_VALUE;
        }
        if (currentTimestamp90k == Integer.MIN_VALUE) {
            currentTimestamp90k = ts;
        }

        depacketizer.ingest(rtpPacket, this::onNal);

        if (marker && !pendingNals.isEmpty()) {
            out.add(emitCurrentAccessUnit(currentTimestamp90k));
            currentTimestamp90k = Integer.MIN_VALUE;
        }
        return out;
    }

    public AccessUnit flush() {
        if (pendingNals.isEmpty()) {
            return null;
        }
        int ts = currentTimestamp90k == Integer.MIN_VALUE ? 0 : currentTimestamp90k;
        AccessUnit au = emitCurrentAccessUnit(ts);
        currentTimestamp90k = Integer.MIN_VALUE;
        return au;
    }

    public void reset() {
        depacketizer.reset();
        currentTimestamp90k = Integer.MIN_VALUE;
        releaseAll(pendingNals);
        pendingNals.clear();
        ReferenceCountUtil.safeRelease(cachedSps);
        ReferenceCountUtil.safeRelease(cachedPps);
        cachedSps = null;
        cachedPps = null;
    }

    @Override
    public void close() {
        reset();
    }

    private void onNal(ByteBuf nal) {
        if (nal == null || !nal.isReadable()) {
            ReferenceCountUtil.safeRelease(nal);
            return;
        }
        int nalType = readNalType(nal);
        if (nalType == 7) {
            ReferenceCountUtil.safeRelease(cachedSps);
            cachedSps = nal.retainedDuplicate();
        } else if (nalType == 8) {
            ReferenceCountUtil.safeRelease(cachedPps);
            cachedPps = nal.retainedDuplicate();
        }
        pendingNals.add(nal);
    }

    private AccessUnit emitCurrentAccessUnit(int timestamp90k) {
        boolean keyFrame = containsNalType(pendingNals, 5);
        boolean hasVcl = containsAnyVclNal(pendingNals);
        boolean hasSps = containsNalType(pendingNals, 7);
        boolean hasPps = containsNalType(pendingNals, 8);

        int totalLen = 0;
        if (hasVcl && !hasSps && cachedSps != null && cachedSps.isReadable()) {
            totalLen += cachedSps.readableBytes();
        }
        if (hasVcl && !hasPps && cachedPps != null && cachedPps.isReadable()) {
            totalLen += cachedPps.readableBytes();
        }
        for (ByteBuf nal : pendingNals) {
            if (nal != null && nal.isReadable()) {
                totalLen += nal.readableBytes();
            }
        }

        ByteBuf accessUnit = Unpooled.buffer(Math.max(totalLen, START_CODE_LEN));
        if (hasVcl && !hasSps && cachedSps != null && cachedSps.isReadable()) {
            accessUnit.writeBytes(cachedSps, cachedSps.readerIndex(), cachedSps.readableBytes());
            hasSps = true;
        }
        if (hasVcl && !hasPps && cachedPps != null && cachedPps.isReadable()) {
            accessUnit.writeBytes(cachedPps, cachedPps.readerIndex(), cachedPps.readableBytes());
            hasPps = true;
        }
        for (ByteBuf nal : pendingNals) {
            if (nal != null && nal.isReadable()) {
                accessUnit.writeBytes(nal, nal.readerIndex(), nal.readableBytes());
            }
        }

        releaseAll(pendingNals);
        pendingNals.clear();
        return new AccessUnit(timestamp90k, keyFrame, hasVcl, hasSps, hasPps, accessUnit);
    }

    private static boolean containsNalType(List<ByteBuf> nals, int type) {
        for (ByteBuf nal : nals) {
            if (readNalType(nal) == type) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyVclNal(List<ByteBuf> nals) {
        for (ByteBuf nal : nals) {
            int t = readNalType(nal);
            if (t >= 1 && t <= 5) {
                return true;
            }
        }
        return false;
    }

    private static int readNalType(ByteBuf annexBNal) {
        if (annexBNal == null || annexBNal.readableBytes() <= 0) {
            return -1;
        }
        int ri = annexBNal.readerIndex();
        int bytes = annexBNal.readableBytes();
        int nalHeaderIndex = ri;
        if (bytes >= 4
                && annexBNal.getUnsignedByte(ri) == 0
                && annexBNal.getUnsignedByte(ri + 1) == 0
                && annexBNal.getUnsignedByte(ri + 2) == 0
                && annexBNal.getUnsignedByte(ri + 3) == 1) {
            nalHeaderIndex = ri + 4;
        } else if (bytes >= 3
                && annexBNal.getUnsignedByte(ri) == 0
                && annexBNal.getUnsignedByte(ri + 1) == 0
                && annexBNal.getUnsignedByte(ri + 2) == 1) {
            nalHeaderIndex = ri + 3;
        }
        if (nalHeaderIndex >= ri + bytes) {
            return -1;
        }
        return annexBNal.getUnsignedByte(nalHeaderIndex) & 0x1F;
    }

    private static int readTimestamp(ByteBuf rtpPacket) {
        if (rtpPacket == null || rtpPacket.readableBytes() < 8) {
            return 0;
        }
        int ri = rtpPacket.readerIndex();
        return rtpPacket.getInt(ri + 4);
    }

    private static void releaseAll(List<ByteBuf> bufs) {
        for (ByteBuf b : bufs) {
            ReferenceCountUtil.safeRelease(b);
        }
    }

    private static ByteBuf wrapAsAnnexbNal(byte[] nalu) {
        ByteBuf out = Unpooled.buffer(START_CODE_LEN + nalu.length);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(1);
        out.writeBytes(nalu);
        return out;
    }

    public static final class AccessUnit {
        private final int timestamp90k;
        private final boolean keyFrame;
        private final boolean hasVcl;
        private final boolean hasSps;
        private final boolean hasPps;
        private final ByteBuf annexB;

        private AccessUnit(
                int timestamp90k,
                boolean keyFrame,
                boolean hasVcl,
                boolean hasSps,
                boolean hasPps,
                ByteBuf annexB) {
            this.timestamp90k = timestamp90k;
            this.keyFrame = keyFrame;
            this.hasVcl = hasVcl;
            this.hasSps = hasSps;
            this.hasPps = hasPps;
            this.annexB = annexB;
        }

        public int timestamp90k() {
            return timestamp90k;
        }

        public boolean keyFrame() {
            return keyFrame;
        }

        public boolean hasVcl() {
            return hasVcl;
        }

        public boolean hasSps() {
            return hasSps;
        }

        public boolean hasPps() {
            return hasPps;
        }

        public ByteBuf annexB() {
            return annexB;
        }

        public void release() {
            ReferenceCountUtil.safeRelease(annexB);
        }
    }
}
