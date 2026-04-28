package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLContext;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal real DTLS engine based on JSSE SSLEngine (server mode).
 * It drives DTLS handshakes from inbound UDP DTLS records and returns outbound DTLS flights.
 */
public final class WebRtcJsseDtlsEngine implements WebRtcDtlsEngine {

    private static final Logger log = LoggerFactory.getLogger(WebRtcJsseDtlsEngine.class);
    private static final char[] KEY_PASS = "mediaserver".toCharArray();
    private static final String DTLS_SRTP_EXPORTER_LABEL = "EXTRACTOR-dtls_srtp";
    private static final int DTLS_SRTP_EXPORTER_LEN = 2 * (16 + 14);
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final SSLContext sslContext;
    private final String fingerprint;
    private final Map<String, SessionState> states = new ConcurrentHashMap<String, SessionState>();

    private WebRtcJsseDtlsEngine(SSLContext sslContext, String fingerprint) {
        this.sslContext = sslContext;
        this.fingerprint = fingerprint;
    }

    public static WebRtcJsseDtlsEngine create() throws Exception {
        installBouncyCastleProviders();
        SelfSignedCertificate cert = new SelfSignedCertificate("media-server-webrtc");
        try {
            SSLContext context = createDtlsContext(cert);
            String fp = sha256Fingerprint(cert.cert());
            log.info("WebRTC DTLS JSSE engine ready fingerprint={}", fp);
            return new WebRtcJsseDtlsEngine(context, fp);
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
        SessionState state;
        try {
            state = stateFor(session.id());
        } catch (Exception e) {
            log.warn("DTLS state init failed session={}: {}", session.id(), e.toString());
            return WebRtcDtlsEngineResult.noChange();
        }
        byte[] inbound = new byte[dtlsPacket.readableBytes()];
        dtlsPacket.getBytes(dtlsPacket.readerIndex(), inbound);

        SessionDriveResult driveResult;
        try {
            driveResult = state.drive(inbound);
        } catch (Exception e) {
            log.warn("DTLS packet handling failed session={}: {}", session.id(), e.toString());
            state.fail();
            return WebRtcDtlsEngineResult.noChange();
        }

        if (driveResult == null || driveResult.outboundPackets == null || driveResult.outboundPackets.isEmpty()) {
            if (driveResult != null && driveResult.establishedNow) {
                return WebRtcDtlsEngineResult.established(buildSrtpTransformer(state));
            }
            return WebRtcDtlsEngineResult.noChange();
        }
        if (driveResult.establishedNow) {
            return WebRtcDtlsEngineResult.established(driveResult.outboundPackets, buildSrtpTransformer(state));
        }
        return WebRtcDtlsEngineResult.outbound(driveResult.outboundPackets);
    }

    private WebRtcSrtpTransformer buildSrtpTransformer(SessionState state) {
        if (state == null || state.engine == null) {
            return null;
        }
        try {
            byte[] keyingMaterial = exportDtlsSrtpKeyingMaterial(state.engine);
            if (keyingMaterial == null || keyingMaterial.length < DTLS_SRTP_EXPORTER_LEN) {
                log.warn("DTLS-SRTP exporter unavailable or too short");
                return null;
            }
            return WebRtcSrtpAesCmTransformer.fromDtlsSrtpKeyingMaterial(keyingMaterial, true);
        } catch (Exception e) {
            log.warn("DTLS-SRTP transform bootstrap failed: {}", e.toString());
            return null;
        }
    }

    private SessionState stateFor(String sessionId) throws Exception {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId");
        }
        SessionState exists = states.get(sessionId);
        if (exists != null) {
            return exists;
        }
        SessionState created = new SessionState(newEngine());
        SessionState prev = states.putIfAbsent(sessionId, created);
        return prev == null ? created : prev;
    }

    private SSLEngine newEngine() throws Exception {
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(false);
        enableDtlsProtocols(engine);
        engine.beginHandshake();
        return engine;
    }

    private static SSLContext createDtlsContext(SelfSignedCertificate cert) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("media-server-dtls", cert.key(), KEY_PASS, new java.security.cert.Certificate[]{cert.cert()});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEY_PASS);

        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("DTLSv1.2", "BCJSSE");
        } catch (Exception ignored) {
            try {
                ctx = SSLContext.getInstance("DTLSv1.2");
            } catch (Exception ignored2) {
                ctx = SSLContext.getInstance("DTLS");
            }
        }
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static void enableDtlsProtocols(SSLEngine engine) {
        String[] supported = engine.getSupportedProtocols();
        List<String> dtls = new ArrayList<String>(2);
        for (String p : supported) {
            if (p == null) {
                continue;
            }
            String v = p.trim().toUpperCase(Locale.ROOT);
            if ("DTLSV1.2".equals(v) || "DTLSV1.0".equals(v) || "DTLSV1".equals(v)) {
                dtls.add(p);
            }
        }
        if (!dtls.isEmpty()) {
            engine.setEnabledProtocols(dtls.toArray(new String[dtls.size()]));
        }
        SSLParameters params = engine.getSSLParameters();
        try {
            params.setUseCipherSuitesOrder(true);
            engine.setSSLParameters(params);
        } catch (Exception ignored) {
            // Optional across JDK/provider variants.
        }
    }

    private static void installBouncyCastleProviders() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (Security.getProvider("BCJSSE") == null) {
            Security.addProvider(new BouncyCastleJsseProvider());
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

    private static final class SessionState {

        private final SSLEngine engine;
        private ByteBuffer appBuffer;
        private boolean established;
        private boolean failed;

        private SessionState(SSLEngine engine) {
            this.engine = engine;
            SSLSession sslSession = engine.getSession();
            int appSize = sslSession == null ? 2048 : sslSession.getApplicationBufferSize();
            this.appBuffer = ByteBuffer.allocate(Math.max(2048, appSize));
        }

        private synchronized SessionDriveResult drive(byte[] inboundBytes) throws SSLException {
            if (failed) {
                return SessionDriveResult.noChange();
            }
            List<ByteBuf> outbound = new ArrayList<ByteBuf>(2);
            boolean establishedNow = false;

            if (inboundBytes != null && inboundBytes.length > 0) {
                ByteBuffer inbound = ByteBuffer.wrap(inboundBytes);
                int unwrapGuard = 32;
                while (inbound.hasRemaining() && unwrapGuard-- > 0) {
                    ensureAppCapacity();
                    appBuffer.clear();
                    SSLEngineResult res = engine.unwrap(inbound, appBuffer);
                    runDelegatedTasks(engine);
                    if (res.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        growAppBuffer();
                        continue;
                    }
                    if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
                        failed = true;
                        break;
                    }
                    if (res.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                        establishedNow = markEstablished();
                    }
                    if (res.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        break;
                    }
                    if (res.bytesConsumed() == 0 && res.bytesProduced() == 0) {
                        break;
                    }
                }
            }

            driveWrap(outbound);
            if (!established && isEstablishedHandshakeStatus(engine.getHandshakeStatus())) {
                establishedNow = markEstablished() || establishedNow;
            }
            return new SessionDriveResult(establishedNow, outbound);
        }

        private void driveWrap(List<ByteBuf> outbound) throws SSLException {
            int wrapGuard = 64;
            while (wrapGuard-- > 0) {
                SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
                String hsName = hs == null ? "" : hs.name();
                if ("NEED_TASK".equals(hsName)) {
                    runDelegatedTasks(engine);
                    continue;
                }
                if ("NEED_UNWRAP_AGAIN".equals(hsName)) {
                    ensureAppCapacity();
                    appBuffer.clear();
                    SSLEngineResult res = engine.unwrap(EMPTY_BUFFER, appBuffer);
                    runDelegatedTasks(engine);
                    if (res.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                        markEstablished();
                    }
                    if (res.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        growAppBuffer();
                    }
                    if (res.bytesConsumed() == 0 && res.bytesProduced() == 0) {
                        break;
                    }
                    continue;
                }
                if (hs != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    break;
                }
                ByteBuffer out = ByteBuffer.allocate(outPacketCapacity());
                SSLEngineResult res = engine.wrap(EMPTY_BUFFER, out);
                runDelegatedTasks(engine);
                if (res.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    continue;
                }
                if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
                    failed = true;
                    break;
                }
                if (res.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                    markEstablished();
                }
                if (res.bytesProduced() > 0) {
                    out.flip();
                    byte[] datagram = new byte[out.remaining()];
                    out.get(datagram);
                    outbound.add(Unpooled.wrappedBuffer(datagram));
                }
                if (res.bytesConsumed() == 0 && res.bytesProduced() == 0) {
                    break;
                }
            }
        }

        private boolean markEstablished() {
            if (established) {
                return false;
            }
            established = true;
            return true;
        }

        private void ensureAppCapacity() {
            if (appBuffer == null || appBuffer.capacity() < 2048) {
                appBuffer = ByteBuffer.allocate(2048);
            }
        }

        private void growAppBuffer() {
            int next = appBuffer == null ? 2048 : (appBuffer.capacity() * 2);
            appBuffer = ByteBuffer.allocate(Math.max(2048, Math.min(next, 65536)));
        }

        private int outPacketCapacity() {
            SSLSession sslSession = engine.getSession();
            int size = sslSession == null ? 2048 : sslSession.getPacketBufferSize();
            return Math.max(2048, Math.min(size + 1024, 65536));
        }

        private void fail() {
            this.failed = true;
        }
    }

    private static void runDelegatedTasks(SSLEngine engine) {
        if (engine == null) {
            return;
        }
        while (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            Runnable task = engine.getDelegatedTask();
            if (task == null) {
                break;
            }
            task.run();
        }
    }

    private static byte[] exportDtlsSrtpKeyingMaterial(SSLEngine engine) throws Exception {
        if (!(engine instanceof org.bouncycastle.jsse.BCSSLEngine)) {
            return null;
        }
        org.bouncycastle.jsse.BCSSLEngine bcEngine = (org.bouncycastle.jsse.BCSSLEngine) engine;
        org.bouncycastle.jsse.BCSSLConnection connection = bcEngine.getConnection();
        if (connection == null) {
            return null;
        }
        Method exporter = connection.getClass().getDeclaredMethod(
                "exportKeyingMaterial",
                String.class,
                byte[].class,
                int.class);
        exporter.setAccessible(true);
        Object value = exporter.invoke(connection, DTLS_SRTP_EXPORTER_LABEL, null, DTLS_SRTP_EXPORTER_LEN);
        if (!(value instanceof byte[])) {
            return null;
        }
        return (byte[]) value;
    }

    private static boolean isEstablishedHandshakeStatus(SSLEngineResult.HandshakeStatus hs) {
        return hs == SSLEngineResult.HandshakeStatus.FINISHED
                || hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
    }

    private static final class SessionDriveResult {
        private final boolean establishedNow;
        private final List<ByteBuf> outboundPackets;

        private SessionDriveResult(boolean establishedNow, List<ByteBuf> outboundPackets) {
            this.establishedNow = establishedNow;
            this.outboundPackets = outboundPackets == null ? new ArrayList<ByteBuf>(0) : outboundPackets;
        }

        private static SessionDriveResult noChange() {
            return new SessionDriveResult(false, new ArrayList<ByteBuf>(0));
        }
    }
}
