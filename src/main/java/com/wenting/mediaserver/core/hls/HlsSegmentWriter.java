package com.wenting.mediaserver.core.hls;

import io.netty.buffer.ByteBuf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class HlsSegmentWriter {
    private final int sequence;
    private final String fileName;
    private final Path path;
    private final OutputStream out;
    private final HlsTsMuxer muxer;
    private boolean closed;

    HlsSegmentWriter(int sequence, String fileName, Path path, HlsAacConfig aacConfig) {
        this.sequence = sequence;
        this.fileName = fileName;
        this.path = path;
        this.muxer = new HlsTsMuxer(aacConfig);
        try {
            this.out = new BufferedOutputStream(Files.newOutputStream(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE));
            muxer.writePatPmt(out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open HLS segment file " + path, e);
        }
    }

    int sequence() {
        return sequence;
    }

    String fileName() {
        return fileName;
    }

    Path path() {
        return path;
    }

    void writeVideoAccessUnit(ByteBuf annexbAccessUnit, int pts90k) {
        if (closed || annexbAccessUnit == null || !annexbAccessUnit.isReadable()) {
            return;
        }
        byte[] accessUnit = new byte[annexbAccessUnit.readableBytes()];
        annexbAccessUnit.getBytes(annexbAccessUnit.readerIndex(), accessUnit);
        writeVideoAccessUnit(accessUnit, pts90k);
    }

    void writeVideoAccessUnit(byte[] annexbAccessUnit, int pts90k) {
        if (closed || annexbAccessUnit == null || annexbAccessUnit.length == 0) {
            return;
        }
        try {
            muxer.writeH264AccessUnit(out, annexbAccessUnit, pts90k & 0xFFFFFFFFL);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write HLS segment " + path, e);
        }
    }

    void writeAudioFrame(byte[] rawAac, int pts90k) {
        if (closed || rawAac == null || rawAac.length == 0) {
            return;
        }
        try {
            muxer.writeAacFrame(out, rawAac, pts90k & 0xFFFFFFFFL);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write HLS audio into segment " + path, e);
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.flush();
        } catch (IOException ignore) {
            // ignore
        }
        try {
            out.close();
        } catch (IOException ignore) {
            // ignore
        }
    }
}
