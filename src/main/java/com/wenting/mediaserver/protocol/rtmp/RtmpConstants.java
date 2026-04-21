package com.wenting.mediaserver.protocol.rtmp;

public final class RtmpConstants {
    private RtmpConstants() {
    }

    /** Protocol control: set chunk size */
    public static final int TYPE_SET_CHUNK_SIZE = 1;
    /** Protocol control: abort message */
    public static final int TYPE_ABORT = 2;
    /** Protocol control: acknowledgement */
    public static final int TYPE_ACK = 3;
    /** Protocol control: user control message */
    public static final int TYPE_USER_CONTROL = 4;
    /** Protocol control: window acknowledgement size */
    public static final int TYPE_WINDOW_ACK_SIZE = 5;
    /** Protocol control: set peer bandwidth */
    public static final int TYPE_SET_PEER_BANDWIDTH = 6;

    /** Audio message */
    public static final int TYPE_AUDIO = 8;
    /** Video message */
    public static final int TYPE_VIDEO = 9;

    /** Data message (AMF0) */
    public static final int TYPE_DATA_AMF0 = 18;
    /** Shared object message (AMF0) */
    public static final int TYPE_SHARED_OBJ_AMF0 = 19;
    /** Command message (AMF0) */
    public static final int TYPE_COMMAND_AMF0 = 20;

    /** Chunk stream ID for protocol control messages */
    public static final int CSID_PROTOCOL = 2;
    /** Chunk stream ID for command messages */
    public static final int CSID_COMMAND = 3;
    /** Chunk stream ID for audio messages */
    public static final int CSID_AUDIO = 4;
    /** Chunk stream ID for video messages */
    public static final int CSID_VIDEO = 6;
}
