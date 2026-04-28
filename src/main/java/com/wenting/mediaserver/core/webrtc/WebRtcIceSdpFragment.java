package com.wenting.mediaserver.core.webrtc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed ICE SDP fragment from WHEP PATCH (trickle ICE).
 */
public final class WebRtcIceSdpFragment {

    private final String iceUfrag;
    private final String icePwd;
    private final List<String> candidates;
    private final boolean endOfCandidates;

    private WebRtcIceSdpFragment(String iceUfrag, String icePwd, List<String> candidates, boolean endOfCandidates) {
        this.iceUfrag = iceUfrag;
        this.icePwd = icePwd;
        this.candidates = Collections.unmodifiableList(new ArrayList<String>(candidates));
        this.endOfCandidates = endOfCandidates;
    }

    public String iceUfrag() {
        return iceUfrag;
    }

    public String icePwd() {
        return icePwd;
    }

    public List<String> candidates() {
        return candidates;
    }

    public boolean endOfCandidates() {
        return endOfCandidates;
    }

    public static WebRtcIceSdpFragment parse(String sdpFragment) {
        if (sdpFragment == null || sdpFragment.trim().isEmpty()) {
            throw new IllegalArgumentException("empty trickle sdpfrag");
        }
        String ufrag = null;
        String pwd = null;
        boolean end = false;
        boolean recognized = false;
        List<String> candidates = new ArrayList<String>();
        String[] lines = sdpFragment.split("\r\n|\n");
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("a=ice-ufrag:")) {
                ufrag = parseAttrValue(line, "a=ice-ufrag:");
                recognized = true;
                continue;
            }
            if (line.startsWith("a=ice-pwd:")) {
                pwd = parseAttrValue(line, "a=ice-pwd:");
                recognized = true;
                continue;
            }
            if (line.startsWith("a=candidate:")) {
                candidates.add(parseAttrValue(line, "a=candidate:"));
                recognized = true;
                continue;
            }
            if (line.startsWith("candidate:")) {
                candidates.add(parseAttrValue(line, "candidate:"));
                recognized = true;
                continue;
            }
            if ("a=end-of-candidates".equals(line)) {
                end = true;
                recognized = true;
            }
        }
        if (!recognized) {
            throw new IllegalArgumentException("no ice attributes in sdpfrag");
        }
        return new WebRtcIceSdpFragment(ufrag, pwd, candidates, end);
    }

    private static String parseAttrValue(String line, String prefix) {
        String value = line.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("invalid sdpfrag line: " + prefix);
        }
        return value;
    }
}
