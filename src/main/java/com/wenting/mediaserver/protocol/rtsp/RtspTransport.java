package com.wenting.mediaserver.protocol.rtsp;

import java.util.Locale;

/**
 * Parses {@code Transport} headers for TCP interleaved pairs and UDP {@code client_port}.
 */
public final class RtspTransport {

    private RtspTransport() {
    }

    /**
     * @return RTP and RTCP client UDP ports from {@code client_port=a-b}; {@code 0,0} if missing/invalid
     */
    public static int[] parseClientPorts(String transportHeader) {
        if (transportHeader == null) {
            return new int[]{0, 0};
        }
        String[] parts = transportHeader.split(";");
        for (String p : parts) {
            String trimmed = p.trim();
            String s = trimmed.toLowerCase(Locale.ROOT);
            if (s.startsWith("client_port=")) {
                int eq = trimmed.indexOf('=');
                String v = eq >= 0 ? trimmed.substring(eq + 1).trim() : "";
                int dash = v.indexOf('-');
                if (dash > 0) {
                    try {
                        int a = Integer.parseInt(v.substring(0, dash).trim());
                        int b = Integer.parseInt(v.substring(dash + 1).trim());
                        return new int[]{a, b};
                    } catch (NumberFormatException e) {
                        return new int[]{0, 0};
                    }
                }
            }
        }
        return new int[]{0, 0};
    }

    /**
     * @return rtp and rtcp channel numbers, default {@code 0,1} if missing
     */
    public static int[] parseInterleavedPairs(String transportHeader) {
        if (transportHeader == null) {
            return new int[]{0, 1};
        }
        String t = transportHeader.toLowerCase(Locale.ROOT);
        if (!t.contains("tcp")) {
            return new int[]{0, 1};
        }
        String[] parts = transportHeader.split(";");
        for (String p : parts) {
            String trimmed = p.trim();
            String s = trimmed.toLowerCase(Locale.ROOT);
            if (s.startsWith("interleaved=")) {
                int eq = trimmed.indexOf('=');
                String v = eq >= 0 ? trimmed.substring(eq + 1).trim() : "";
                int dash = v.indexOf('-');
                if (dash > 0) {
                    try {
                        int a = Integer.parseInt(v.substring(0, dash).trim());
                        int b = Integer.parseInt(v.substring(dash + 1).trim());
                        return new int[]{a, b};
                    } catch (NumberFormatException e) {
                        return new int[]{0, 1};
                    }
                }
            }
        }
        return new int[]{0, 1};
    }

    public static boolean isTcpTransport(String transportHeader) {
        if (transportHeader == null) {
            return false;
        }
        String t = transportHeader.toUpperCase(Locale.ROOT);
        return t.contains("TCP") && t.contains("RTP/AVP");
    }

    /**
     * Unicast UDP RTP/AVP (not {@code RTP/AVP/TCP}) with {@code client_port=}.
     */
    public static boolean isUdpTransport(String transportHeader) {
        if (transportHeader == null) {
            return false;
        }
        String u = transportHeader.toUpperCase(Locale.ROOT);
        if (!u.contains("RTP/AVP")) {
            return false;
        }
        if (u.contains("TCP")) {
            return false;
        }
        return u.contains("CLIENT_PORT=");
    }
}
