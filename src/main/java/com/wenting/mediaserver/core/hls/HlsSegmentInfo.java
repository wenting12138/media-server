package com.wenting.mediaserver.core.hls;

import java.nio.file.Path;

final class HlsSegmentInfo {
    private final int sequence;
    private final String fileName;
    private final Path path;
    private final double durationSec;

    HlsSegmentInfo(int sequence, String fileName, Path path, double durationSec) {
        this.sequence = sequence;
        this.fileName = fileName;
        this.path = path;
        this.durationSec = durationSec;
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

    double durationSec() {
        return durationSec;
    }
}
