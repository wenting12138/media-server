package com.wenting.mediaserver.core.webrtc;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * Minimal SDP answer generator for WHEP signaling bootstrap.
 *
 * <p>This class only implements a conservative subset sufficient for phase-2:
 * accept client offer, return a valid SDP answer and session resource.</p>
 */
public final class WebRtcSdpAnswerGenerator {

    private static final String CRLF = "\r\n";
    private static final String DEFAULT_ICE_UFRAG = "msrv";
    private static final String DEFAULT_ICE_PWD = "msrvmsrvmsrvmsrvmsrvms";
    private static final String DEFAULT_CANDIDATE_IP = "127.0.0.1";
    private static final String DEFAULT_FINGERPRINT =
            "sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:"
                    + "10:20:30:40:50:60:70:80:90:A0:B0:C0:D0:E0:F0:01";
    private final String localFingerprint;

    public WebRtcSdpAnswerGenerator() {
        this(null);
    }

    public WebRtcSdpAnswerGenerator(String localFingerprint) {
        this.localFingerprint = normalizeFingerprint(localFingerprint);
    }

    public String createAnswer(String offerSdp, String sessionId, String candidateIp) {
        return createAnswer(offerSdp, sessionId, candidateIp, 9);
    }

    public String createAnswer(String offerSdp, String sessionId, String candidateIp, int candidatePort) {
        if (offerSdp == null || offerSdp.trim().isEmpty()) {
            throw new IllegalArgumentException("offer sdp is empty");
        }
        Offer offer = parseOffer(offerSdp);
        if (offer.mediaSections.isEmpty()) {
            throw new IllegalArgumentException("offer has no media sections");
        }

        String answerIceUfrag = randomToken(sessionId, 8);
        String answerIcePwd = randomToken(sessionId + "-pwd", 24);
        String answerFingerprint = resolveFingerprint(sessionId);
        String ip = sanitizeCandidateIp(candidateIp);
        int port = sanitizeCandidatePort(candidatePort);

        List<MediaAnswer> answers = new ArrayList<MediaAnswer>();
        int autoMid = 0;
        for (MediaSection media : offer.mediaSections) {
            MediaAnswer answer = new MediaAnswer();
            answer.kind = media.kind;
            answer.proto = media.proto == null || media.proto.isEmpty() ? "UDP/TLS/RTP/SAVPF" : media.proto;
            answer.mid = media.mid == null || media.mid.isEmpty() ? String.valueOf(autoMid++) : media.mid;
            answer.acceptedPayloads = selectPayloads(media);
            answer.rejected = answer.acceptedPayloads.isEmpty() || !isRtpMedia(media.kind);
            answer.direction = mapDirection(media.direction);
            if (answer.rejected) {
                answer.direction = "inactive";
            }
            answer.rtpmap = filterByPayload(media.rtpmap, answer.acceptedPayloads);
            answer.fmtp = filterByPayload(media.fmtp, answer.acceptedPayloads);
            answer.rtcpFb = filterByPayload(media.rtcpFb, answer.acceptedPayloads);
            answer.hasRtcpMux = media.hasRtcpMux;
            answer.hasRtcpRsize = media.hasRtcpRsize;
            answers.add(answer);
        }

        StringBuilder sdp = new StringBuilder(1024);
        sdp.append("v=0").append(CRLF);
        sdp.append("o=- ").append(sessionOriginId(sessionId)).append(" 2 IN IP4 ").append(ip).append(CRLF);
        sdp.append("s=-").append(CRLF);
        sdp.append("t=0 0").append(CRLF);
        sdp.append("a=ice-lite").append(CRLF);

        String bundle = bundleMids(answers);
        if (!bundle.isEmpty()) {
            sdp.append("a=group:BUNDLE ").append(bundle).append(CRLF);
        }
        sdp.append("a=msid-semantic: WMS *").append(CRLF);

        for (MediaAnswer answer : answers) {
            if (answer.rejected) {
                sdp.append("m=").append(answer.kind).append(" 0 ").append(answer.proto).append(" 0").append(CRLF);
                sdp.append("c=IN IP4 0.0.0.0").append(CRLF);
                sdp.append("a=mid:").append(answer.mid).append(CRLF);
                sdp.append("a=inactive").append(CRLF);
                continue;
            }

            sdp.append("m=").append(answer.kind).append(" 9 ").append(answer.proto).append(' ');
            appendPayloadList(sdp, answer.acceptedPayloads);
            sdp.append(CRLF);
            sdp.append("c=IN IP4 0.0.0.0").append(CRLF);
            sdp.append("a=mid:").append(answer.mid).append(CRLF);
            sdp.append("a=").append(answer.direction).append(CRLF);
            sdp.append("a=ice-ufrag:").append(answerIceUfrag).append(CRLF);
            sdp.append("a=ice-pwd:").append(answerIcePwd).append(CRLF);
            sdp.append("a=fingerprint:").append(answerFingerprint).append(CRLF);
            sdp.append("a=setup:passive").append(CRLF);
            if (answer.hasRtcpMux) {
                sdp.append("a=rtcp-mux").append(CRLF);
            }
            if (answer.hasRtcpRsize) {
                sdp.append("a=rtcp-rsize").append(CRLF);
            }

            appendAttributeValues(sdp, "a=rtpmap:", answer.rtpmap, answer.acceptedPayloads);
            appendAttributeValues(sdp, "a=fmtp:", answer.fmtp, answer.acceptedPayloads);
            appendAttributeValues(sdp, "a=rtcp-fb:", answer.rtcpFb, answer.acceptedPayloads);

            if ("video".equals(answer.kind)) {
                int ssrc = 0x12345678;
                sdp.append("a=ssrc:").append(ssrc).append(" cname:media-server").append(CRLF);
                sdp.append("a=ssrc:").append(ssrc).append(" msid:WMS0 video0").append(CRLF);
                sdp.append("a=msid:WMS0 video0").append(CRLF);
            }

            sdp.append("a=candidate:1 1 udp 2130706431 ").append(ip).append(' ').append(port).append(" typ host").append(CRLF);
            sdp.append("a=end-of-candidates").append(CRLF);
        }
        return sdp.toString();
    }

    private static Offer parseOffer(String rawSdp) {
        Offer offer = new Offer();
        String[] lines = rawSdp.split("\r\n|\n");
        MediaSection current = null;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("m=")) {
                current = parseMediaLine(line.substring(2));
                offer.mediaSections.add(current);
                continue;
            }
            if (current == null) {
                parseCommonAttributes(offer, line);
                continue;
            }
            parseMediaAttributes(current, line);
        }
        return offer;
    }

    private static void parseCommonAttributes(Offer offer, String line) {
        if (line.startsWith("a=fingerprint:")) {
            offer.fingerprint = line.substring("a=fingerprint:".length()).trim();
        }
    }

    private static void parseMediaAttributes(MediaSection section, String line) {
        if (line.startsWith("a=mid:")) {
            section.mid = line.substring("a=mid:".length()).trim();
            return;
        }
        if ("a=sendrecv".equals(line) || "a=sendonly".equals(line) || "a=recvonly".equals(line) || "a=inactive".equals(line)) {
            section.direction = line.substring(2);
            return;
        }
        if ("a=rtcp-mux".equals(line)) {
            section.hasRtcpMux = true;
            return;
        }
        if ("a=rtcp-rsize".equals(line)) {
            section.hasRtcpRsize = true;
            return;
        }
        if (line.startsWith("a=fingerprint:")) {
            section.fingerprint = line.substring("a=fingerprint:".length()).trim();
            return;
        }
        if (line.startsWith("a=rtpmap:")) {
            parsePayloadAttribute(section.rtpmap, line.substring("a=rtpmap:".length()));
            return;
        }
        if (line.startsWith("a=fmtp:")) {
            parsePayloadAttribute(section.fmtp, line.substring("a=fmtp:".length()));
            return;
        }
        if (line.startsWith("a=rtcp-fb:")) {
            parsePayloadAttribute(section.rtcpFb, line.substring("a=rtcp-fb:".length()));
        }
    }

    private static MediaSection parseMediaLine(String value) {
        MediaSection section = new MediaSection();
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length >= 1) {
            section.kind = tokens[0].toLowerCase(Locale.ROOT);
        }
        if (tokens.length >= 3) {
            section.proto = tokens[2];
        }
        for (int i = 3; i < tokens.length; i++) {
            section.payloads.add(tokens[i]);
        }
        return section;
    }

    private static void parsePayloadAttribute(Map<String, String> map, String raw) {
        if (raw == null) {
            return;
        }
        String v = raw.trim();
        int sp = v.indexOf(' ');
        if (sp <= 0) {
            return;
        }
        String pt = v.substring(0, sp).trim();
        String body = v.substring(sp + 1).trim();
        if (pt.isEmpty() || body.isEmpty()) {
            return;
        }
        map.put(pt, body);
    }

    private static List<String> selectPayloads(MediaSection media) {
        List<String> selected = new ArrayList<String>();
        if (media.payloads.isEmpty()) {
            return selected;
        }
        if ("video".equals(media.kind)) {
            addVideoPayloads(media, selected);
        } else if ("audio".equals(media.kind)) {
            addAudioPayloads(media, selected);
        }
        if (selected.isEmpty() && isRtpMedia(media.kind)) {
            selected.add(media.payloads.get(0));
        }
        return selected;
    }

    private static void addVideoPayloads(MediaSection media, List<String> selected) {
        for (String pt : media.payloads) {
            String codec = codecName(media.rtpmap.get(pt));
            if ("h264".equals(codec)) {
                selected.add(pt);
            }
        }
        if (selected.isEmpty()) {
            return;
        }
        for (String pt : media.payloads) {
            String codec = codecName(media.rtpmap.get(pt));
            if (!"rtx".equals(codec)) {
                continue;
            }
            String fmtp = media.fmtp.get(pt);
            if (fmtp == null) {
                continue;
            }
            String apt = parseFmtpValue(fmtp, "apt");
            if (apt != null && selected.contains(apt)) {
                selected.add(pt);
            }
        }
    }

    private static void addAudioPayloads(MediaSection media, List<String> selected) {
        String[] preferred = new String[]{"opus", "mpeg4-generic", "aac", "pcmu", "pcma"};
        for (String codec : preferred) {
            String match = findPayloadByCodec(media, codec);
            if (match != null) {
                selected.add(match);
                return;
            }
        }
    }

    private static String findPayloadByCodec(MediaSection media, String codec) {
        for (String pt : media.payloads) {
            if (codec.equals(codecName(media.rtpmap.get(pt)))) {
                return pt;
            }
        }
        return null;
    }

    private static String parseFmtpValue(String fmtp, String key) {
        if (fmtp == null || key == null) {
            return null;
        }
        String[] fields = fmtp.split(";");
        for (String raw : fields) {
            String v = raw.trim();
            int eq = v.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (key.equalsIgnoreCase(v.substring(0, eq).trim())) {
                return v.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static String codecName(String rtpmapValue) {
        if (rtpmapValue == null || rtpmapValue.isEmpty()) {
            return "";
        }
        int slash = rtpmapValue.indexOf('/');
        String codec = slash > 0 ? rtpmapValue.substring(0, slash) : rtpmapValue;
        return codec.trim().toLowerCase(Locale.ROOT);
    }

    private static String mapDirection(String offerDirection) {
        if ("sendonly".equals(offerDirection)) {
            return "recvonly";
        }
        if ("inactive".equals(offerDirection)) {
            return "inactive";
        }
        return "sendonly";
    }

    private static boolean isRtpMedia(String kind) {
        return "audio".equals(kind) || "video".equals(kind);
    }

    private static Map<String, String> filterByPayload(Map<String, String> source, List<String> payloads) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String pt : payloads) {
            String value = source.get(pt);
            if (value != null) {
                out.put(pt, value);
            }
        }
        return out;
    }

    private static String sanitizeCandidateIp(String candidateIp) {
        if (candidateIp == null) {
            return DEFAULT_CANDIDATE_IP;
        }
        String ip = candidateIp.trim();
        if (ip.isEmpty() || "0.0.0.0".equals(ip)) {
            return DEFAULT_CANDIDATE_IP;
        }
        if (ip.indexOf(':') >= 0) {
            return DEFAULT_CANDIDATE_IP;
        }
        return ip;
    }

    private static int sanitizeCandidatePort(int candidatePort) {
        if (candidatePort <= 0 || candidatePort > 65535) {
            return 9;
        }
        return candidatePort;
    }

    private static String randomToken(String seed, int size) {
        String source = seed == null ? UUID.randomUUID().toString().replace("-", "") : seed.replace("-", "");
        if (source.length() < size) {
            source = source + UUID.randomUUID().toString().replace("-", "");
        }
        return source.substring(0, size).toLowerCase(Locale.ROOT);
    }

    private static String syntheticFingerprint(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((seed == null ? UUID.randomUUID().toString() : seed).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8 + 96);
            sb.append("sha-256 ");
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                int b = bytes[i] & 0xFF;
                if (b < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(b).toUpperCase(Locale.ROOT));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return DEFAULT_FINGERPRINT;
        }
    }

    private String resolveFingerprint(String seed) {
        if (localFingerprint != null && !localFingerprint.isEmpty()) {
            return localFingerprint;
        }
        return syntheticFingerprint(seed);
    }

    private static String normalizeFingerprint(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("sha-256 ")) {
            return null;
        }
        return "sha-256 " + v.substring("sha-256 ".length()).trim().toUpperCase(Locale.ROOT);
    }

    private static String sessionOriginId(String seed) {
        long base = 0L;
        if (seed != null) {
            for (int i = 0; i < seed.length(); i++) {
                base = (base * 31L) + seed.charAt(i);
            }
        }
        if (base < 0) {
            base = -base;
        }
        if (base == 0L) {
            base = System.currentTimeMillis();
        }
        return String.valueOf(base);
    }

    private static String bundleMids(List<MediaAnswer> answers) {
        StringBuilder mids = new StringBuilder();
        for (MediaAnswer answer : answers) {
            if (answer.rejected) {
                continue;
            }
            if (mids.length() > 0) {
                mids.append(' ');
            }
            mids.append(answer.mid);
        }
        return mids.toString();
    }

    private static void appendPayloadList(StringBuilder out, List<String> payloads) {
        for (int i = 0; i < payloads.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(payloads.get(i));
        }
    }

    private static void appendAttributeValues(
            StringBuilder sdp,
            String attrPrefix,
            Map<String, String> values,
            List<String> payloads) {
        for (String pt : payloads) {
            String v = values.get(pt);
            if (v != null && !v.isEmpty()) {
                sdp.append(attrPrefix).append(pt).append(' ').append(v).append(CRLF);
            }
        }
    }

    private static final class Offer {
        private final List<MediaSection> mediaSections = new ArrayList<MediaSection>();
        private String fingerprint;
    }

    private static final class MediaSection {
        private String kind = "video";
        private String proto = "UDP/TLS/RTP/SAVPF";
        private String mid;
        private String direction = "sendrecv";
        private boolean hasRtcpMux;
        private boolean hasRtcpRsize;
        private String fingerprint;
        private final List<String> payloads = new ArrayList<String>();
        private final Map<String, String> rtpmap = new LinkedHashMap<String, String>();
        private final Map<String, String> fmtp = new LinkedHashMap<String, String>();
        private final Map<String, String> rtcpFb = new LinkedHashMap<String, String>();
    }

    private static final class MediaAnswer {
        private String kind;
        private String proto;
        private String mid;
        private String direction;
        private boolean rejected;
        private boolean hasRtcpMux;
        private boolean hasRtcpRsize;
        private List<String> acceptedPayloads;
        private Map<String, String> rtpmap;
        private Map<String, String> fmtp;
        private Map<String, String> rtcpFb;
    }
}
