package com.wenting.mediaserver.protocol.rtmp;

import io.netty.buffer.ByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;

final class RtmpAmf0 {
    private static final int NUMBER = 0x00;
    private static final int BOOLEAN = 0x01;
    private static final int STRING = 0x02;
    private static final int OBJECT = 0x03;
    private static final int NULL = 0x05;
    private static final int OBJECT_END = 0x09;

    private RtmpAmf0() {
    }

    static Object readValue(ByteBuf in) {
        if (!in.isReadable()) {
            return null;
        }
        int type = in.readUnsignedByte();
        switch (type) {
            case NUMBER:
                return in.readDouble();
            case BOOLEAN:
                return in.readUnsignedByte() != 0;
            case STRING:
                return readStringRaw(in);
            case OBJECT:
                Map<String, Object> obj = new LinkedHashMap<String, Object>();
                while (in.readableBytes() >= 3) {
                    int keyLen = in.readUnsignedShort();
                    if (keyLen == 0) {
                        int end = in.readUnsignedByte();
                        if (end == OBJECT_END) {
                            break;
                        }
                        return obj;
                    }
                    byte[] k = new byte[keyLen];
                    in.readBytes(k);
                    String key = new String(k);
                    obj.put(key, readValue(in));
                }
                return obj;
            case NULL:
                return null;
            default:
                return null;
        }
    }

    static String readString(ByteBuf in) {
        Object v = readValue(in);
        return v instanceof String ? (String) v : null;
    }

    static Double readNumber(ByteBuf in) {
        Object v = readValue(in);
        return v instanceof Double ? (Double) v : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readObject(ByteBuf in) {
        Object v = readValue(in);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    static void writeString(ByteBuf out, String s) {
        out.writeByte(STRING);
        byte[] b = s == null ? new byte[0] : s.getBytes();
        out.writeShort(b.length);
        out.writeBytes(b);
    }

    static void writeNumber(ByteBuf out, double v) {
        out.writeByte(NUMBER);
        out.writeDouble(v);
    }

    static void writeNull(ByteBuf out) {
        out.writeByte(NULL);
    }

    static void writeObject(ByteBuf out, Map<String, Object> obj) {
        out.writeByte(OBJECT);
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            byte[] key = e.getKey().getBytes();
            out.writeShort(key.length);
            out.writeBytes(key);
            writeAny(out, e.getValue());
        }
        out.writeShort(0);
        out.writeByte(OBJECT_END);
    }

    private static void writeAny(ByteBuf out, Object v) {
        if (v == null) {
            writeNull(out);
        } else if (v instanceof Number) {
            writeNumber(out, ((Number) v).doubleValue());
        } else if (v instanceof Boolean) {
            out.writeByte(BOOLEAN);
            out.writeByte(((Boolean) v).booleanValue() ? 1 : 0);
        } else if (v instanceof String) {
            writeString(out, (String) v);
        } else if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            writeObject(out, m);
        } else {
            writeNull(out);
        }
    }

    private static String readStringRaw(ByteBuf in) {
        int len = in.readUnsignedShort();
        byte[] b = new byte[len];
        in.readBytes(b);
        return new String(b);
    }
}
