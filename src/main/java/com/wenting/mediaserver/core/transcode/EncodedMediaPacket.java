package com.wenting.mediaserver.core.transcode;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

/**
 * Protocol-agnostic encoded media packet for transcode plugins.
 */
public final class EncodedMediaPacket {

    public enum SourceProtocol {
        RTMP,
        RTSP
    }

    public enum TrackType {
        VIDEO,
        AUDIO,
        DATA
    }

    public enum CodecType {
        H264,
        AAC,
        UNKNOWN
    }

    public enum PayloadFormat {
        RTMP_TAG,
        RTP_PACKET
    }

    private final SourceProtocol sourceProtocol;
    private final TrackType trackType;
    private final CodecType codecType;
    private final PayloadFormat payloadFormat;
    private final int timestamp;
    private final int messageStreamId;
    private final ByteBuf payload;

    public EncodedMediaPacket(
            SourceProtocol sourceProtocol,
            TrackType trackType,
            CodecType codecType,
            PayloadFormat payloadFormat,
            int timestamp,
            int messageStreamId,
            ByteBuf payload) {
        this.sourceProtocol = sourceProtocol;
        this.trackType = trackType;
        this.codecType = codecType;
        this.payloadFormat = payloadFormat;
        this.timestamp = timestamp;
        this.messageStreamId = messageStreamId;
        this.payload = payload;
    }

    public SourceProtocol sourceProtocol() {
        return sourceProtocol;
    }

    public TrackType trackType() {
        return trackType;
    }

    public CodecType codecType() {
        return codecType;
    }

    public PayloadFormat payloadFormat() {
        return payloadFormat;
    }

    public int timestamp() {
        return timestamp;
    }

    public int messageStreamId() {
        return messageStreamId;
    }

    public ByteBuf payload() {
        return payload;
    }

    public void release() {
        ReferenceCountUtil.safeRelease(payload);
    }
}
