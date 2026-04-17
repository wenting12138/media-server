package com.wenting.mediaserver.protocol.rtsp.sdp;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;

import java.util.Locale;

/**
 * Minimal SDP parser: finds first video m= line and matching rtpmap/fmtp for H264.
 */
public final class SdpParser {

    private SdpParser() {
    }

    public static SdpMedia findFirstH264Video(ByteBuf sdpBody) {
        if (sdpBody == null || !sdpBody.isReadable()) {
            return null;
        }
        String sdp = sdpBody.toString(CharsetUtil.UTF_8);
        String[] lines = sdp.split("\r\n|\n");
        int payload = -1;
        String fmtp = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("m=video ")) {
                String[] parts = line.split(" ");
                if (parts.length >= 4) {
                    try {
                        payload = Integer.parseInt(parts[3].trim());
                    } catch (NumberFormatException e) {
                        payload = -1;
                    }
                }
                String rtpmap = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String l = lines[j];
                    if (l.startsWith("m=")) {
                        break;
                    }
                    String lower = l.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("a=rtpmap:")) {
                        String rest = l.substring("a=rtpmap:".length());
                        int sp = rest.indexOf(' ');
                        if (sp > 0) {
                            String ptStr = rest.substring(0, sp).trim();
                            try {
                                int pt = Integer.parseInt(ptStr);
                                if (pt == payload) {
                                    rtpmap = rest.substring(sp + 1).trim();
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    } else if (lower.startsWith("a=fmtp:")) {
                        String rest = l.substring("a=fmtp:".length());
                        int sp = rest.indexOf(' ');
                        if (sp > 0) {
                            String ptStr = rest.substring(0, sp).trim();
                            try {
                                int pt = Integer.parseInt(ptStr);
                                if (pt == payload) {
                                    fmtp = rest.substring(sp + 1).trim();
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
                if (payload >= 0 && rtpmap != null && rtpmap.toUpperCase(Locale.ROOT).startsWith("H264/")) {
                    return new SdpMedia(payload, rtpmap, fmtp);
                }
                return null;
            }
        }
        return null;
    }

    public static boolean containsH264Video(ByteBuf sdpBody) {
        return findFirstH264Video(sdpBody) != null;
    }
}
