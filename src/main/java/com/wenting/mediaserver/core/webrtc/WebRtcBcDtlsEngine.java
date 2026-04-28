package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DTLSServerProtocol;
import org.bouncycastle.tls.DatagramTransport;
import org.bouncycastle.tls.DefaultTlsServer;
import org.bouncycastle.tls.ExporterLabel;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SRTPProtectionProfile;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsCredentialedSigner;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsSRTPUtils;
import org.bouncycastle.tls.UseSRTPData;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCrypto;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebRTC DTLS-SRTP server based on BouncyCastle's native DTLS stack.
 */
public final class WebRtcBcDtlsEngine implements WebRtcDtlsEngine {

    private static final Logger log = LoggerFactory.getLogger(WebRtcBcDtlsEngine.class);
    private static final int DTLS_SRTP_EXPORTER_LEN = 2 * (16 + 14);
    private static final int DATAGRAM_LIMIT = 1500;
    private static final int OUTBOUND_WAIT_MS = 50;

    private final JcaTlsCrypto crypto;
    private final PrivateKey privateKey;
    private final Certificate certificate;
    private final String fingerprint;
    private final Map<String, SessionState> states = new ConcurrentHashMap<String, SessionState>();

    private WebRtcBcDtlsEngine(
            JcaTlsCrypto crypto,
            PrivateKey privateKey,
            Certificate certificate,
            String fingerprint) {
        this.crypto = crypto;
        this.privateKey = privateKey;
        this.certificate = certificate;
        this.fingerprint = fingerprint;
    }

    public static WebRtcBcDtlsEngine create() throws Exception {
        installBouncyCastleProvider();
        SelfSignedCertificate cert = new SelfSignedCertificate("media-server-webrtc");
        try {
            JcaTlsCrypto crypto = new JcaTlsCryptoProvider()
                    .setProvider("BC")
                    .create(new SecureRandom());
            TlsCertificate tlsCert = crypto.createCertificate(cert.cert().getEncoded());
            Certificate chain = new Certificate(new TlsCertificate[]{tlsCert});
            String fp = sha256Fingerprint(cert.cert());
            log.info("WebRTC DTLS BouncyCastle engine ready fingerprint={}", fp);
            return new WebRtcBcDtlsEngine(crypto, cert.key(), chain, fp);
        } finally {
            cert.delete();
        }
    }

    @Override
    public String localFingerprint() {
        return fingerprint;
    }

    @Override
    public WebRtcDtlsEngineResult onPacket(WebRtcSession session, ByteBuf dtlsPacket, boolean clientHello) {
        if (session == null || dtlsPacket == null || !dtlsPacket.isReadable()) {
            return WebRtcDtlsEngineResult.noChange();
        }
        SessionState state = stateFor(session.id());
        byte[] inbound = new byte[dtlsPacket.readableBytes()];
        dtlsPacket.getBytes(dtlsPacket.readerIndex(), inbound);
        state.offerInbound(inbound);

        List<ByteBuf> outbound = state.drainOutboundAfterWait(OUTBOUND_WAIT_MS);
        if (state.reportEstablishedOnce()) {
            return WebRtcDtlsEngineResult.established(outbound, state.srtpTransformer());
        }
        if (!outbound.isEmpty()) {
            return WebRtcDtlsEngineResult.outbound(outbound);
        }
        return WebRtcDtlsEngineResult.noChange();
    }

    private SessionState stateFor(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId");
        }
        SessionState exists = states.get(sessionId);
        if (exists != null) {
            return exists;
        }
        SessionState created = new SessionState(sessionId);
        SessionState prev = states.putIfAbsent(sessionId, created);
        SessionState state = prev == null ? created : prev;
        if (prev == null) {
            created.start();
        }
        return state;
    }

    private static void installBouncyCastleProvider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static String sha256Fingerprint(X509Certificate cert) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder(8 + (hash.length * 3));
        sb.append("sha-256 ");
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            int b = hash[i] & 0xFF;
            if (b < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(b).toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private final class SessionState implements Runnable {

        private final String sessionId;
        private final QueueDatagramTransport transport = new QueueDatagramTransport();
        private final AtomicBoolean establishedReported = new AtomicBoolean(false);
        private volatile WebRtcSrtpTransformer srtpTransformer;
        private volatile boolean established;
        private volatile boolean failed;

        private SessionState(String sessionId) {
            this.sessionId = sessionId;
        }

        private void start() {
            Thread t = new Thread(this, "webrtc-dtls-" + sessionId);
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {
            WebRtcTlsServer server = new WebRtcTlsServer(crypto, privateKey, certificate);
            try {
                new DTLSServerProtocol().accept(server, transport);
                byte[] keyingMaterial = server.exportDtlsSrtpKeyingMaterial();
                srtpTransformer = WebRtcSrtpAesCmTransformer.fromDtlsSrtpKeyingMaterial(keyingMaterial, true);
                established = true;
                transport.wakeupOutboundWaiters();
                log.info("WebRTC DTLS established session={}", sessionId);
            } catch (Exception e) {
                failed = true;
                transport.wakeupOutboundWaiters();
                log.warn("WebRTC DTLS handshake failed session={}: {}", sessionId, e.toString());
            }
        }

        private void offerInbound(byte[] packet) {
            if (packet == null || packet.length <= 0 || failed) {
                return;
            }
            transport.offerInbound(packet);
        }

        private List<ByteBuf> drainOutboundAfterWait(int waitMs) {
            return transport.drainOutboundAfterWait(waitMs);
        }

        private boolean reportEstablishedOnce() {
            return established && establishedReported.compareAndSet(false, true);
        }

        private WebRtcSrtpTransformer srtpTransformer() {
            return srtpTransformer;
        }
    }

    private static final class QueueDatagramTransport implements DatagramTransport {

        private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<byte[]>();
        private final BlockingQueue<byte[]> outbound = new LinkedBlockingQueue<byte[]>();

        @Override
        public int getReceiveLimit() {
            return DATAGRAM_LIMIT;
        }

        @Override
        public int getSendLimit() {
            return DATAGRAM_LIMIT;
        }

        @Override
        public int receive(byte[] buf, int off, int len, int waitMillis) throws IOException {
            try {
                byte[] packet = inbound.poll(Math.max(1, waitMillis), TimeUnit.MILLISECONDS);
                if (packet == null) {
                    return -1;
                }
                int n = Math.min(len, packet.length);
                System.arraycopy(packet, 0, buf, off, n);
                return n;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for DTLS datagram", e);
            }
        }

        @Override
        public void send(byte[] buf, int off, int len) {
            if (buf == null || len <= 0) {
                return;
            }
            byte[] packet = new byte[len];
            System.arraycopy(buf, off, packet, 0, len);
            outbound.offer(packet);
        }

        @Override
        public void close() {
            inbound.clear();
            outbound.clear();
        }

        private void offerInbound(byte[] packet) {
            inbound.offer(packet);
        }

        private void wakeupOutboundWaiters() {
            outbound.offer(new byte[0]);
        }

        private List<ByteBuf> drainOutboundAfterWait(int waitMs) {
            List<ByteBuf> packets = new ArrayList<ByteBuf>(4);
            try {
                byte[] first = outbound.poll(Math.max(1, waitMs), TimeUnit.MILLISECONDS);
                if (first != null && first.length > 0) {
                    packets.add(Unpooled.wrappedBuffer(first));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return packets;
            }
            while (true) {
                byte[] packet = outbound.poll();
                if (packet == null) {
                    break;
                }
                if (packet.length > 0) {
                    packets.add(Unpooled.wrappedBuffer(packet));
                }
            }
            return packets;
        }
    }

    private static final class WebRtcTlsServer extends DefaultTlsServer {

        private final JcaTlsCrypto crypto;
        private final PrivateKey privateKey;
        private final Certificate certificate;
        private boolean clientOfferedSrtpProfile;

        private WebRtcTlsServer(JcaTlsCrypto crypto, PrivateKey privateKey, Certificate certificate) {
            super(crypto);
            this.crypto = crypto;
            this.privateKey = privateKey;
            this.certificate = certificate;
        }

        @Override
        public ProtocolVersion[] getProtocolVersions() {
            return new ProtocolVersion[]{ProtocolVersion.DTLSv12};
        }

        @Override
        protected int[] getSupportedCipherSuites() {
            return new int[]{
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
            };
        }

        @Override
        public void processClientExtensions(Hashtable clientExtensions) throws IOException {
            super.processClientExtensions(clientExtensions);
            UseSRTPData srtp = TlsSRTPUtils.getUseSRTPExtension(clientExtensions);
            clientOfferedSrtpProfile = hasSrtpAes128Sha1_80(srtp);
            if (!clientOfferedSrtpProfile) {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter, "client did not offer required use_srtp profile");
            }
        }

        @Override
        public Hashtable getServerExtensions() throws IOException {
            Hashtable extensions = super.getServerExtensions();
            if (extensions == null) {
                extensions = new Hashtable();
            }
            if (!clientOfferedSrtpProfile) {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter, "use_srtp not negotiated");
            }
            TlsSRTPUtils.addUseSRTPExtension(
                    extensions,
                    new UseSRTPData(new int[]{SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80}, new byte[0]));
            return extensions;
        }

        @Override
        protected TlsCredentialedSigner getRSASignerCredentials() throws IOException {
            SignatureAndHashAlgorithm sigAlg = new SignatureAndHashAlgorithm(
                    HashAlgorithm.sha256,
                    SignatureAlgorithm.rsa);
            return new JcaDefaultTlsCredentialedSigner(
                    new TlsCryptoParameters(context),
                    crypto,
                    privateKey,
                    certificate,
                    sigAlg);
        }

        private byte[] exportDtlsSrtpKeyingMaterial() {
            if (context == null) {
                throw new IllegalStateException("DTLS context unavailable");
            }
            return context.exportKeyingMaterial(ExporterLabel.dtls_srtp, null, DTLS_SRTP_EXPORTER_LEN);
        }

        private static boolean hasSrtpAes128Sha1_80(UseSRTPData srtp) {
            if (srtp == null || srtp.getProtectionProfiles() == null) {
                return false;
            }
            int[] profiles = srtp.getProtectionProfiles();
            for (int profile : profiles) {
                if (profile == SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80) {
                    return true;
                }
            }
            return false;
        }
    }
}
