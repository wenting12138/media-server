package com.wenting.mediaserver.protocol.rtsp;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed RTSP request (single TCP message: headers + optional body).
 */
public final class RtspRequestMessage {

    private static final int BODY_STRING_PREVIEW_MAX_BYTES = 8192;

    private final String method;
    private final String uri;
    private final String version;
    private final Map<String, String> headers;
    private final ByteBuf body;

    public RtspRequestMessage(
            String method,
            String uri,
            String version,
            Map<String, String> headers,
            ByteBuf body) {
        this.method = method;
        this.uri = uri;
        this.version = version;
        this.headers = headers;
        this.body = body;
    }

    public String method() {
        return method;
    }

    public String uri() {
        return uri;
    }

    public String version() {
        return version;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public String header(String name) {
        if (name == null) {
            return null;
        }
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public ByteBuf body() {
        return body;
    }

    public int cSeq() {
        String v = header("CSeq");
        if (v == null) {
            return -1;
        }
        int sp = v.indexOf(' ');
        String num = sp >= 0 ? v.substring(0, sp).trim() : v.trim();
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("RtspRequestMessage{method=").append(method);
        sb.append(", uri=").append(uri);
        sb.append(", version=").append(version);
        sb.append(", cSeq=").append(cSeq());
        sb.append(", headers=").append(headers);
        if (body == null) {
            sb.append(", body=null}");
            return sb.toString();
        }
        int len = body.readableBytes();
        sb.append(", bodyBytes=").append(len);
        if (len > 0) {
            int previewBytes = Math.min(len, BODY_STRING_PREVIEW_MAX_BYTES);
            String raw = body.toString(body.readerIndex(), previewBytes, StandardCharsets.UTF_8);
            sb.append(", bodyPreview=").append(escapeBodyForLog(raw));
            if (previewBytes < len) {
                sb.append("...(truncated)");
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeBodyForLog(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\r':
                    out.append("\\r");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    /**
     * Parses one RTSP message from {@code message} (header lines + blank line + body if any).
     * Takes ownership of {@code message} and releases it after copying body slice.
     */
    public static RtspRequestMessage parse(ByteBuf message) {
        try {
            int hdrEnd = indexOfDoubleCrlf(message, message.readerIndex(), message.writerIndex());
            if (hdrEnd < 0) {
                throw new IllegalArgumentException("no end of headers");
            }
            int bodyStart = hdrEnd + 4;
            // Include the CRLF that terminates the last header line (hdrEnd points at the first \r of \r\n\r\n).
            int headerEndExclusive = hdrEnd + 2;
            ByteBuf headerSlice = message.slice(message.readerIndex(), headerEndExclusive - message.readerIndex());

            String first = readLine(headerSlice);
            if (first == null || first.isEmpty()) {
                throw new IllegalArgumentException("empty request line");
            }
            String[] parts = first.split(" ", 3);
            if (parts.length < 2) {
                throw new IllegalArgumentException("bad request line");
            }
            String method = parts[0];
            String uri = parts[1];
            String ver = parts.length > 2 ? parts[2] : "RTSP/1.0";

            Map<String, String> headers = new LinkedHashMap<String, String>();
            String line;
            while ((line = readLine(headerSlice)) != null) {
                if (line.isEmpty()) {
                    break;
                }
                int c = line.indexOf(':');
                if (c > 0) {
                    String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(c + 1).trim();
                    headers.put(k, v);
                }
            }

            int contentLen = 0;
            String cl = headers.get("content-length");
            if (cl != null) {
                try {
                    contentLen = Integer.parseInt(cl.trim());
                } catch (NumberFormatException ignored) {
                    contentLen = 0;
                }
            }

            int total = message.writerIndex() - message.readerIndex();
            int expectedEnd = bodyStart - message.readerIndex() + contentLen;
            if (total < expectedEnd) {
                throw new IllegalArgumentException("short body");
            }

            ByteBuf body;
            if (contentLen > 0) {
                body = message.slice(bodyStart, contentLen).retain();
            } else {
                body = io.netty.buffer.Unpooled.EMPTY_BUFFER;
            }
            return new RtspRequestMessage(method, uri, ver, Collections.unmodifiableMap(headers), body);
        } finally {
            message.release();
        }
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

    private static String readLine(ByteBuf buf) {
        if (!buf.isReadable()) {
            return null;
        }
        int start = buf.readerIndex();
        while (buf.isReadable()) {
            byte b = buf.readByte();
            if (b == '\n') {
                int end = buf.readerIndex() - 1;
                int len = end - start;
                if (len > 0 && buf.getByte(end - 1) == '\r') {
                    len--;
                }
                return buf.toString(start, len, io.netty.util.CharsetUtil.US_ASCII);
            }
        }
        return null;
    }
}
