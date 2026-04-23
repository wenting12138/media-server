package com.wenting.mediaserver.protocol.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RtmpChunkDecoder extends ByteToMessageDecoder {
    private static final int TIMESTAMP_EXTENDED_MARKER = 0xFFFFFF;
    private static final int MAX_MESSAGE_LENGTH = 8 * 1024 * 1024;
    private final Map<Integer, ChunkState> chunkStates = new HashMap<Integer, ChunkState>();
    private int inChunkSize = 128;

    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            in.markReaderIndex();
            if (in.readableBytes() < 1) {
                in.resetReaderIndex();
                return;
            }

            BasicHeader basicHeader = readBasicHeader(in);
            if (basicHeader == null) {
                in.resetReaderIndex();
                return;
            }

            ChunkState state = chunkStates.get(Integer.valueOf(basicHeader.csid));
            HeaderSnapshot header = parseMessageHeader(ctx, in, basicHeader.fmt, state);
            if (header == HeaderSnapshot.NEED_MORE_DATA) {
                in.resetReaderIndex();
                return;
            }
            if (header == HeaderSnapshot.PROTOCOL_ERROR) {
                ctx.close();
                return;
            }

            if (state == null) {
                state = new ChunkState();
                chunkStates.put(Integer.valueOf(basicHeader.csid), state);
            }

            boolean continuation = state.payload != null;
            int remaining = continuation ? (state.messageLength - state.payload.readableBytes()) : header.messageLength;
            if (remaining < 0) {
                ctx.close();
                return;
            }
            int toRead = Math.min(remaining, inChunkSize);
            if (in.readableBytes() < toRead) {
                in.resetReaderIndex();
                return;
            }

            if (!continuation) {
                if (header.messageLength < 0 || header.messageLength > MAX_MESSAGE_LENGTH) {
                    ctx.close();
                    return;
                }
                state.timestamp = header.timestamp;
                state.timestampDelta = header.timestampDelta;
                state.messageLength = header.messageLength;
                state.typeId = header.typeId;
                state.messageStreamId = header.messageStreamId;
                state.extendedTimestamp = header.extendedTimestamp;
                state.payload = Unpooled.buffer(state.messageLength);
            }

            state.payload.writeBytes(in, toRead);
            if (state.payload.readableBytes() >= state.messageLength) {
                ByteBuf full = state.payload;
                state.payload = null;
                if (state.typeId == RtmpConstants.TYPE_SET_CHUNK_SIZE && full.readableBytes() >= 4) {
                    long chunkSize = full.readUnsignedInt();
                    inChunkSize = (int) Math.max(128, Math.min(MAX_MESSAGE_LENGTH, chunkSize));
                    full.release();
                } else {
                    out.add(new RtmpMessage(state.typeId, state.timestamp, state.messageStreamId, full));
                }
            }
        }
    }

    private HeaderSnapshot parseMessageHeader(
            io.netty.channel.ChannelHandlerContext ctx,
            ByteBuf in,
            int fmt,
            ChunkState state) {
        if (fmt == 0) {
            if (in.readableBytes() < 11) {
                return HeaderSnapshot.NEED_MORE_DATA;
            }
            int timestampField = read24(in);
            int messageLength = read24(in);
            int typeId = in.readUnsignedByte();
            int messageStreamId = readLittleEndianInt(in);
            boolean extendedTimestamp = timestampField == TIMESTAMP_EXTENDED_MARKER;
            int timestamp = timestampField;
            if (extendedTimestamp) {
                if (in.readableBytes() < 4) {
                    return HeaderSnapshot.NEED_MORE_DATA;
                }
                timestamp = in.readInt();
            }
            return new HeaderSnapshot(timestamp, 0, messageLength, typeId, messageStreamId, extendedTimestamp);
        }

        if (state == null) {
            return HeaderSnapshot.PROTOCOL_ERROR;
        }

        if (fmt == 1) {
            if (in.readableBytes() < 7) {
                return HeaderSnapshot.NEED_MORE_DATA;
            }
            int deltaField = read24(in);
            int messageLength = read24(in);
            int typeId = in.readUnsignedByte();
            boolean extendedTimestamp = deltaField == TIMESTAMP_EXTENDED_MARKER;
            int delta = deltaField;
            if (extendedTimestamp) {
                if (in.readableBytes() < 4) {
                    return HeaderSnapshot.NEED_MORE_DATA;
                }
                delta = in.readInt();
            }
            int timestamp = state.timestamp + delta;
            return new HeaderSnapshot(timestamp, delta, messageLength, typeId, state.messageStreamId, extendedTimestamp);
        }

        if (fmt == 2) {
            if (in.readableBytes() < 3) {
                return HeaderSnapshot.NEED_MORE_DATA;
            }
            int deltaField = read24(in);
            boolean extendedTimestamp = deltaField == TIMESTAMP_EXTENDED_MARKER;
            int delta = deltaField;
            if (extendedTimestamp) {
                if (in.readableBytes() < 4) {
                    return HeaderSnapshot.NEED_MORE_DATA;
                }
                delta = in.readInt();
            }
            int timestamp = state.timestamp + delta;
            return new HeaderSnapshot(timestamp, delta, state.messageLength, state.typeId, state.messageStreamId, extendedTimestamp);
        }

        // fmt == 3
        if (state.extendedTimestamp) {
            if (in.readableBytes() < 4) {
                return HeaderSnapshot.NEED_MORE_DATA;
            }
            in.skipBytes(4);
        }
        if (state.payload != null) {
            return new HeaderSnapshot(state.timestamp, state.timestampDelta, state.messageLength, state.typeId, state.messageStreamId, state.extendedTimestamp);
        }
        int timestamp = state.timestamp + state.timestampDelta;
        return new HeaderSnapshot(timestamp, state.timestampDelta, state.messageLength, state.typeId, state.messageStreamId, state.extendedTimestamp);
    }

    private BasicHeader readBasicHeader(ByteBuf in) {
        if (in.readableBytes() < 1) {
            return null;
        }
        int b0 = in.readUnsignedByte();
        int fmt = (b0 >> 6) & 0x03;
        int csidLow = b0 & 0x3F;
        if (csidLow >= 2) {
            return new BasicHeader(fmt, csidLow);
        }
        if (csidLow == 0) {
            if (in.readableBytes() < 1) {
                return null;
            }
            int ext = in.readUnsignedByte();
            return new BasicHeader(fmt, ext + 64);
        }
        if (in.readableBytes() < 2) {
            return null;
        }
        int ext0 = in.readUnsignedByte();
        int ext1 = in.readUnsignedByte();
        return new BasicHeader(fmt, (ext1 * 256) + ext0 + 64);
    }

    static int read24(ByteBuf in) {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return (b1 << 16) | (b2 << 8) | b3;
    }

    static void write24(ByteBuf out, int value) {
        out.writeByte((value >> 16) & 0xFF);
        out.writeByte((value >> 8) & 0xFF);
        out.writeByte(value & 0xFF);
    }

    static int readLittleEndianInt(ByteBuf in) {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        int b4 = in.readUnsignedByte();
        return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
    }

    static void writeLittleEndianInt(ByteBuf out, int v) {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
        out.writeByte((v >> 16) & 0xFF);
        out.writeByte((v >> 24) & 0xFF);
    }

    private static final class ChunkState {
        private int timestamp;
        private int timestampDelta;
        private int typeId;
        private int messageLength;
        private int messageStreamId;
        private boolean extendedTimestamp;
        private ByteBuf payload;
    }

    private static final class BasicHeader {
        private final int fmt;
        private final int csid;

        private BasicHeader(int fmt, int csid) {
            this.fmt = fmt;
            this.csid = csid;
        }
    }

    private static final class HeaderSnapshot {
        private static final HeaderSnapshot NEED_MORE_DATA = new HeaderSnapshot(0, 0, 0, 0, 0, false);
        private static final HeaderSnapshot PROTOCOL_ERROR = new HeaderSnapshot(0, 0, 0, 0, 0, false);

        private final int timestamp;
        private final int timestampDelta;
        private final int messageLength;
        private final int typeId;
        private final int messageStreamId;
        private final boolean extendedTimestamp;

        private HeaderSnapshot(
                int timestamp,
                int timestampDelta,
                int messageLength,
                int typeId,
                int messageStreamId,
                boolean extendedTimestamp) {
            this.timestamp = timestamp;
            this.timestampDelta = timestampDelta;
            this.messageLength = messageLength;
            this.typeId = typeId;
            this.messageStreamId = messageStreamId;
            this.extendedTimestamp = extendedTimestamp;
        }
    }
}
