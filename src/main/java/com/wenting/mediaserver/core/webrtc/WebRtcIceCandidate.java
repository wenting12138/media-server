package com.wenting.mediaserver.core.webrtc;

import java.util.Locale;

/**
 * Parsed ICE candidate from SDP a=candidate line body.
 */
public final class WebRtcIceCandidate {

    private final String foundation;
    private final int component;
    private final String transport;
    private final long priority;
    private final String address;
    private final int port;
    private final String type;

    private WebRtcIceCandidate(
            String foundation,
            int component,
            String transport,
            long priority,
            String address,
            int port,
            String type) {
        this.foundation = foundation;
        this.component = component;
        this.transport = transport;
        this.priority = priority;
        this.address = address;
        this.port = port;
        this.type = type;
    }

    public String foundation() {
        return foundation;
    }

    public int component() {
        return component;
    }

    public String transport() {
        return transport;
    }

    public long priority() {
        return priority;
    }

    public String address() {
        return address;
    }

    public int port() {
        return port;
    }

    public String type() {
        return type;
    }

    public boolean isUdp() {
        return "udp".equalsIgnoreCase(transport);
    }

    public boolean isRtpComponent() {
        return component == 1;
    }

    public static WebRtcIceCandidate parse(String candidateBody) {
        if (candidateBody == null || candidateBody.trim().isEmpty()) {
            throw new IllegalArgumentException("empty candidate");
        }
        String raw = candidateBody.trim();
        if (raw.toLowerCase(Locale.ROOT).startsWith("candidate:")) {
            raw = raw.substring("candidate:".length()).trim();
        }
        String[] parts = raw.split("\\s+");
        if (parts.length < 8) {
            throw new IllegalArgumentException("candidate fields not enough");
        }
        String foundation = parts[0];
        int component = parseInt(parts[1], "component");
        String transport = parts[2].toLowerCase(Locale.ROOT);
        long priority = parseLong(parts[3], "priority");
        String address = parts[4];
        int port = parseInt(parts[5], "port");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid candidate port");
        }
        String type = null;
        for (int i = 6; i + 1 < parts.length; i++) {
            if ("typ".equalsIgnoreCase(parts[i])) {
                type = parts[i + 1].toLowerCase(Locale.ROOT);
                break;
            }
        }
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("missing candidate type");
        }
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("missing candidate address");
        }
        return new WebRtcIceCandidate(foundation, component, transport, priority, address.trim(), port, type);
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid candidate " + name);
        }
    }

    private static long parseLong(String raw, String name) {
        try {
            return Long.parseLong(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid candidate " + name);
        }
    }
}
