package com.wenting.mediaserver.core.webrtc;

import java.util.Locale;

/**
 * Minimal parsed information from WHEP offer SDP required for DTLS/SRTP bootstrap.
 */
public final class WebRtcOfferInfo {

    private final String iceUfrag;
    private final String icePwd;
    private final String fingerprint;
    private final String setupRole;
    private final int mediaCount;
    private final int audioMediaCount;
    private final int videoMediaCount;
    private final int videoPayloadType;

    private WebRtcOfferInfo(
            String iceUfrag,
            String icePwd,
            String fingerprint,
            String setupRole,
            int mediaCount,
            int audioMediaCount,
            int videoMediaCount,
            int videoPayloadType) {
        this.iceUfrag = iceUfrag;
        this.icePwd = icePwd;
        this.fingerprint = fingerprint;
        this.setupRole = setupRole;
        this.mediaCount = mediaCount;
        this.audioMediaCount = audioMediaCount;
        this.videoMediaCount = videoMediaCount;
        this.videoPayloadType = videoPayloadType;
    }

    public String iceUfrag() {
        return iceUfrag;
    }

    public String icePwd() {
        return icePwd;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String setupRole() {
        return setupRole;
    }

    public int mediaCount() {
        return mediaCount;
    }

    public int audioMediaCount() {
        return audioMediaCount;
    }

    public int videoMediaCount() {
        return videoMediaCount;
    }

    public int videoPayloadType() {
        return videoPayloadType;
    }

    public static WebRtcOfferInfo parse(String offerSdp) {
        if (offerSdp == null || offerSdp.trim().isEmpty()) {
            throw new IllegalArgumentException("empty offer sdp");
        }
        String[] lines = offerSdp.split("\r\n|\n");
        String iceUfrag = null;
        String icePwd = null;
        String fingerprint = null;
        String setup = null;
        int mediaCount = 0;
        int audioCount = 0;
        int videoCount = 0;
        int videoPt = 96;
        boolean inVideoSection = false;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("m=")) {
                mediaCount++;
                String media = line.substring(2).trim().toLowerCase(Locale.ROOT);
                inVideoSection = media.startsWith("video ");
                if (media.startsWith("audio ")) {
                    audioCount++;
                } else if (inVideoSection) {
                    videoCount++;
                    // default to first payload type in video m-line if no H264 found
                    String[] tokens = media.split("\\s+");
                    if (tokens.length >= 4) {
                        try {
                            videoPt = Integer.parseInt(tokens[3]);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
                continue;
            }
            if (inVideoSection && line.startsWith("a=rtpmap:")) {
                String value = line.substring("a=rtpmap:".length()).trim();
                int sp = value.indexOf(' ');
                if (sp > 0) {
                    String ptStr = value.substring(0, sp).trim();
                    String codec = value.substring(sp + 1).trim().toLowerCase(Locale.ROOT);
                    int slash = codec.indexOf('/');
                    if (slash > 0) {
                        codec = codec.substring(0, slash);
                    }
                    if ("h264".equals(codec)) {
                        try {
                            videoPt = Integer.parseInt(ptStr);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
                continue;
            }
            if (iceUfrag == null && line.startsWith("a=ice-ufrag:")) {
                iceUfrag = parseValue(line, "a=ice-ufrag:");
                continue;
            }
            if (icePwd == null && line.startsWith("a=ice-pwd:")) {
                icePwd = parseValue(line, "a=ice-pwd:");
                continue;
            }
            if (fingerprint == null && line.startsWith("a=fingerprint:")) {
                fingerprint = parseValue(line, "a=fingerprint:");
                continue;
            }
            if (setup == null && line.startsWith("a=setup:")) {
                setup = parseValue(line, "a=setup:").toLowerCase(Locale.ROOT);
            }
        }
        if (mediaCount <= 0) {
            throw new IllegalArgumentException("offer has no media sections");
        }
        if (iceUfrag == null || iceUfrag.isEmpty()) {
            throw new IllegalArgumentException("offer missing ice-ufrag");
        }
        if (icePwd == null || icePwd.isEmpty()) {
            throw new IllegalArgumentException("offer missing ice-pwd");
        }
        if (fingerprint == null || fingerprint.isEmpty()) {
            throw new IllegalArgumentException("offer missing dtls fingerprint");
        }
        if (setup == null || setup.isEmpty()) {
            throw new IllegalArgumentException("offer missing setup role");
        }
        if (!"actpass".equals(setup) && !"active".equals(setup) && !"passive".equals(setup)) {
            throw new IllegalArgumentException("offer setup role not supported: " + setup);
        }
        return new WebRtcOfferInfo(iceUfrag, icePwd, fingerprint, setup, mediaCount, audioCount, videoCount, videoPt);
    }

    private static String parseValue(String line, String prefix) {
        String value = line.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("invalid sdp line: " + prefix);
        }
        return value;
    }
}
