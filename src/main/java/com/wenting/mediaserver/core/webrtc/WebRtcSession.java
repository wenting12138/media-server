package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One WHEP/WebRTC playback signaling session.
 */
public final class WebRtcSession {

    private final String id;
    private final StreamKey streamKey;
    private final String offerSdp;
    private final String remoteAddress;
    private final long createdAtMs;
    private final CopyOnWriteArrayList<String> remoteIceCandidates = new CopyOnWriteArrayList<String>();
    private final AtomicLong mediaVideoPackets = new AtomicLong();
    private final AtomicLong mediaAudioPackets = new AtomicLong();
    private final AtomicLong mediaDataPackets = new AtomicLong();
    private final AtomicLong plainRtpRelayPackets = new AtomicLong();
    private final AtomicLong plainRtpRelayBytes = new AtomicLong();
    private final AtomicLong stunIngressPackets = new AtomicLong();
    private final AtomicLong stunBindingSuccessPackets = new AtomicLong();
    private final AtomicLong stunBindingFailurePackets = new AtomicLong();
    private final AtomicLong dtlsIngressPackets = new AtomicLong();
    private final AtomicLong rtpIngressPackets = new AtomicLong();
    private final AtomicLong rtcpIngressPackets = new AtomicLong();
    private final AtomicLong unknownUdpIngressPackets = new AtomicLong();
    private final AtomicLong srtpProtectedPackets = new AtomicLong();
    private final AtomicLong srtpProtectedBytes = new AtomicLong();
    private volatile long lastSeenAtMs;
    private volatile String answerSdp;
    private volatile String localIceUfrag;
    private volatile String localIcePwd;
    private volatile String remoteIceUfrag;
    private volatile String remoteIcePwd;
    private volatile boolean remoteEndOfCandidates;
    private volatile String remoteOfferFingerprint;
    private volatile String remoteOfferSetupRole;
    private volatile int remoteOfferMediaCount;
    private volatile int remoteOfferAudioMediaCount;
    private volatile int remoteOfferVideoMediaCount;
    private volatile InetSocketAddress selectedRtpCandidate;
    private volatile String selectedCandidateType;
    private volatile String selectedCandidateTransport;
    private volatile WebRtcTransportState transportState = WebRtcTransportState.NEW;
    private volatile WebRtcSrtpTransformer srtpTransformer;
    private volatile long iceConnectedAtMs;
    private volatile long dtlsClientHelloAtMs;
    private volatile long dtlsEstablishedAtMs;
    private volatile long mediaAttachedAtMs;
    private volatile long mediaLastPacketAtMs;
    private volatile String mediaSourceProtocol;
    private volatile String mediaPayloadFormat;
    private volatile boolean mediaVideoConfigSeen;
    private volatile boolean mediaVideoKeyFrameSeen;
    private volatile String lastStunUsername;

    WebRtcSession(String id, StreamKey streamKey, String offerSdp, String remoteAddress, long createdAtMs) {
        this.id = id;
        this.streamKey = streamKey;
        this.offerSdp = offerSdp;
        this.remoteAddress = remoteAddress;
        this.createdAtMs = createdAtMs;
        this.lastSeenAtMs = createdAtMs;
    }

    public String id() {
        return id;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String offerSdp() {
        return offerSdp;
    }

    public String answerSdp() {
        return answerSdp;
    }

    public String localIceUfrag() {
        return localIceUfrag;
    }

    public String localIcePwd() {
        return localIcePwd;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public long lastSeenAtMs() {
        return lastSeenAtMs;
    }

    public String remoteIceUfrag() {
        return remoteIceUfrag;
    }

    public String remoteIcePwd() {
        return remoteIcePwd;
    }

    public int remoteCandidateCount() {
        return remoteIceCandidates.size();
    }

    public List<String> remoteIceCandidates() {
        return new ArrayList<String>(remoteIceCandidates);
    }

    public boolean remoteEndOfCandidates() {
        return remoteEndOfCandidates;
    }

    public String remoteOfferFingerprint() {
        return remoteOfferFingerprint;
    }

    public String remoteOfferSetupRole() {
        return remoteOfferSetupRole;
    }

    public int remoteOfferMediaCount() {
        return remoteOfferMediaCount;
    }

    public int remoteOfferAudioMediaCount() {
        return remoteOfferAudioMediaCount;
    }

    public int remoteOfferVideoMediaCount() {
        return remoteOfferVideoMediaCount;
    }

    public InetSocketAddress selectedRtpCandidate() {
        return selectedRtpCandidate;
    }

    public String selectedRtpCandidateHost() {
        InetSocketAddress addr = selectedRtpCandidate;
        if (addr == null) {
            return null;
        }
        return addr.getHostString();
    }

    public int selectedRtpCandidatePort() {
        InetSocketAddress addr = selectedRtpCandidate;
        return addr == null ? 0 : addr.getPort();
    }

    public String selectedCandidateType() {
        return selectedCandidateType;
    }

    public String selectedCandidateTransport() {
        return selectedCandidateTransport;
    }

    public boolean canPlainRtpRelay() {
        return selectedRtpCandidate != null;
    }

    public WebRtcTransportState transportState() {
        return transportState;
    }

    public long iceConnectedAtMs() {
        return iceConnectedAtMs;
    }

    public long dtlsClientHelloAtMs() {
        return dtlsClientHelloAtMs;
    }

    public long dtlsEstablishedAtMs() {
        return dtlsEstablishedAtMs;
    }

    public long mediaAttachedAtMs() {
        return mediaAttachedAtMs;
    }

    public long mediaLastPacketAtMs() {
        return mediaLastPacketAtMs;
    }

    public long mediaVideoPackets() {
        return mediaVideoPackets.get();
    }

    public long mediaAudioPackets() {
        return mediaAudioPackets.get();
    }

    public long mediaDataPackets() {
        return mediaDataPackets.get();
    }

    public long plainRtpRelayPackets() {
        return plainRtpRelayPackets.get();
    }

    public long plainRtpRelayBytes() {
        return plainRtpRelayBytes.get();
    }

    public long stunIngressPackets() {
        return stunIngressPackets.get();
    }

    public long stunBindingSuccessPackets() {
        return stunBindingSuccessPackets.get();
    }

    public long stunBindingFailurePackets() {
        return stunBindingFailurePackets.get();
    }

    public String lastStunUsername() {
        return lastStunUsername;
    }

    public long dtlsIngressPackets() {
        return dtlsIngressPackets.get();
    }

    public long rtpIngressPackets() {
        return rtpIngressPackets.get();
    }

    public long rtcpIngressPackets() {
        return rtcpIngressPackets.get();
    }

    public long unknownUdpIngressPackets() {
        return unknownUdpIngressPackets.get();
    }

    public long srtpProtectedPackets() {
        return srtpProtectedPackets.get();
    }

    public long srtpProtectedBytes() {
        return srtpProtectedBytes.get();
    }

    public boolean srtpReady() {
        return transportState == WebRtcTransportState.DTLS_ESTABLISHED
                && srtpTransformer != null
                && srtpTransformer.isProtectedTransport();
    }

    public String mediaSourceProtocol() {
        return mediaSourceProtocol;
    }

    public String mediaPayloadFormat() {
        return mediaPayloadFormat;
    }

    public boolean mediaVideoConfigSeen() {
        return mediaVideoConfigSeen;
    }

    public boolean mediaVideoKeyFrameSeen() {
        return mediaVideoKeyFrameSeen;
    }

    public void setAnswerSdp(String answerSdp) {
        this.answerSdp = answerSdp;
        this.localIceUfrag = parseSdpAttribute(answerSdp, "a=ice-ufrag:");
        this.localIcePwd = parseSdpAttribute(answerSdp, "a=ice-pwd:");
        touch();
    }

    public void applyOfferInfo(WebRtcOfferInfo offerInfo) {
        if (offerInfo == null) {
            return;
        }
        remoteIceUfrag = offerInfo.iceUfrag();
        remoteIcePwd = offerInfo.icePwd();
        remoteOfferFingerprint = offerInfo.fingerprint();
        remoteOfferSetupRole = offerInfo.setupRole();
        remoteOfferMediaCount = offerInfo.mediaCount();
        remoteOfferAudioMediaCount = offerInfo.audioMediaCount();
        remoteOfferVideoMediaCount = offerInfo.videoMediaCount();
        touch();
    }

    public void applyRemoteIceFragment(WebRtcIceSdpFragment fragment) {
        if (fragment == null) {
            return;
        }
        if (fragment.iceUfrag() != null) {
            this.remoteIceUfrag = fragment.iceUfrag();
        }
        if (fragment.icePwd() != null) {
            this.remoteIcePwd = fragment.icePwd();
        }
        List<String> candidates = fragment.candidates();
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate == null || candidate.trim().isEmpty()) {
                    continue;
                }
                String value = candidate.trim();
                remoteIceCandidates.add(value);
                if (selectedRtpCandidate == null) {
                    trySelectCandidate(value);
                }
            }
        }
        if (fragment.endOfCandidates()) {
            this.remoteEndOfCandidates = true;
        }
        touch();
    }

    public void onPlainRtpRelayed(int bytes) {
        plainRtpRelayPackets.incrementAndGet();
        if (bytes > 0) {
            plainRtpRelayBytes.addAndGet(bytes);
        }
        touch();
    }

    public void onStunConnectivityCheck(InetSocketAddress sender) {
        stunIngressPackets.incrementAndGet();
        if (sender != null && selectedRtpCandidate == null) {
            selectRtpCandidateFromStun(sender);
        }
        if (transportState == WebRtcTransportState.NEW) {
            transportState = WebRtcTransportState.ICE_CONNECTED;
            iceConnectedAtMs = System.currentTimeMillis();
        }
        touch();
    }

    public void onStunBindingSuccessSent(String username) {
        stunBindingSuccessPackets.incrementAndGet();
        if (username != null && !username.trim().isEmpty()) {
            lastStunUsername = username.trim();
        }
        touch();
    }

    public void onStunBindingFailure(String username) {
        stunBindingFailurePackets.incrementAndGet();
        if (username != null && !username.trim().isEmpty()) {
            lastStunUsername = username.trim();
        }
        touch();
    }

    public void selectRtpCandidateFromStun(InetSocketAddress sender) {
        if (sender == null) {
            return;
        }
        selectedRtpCandidate = sender;
        selectedCandidateType = "peer-reflexive";
        selectedCandidateTransport = "udp";
        touch();
    }

    public void onDtlsPacket(boolean clientHello) {
        dtlsIngressPackets.incrementAndGet();
        if (clientHello && transportState != WebRtcTransportState.DTLS_ESTABLISHED) {
            transportState = WebRtcTransportState.DTLS_CLIENT_HELLO_SEEN;
            if (dtlsClientHelloAtMs <= 0L) {
                dtlsClientHelloAtMs = System.currentTimeMillis();
            }
        } else if (transportState == WebRtcTransportState.NEW) {
            // Some networks may hide STUN; still mark transport as progressed.
            transportState = WebRtcTransportState.DTLS_CLIENT_HELLO_SEEN;
            if (dtlsClientHelloAtMs <= 0L) {
                dtlsClientHelloAtMs = System.currentTimeMillis();
            }
        }
        touch();
    }

    public void markDtlsEstablished() {
        transportState = WebRtcTransportState.DTLS_ESTABLISHED;
        if (dtlsEstablishedAtMs <= 0L) {
            dtlsEstablishedAtMs = System.currentTimeMillis();
        }
        touch();
    }

    public void setSrtpTransformer(WebRtcSrtpTransformer transformer) {
        this.srtpTransformer = transformer;
        touch();
    }

    public io.netty.buffer.ByteBuf protectOutboundRtp(io.netty.buffer.ByteBuf plainRtp, boolean allowPlainRelay) {
        if (plainRtp == null || !plainRtp.isReadable()) {
            return null;
        }
        WebRtcSrtpTransformer transformer = this.srtpTransformer;
        io.netty.buffer.ByteBuf protectedPacket;
        if (transformer == null) {
            if (!allowPlainRelay) {
                return null;
            }
            protectedPacket = plainRtp.retainedDuplicate();
        } else {
            if (!transformer.isProtectedTransport() && !allowPlainRelay) {
                return null;
            }
            protectedPacket = transformer.protectRtp(plainRtp);
        }
        if (protectedPacket != null && protectedPacket.isReadable()) {
            srtpProtectedPackets.incrementAndGet();
            srtpProtectedBytes.addAndGet(protectedPacket.readableBytes());
        }
        return protectedPacket;
    }

    public void onRtpIngressPacket() {
        rtpIngressPackets.incrementAndGet();
        touch();
    }

    public void onRtcpIngressPacket() {
        rtcpIngressPackets.incrementAndGet();
        touch();
    }

    public void onUnknownUdpPacket() {
        unknownUdpIngressPackets.incrementAndGet();
        touch();
    }

    public void onMediaPacket(
            String trackType,
            String sourceProtocol,
            String payloadFormat,
            boolean codecConfig,
            boolean keyFrame) {
        long now = System.currentTimeMillis();
        if (mediaAttachedAtMs <= 0L) {
            mediaAttachedAtMs = now;
        }
        mediaLastPacketAtMs = now;
        if (sourceProtocol != null && !sourceProtocol.trim().isEmpty()) {
            mediaSourceProtocol = sourceProtocol;
        }
        if (payloadFormat != null && !payloadFormat.trim().isEmpty()) {
            mediaPayloadFormat = payloadFormat;
        }
        if ("video".equalsIgnoreCase(trackType)) {
            mediaVideoPackets.incrementAndGet();
            if (codecConfig) {
                mediaVideoConfigSeen = true;
            }
            if (keyFrame) {
                mediaVideoKeyFrameSeen = true;
            }
        } else if ("audio".equalsIgnoreCase(trackType)) {
            mediaAudioPackets.incrementAndGet();
        } else {
            mediaDataPackets.incrementAndGet();
        }
        touch();
    }

    public void touch() {
        this.lastSeenAtMs = System.currentTimeMillis();
    }

    public boolean matchesSelectedRtpEndpoint(InetSocketAddress endpoint) {
        if (endpoint == null) {
            return false;
        }
        InetSocketAddress selected = selectedRtpCandidate;
        if (selected == null) {
            return false;
        }
        if (selected.getPort() != endpoint.getPort()) {
            return false;
        }
        InetAddress a = selected.getAddress();
        InetAddress b = endpoint.getAddress();
        if (a != null && b != null) {
            return a.equals(b);
        }
        String hostA = selected.getHostString();
        String hostB = endpoint.getHostString();
        if (hostA == null || hostB == null) {
            return false;
        }
        return hostA.trim().toLowerCase(Locale.ROOT).equals(hostB.trim().toLowerCase(Locale.ROOT));
    }

    private void trySelectCandidate(String rawCandidate) {
        WebRtcIceCandidate candidate;
        try {
            candidate = WebRtcIceCandidate.parse(rawCandidate);
        } catch (IllegalArgumentException ignore) {
            return;
        }
        if (!candidate.isUdp() || !candidate.isRtpComponent()) {
            return;
        }
        String host = candidate.address();
        if (host == null || host.trim().isEmpty()) {
            return;
        }
        selectedRtpCandidate = new InetSocketAddress(host.trim(), candidate.port());
        selectedCandidateType = candidate.type();
        selectedCandidateTransport = candidate.transport();
    }

    private static String parseSdpAttribute(String sdp, String prefix) {
        if (sdp == null || prefix == null || prefix.isEmpty()) {
            return null;
        }
        String[] lines = sdp.split("\r\n|\n");
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.startsWith(prefix)) {
                String v = line.substring(prefix.length()).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }
}
