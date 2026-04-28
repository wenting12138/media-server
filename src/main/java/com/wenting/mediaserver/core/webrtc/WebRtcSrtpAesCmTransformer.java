package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal SRTP transform for RTP:
 * SRTP_AES128_CM_HMAC_SHA1_80 (RFC 3711 / RFC 5764).
 *
 * <p>Current scope:
 * protects outbound RTP packets only (RTCP not yet wired in media path).</p>
 */
public final class WebRtcSrtpAesCmTransformer implements WebRtcSrtpTransformer {

    private static final int MASTER_KEY_LEN = 16;
    private static final int MASTER_SALT_LEN = 14;
    private static final int SRTP_ENC_KEY_LEN = 16;
    private static final int SRTP_AUTH_KEY_LEN = 20;
    private static final int SRTP_SALT_KEY_LEN = 14;
    private static final int SRTP_TAG_LEN = 10;
    private static final String HMAC_SHA1 = "HmacSHA1";
    private static final String AES_ECB_NO_PADDING = "AES/ECB/NoPadding";

    private final byte[] rtpEncKey;
    private final byte[] rtpAuthKey;
    private final byte[] rtpSaltKey;
    private final ThreadLocal<Cipher> aesCipher = new ThreadLocal<Cipher>();
    private final ThreadLocal<Mac> hmacSha1 = new ThreadLocal<Mac>();
    private final Map<Integer, SenderState> senders = new ConcurrentHashMap<Integer, SenderState>();

    private WebRtcSrtpAesCmTransformer(byte[] masterKey, byte[] masterSalt) {
        this.rtpEncKey = deriveSessionMaterial(masterKey, masterSalt, 0x00, SRTP_ENC_KEY_LEN);
        this.rtpAuthKey = deriveSessionMaterial(masterKey, masterSalt, 0x01, SRTP_AUTH_KEY_LEN);
        this.rtpSaltKey = deriveSessionMaterial(masterKey, masterSalt, 0x02, SRTP_SALT_KEY_LEN);
    }

    public static WebRtcSrtpAesCmTransformer fromDtlsSrtpKeyingMaterial(byte[] keyingMaterial, boolean serverSide) {
        if (keyingMaterial == null || keyingMaterial.length < 2 * (MASTER_KEY_LEN + MASTER_SALT_LEN)) {
            throw new IllegalArgumentException("invalid dtls-srtp keying material");
        }
        int clientKeyOffset = 0;
        int serverKeyOffset = MASTER_KEY_LEN;
        int clientSaltOffset = 2 * MASTER_KEY_LEN;
        int serverSaltOffset = clientSaltOffset + MASTER_SALT_LEN;
        byte[] masterKey = new byte[MASTER_KEY_LEN];
        byte[] masterSalt = new byte[MASTER_SALT_LEN];
        if (serverSide) {
            System.arraycopy(keyingMaterial, serverKeyOffset, masterKey, 0, MASTER_KEY_LEN);
            System.arraycopy(keyingMaterial, serverSaltOffset, masterSalt, 0, MASTER_SALT_LEN);
        } else {
            System.arraycopy(keyingMaterial, clientKeyOffset, masterKey, 0, MASTER_KEY_LEN);
            System.arraycopy(keyingMaterial, clientSaltOffset, masterSalt, 0, MASTER_SALT_LEN);
        }
        return new WebRtcSrtpAesCmTransformer(masterKey, masterSalt);
    }

    @Override
    public boolean isProtectedTransport() {
        return true;
    }

    @Override
    public synchronized ByteBuf protectRtp(ByteBuf plainRtp) {
        if (plainRtp == null || !plainRtp.isReadable()) {
            return null;
        }
        int len = plainRtp.readableBytes();
        if (len < 12) {
            return null;
        }
        byte[] packet = new byte[len];
        plainRtp.getBytes(plainRtp.readerIndex(), packet);

        int payloadOffset = readRtpPayloadOffset(packet);
        if (payloadOffset < 12 || payloadOffset >= packet.length) {
            return null;
        }
        int seq = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        int ssrc = ((packet[8] & 0xFF) << 24)
                | ((packet[9] & 0xFF) << 16)
                | ((packet[10] & 0xFF) << 8)
                | (packet[11] & 0xFF);
        SenderState state = senderState(ssrc);
        int roc = state.updateAndGetRoc(seq);
        long index = (((long) roc) << 16) | (seq & 0xFFFFL);

        byte[] iv = createRtpIv(rtpSaltKey, index, ssrc);
        xorAesCtr(packet, payloadOffset, packet.length - payloadOffset, iv);
        byte[] tag = computeAuthTag(packet, roc);

        ByteBuf out = Unpooled.buffer(packet.length + SRTP_TAG_LEN);
        out.writeBytes(packet);
        out.writeBytes(tag, 0, SRTP_TAG_LEN);
        return out;
    }

    @Override
    public ByteBuf protectRtcp(ByteBuf plainRtcp) {
        return null;
    }

    private SenderState senderState(int ssrc) {
        SenderState current = senders.get(ssrc);
        if (current != null) {
            return current;
        }
        SenderState created = new SenderState();
        SenderState prev = senders.putIfAbsent(ssrc, created);
        return prev == null ? created : prev;
    }

    private void xorAesCtr(byte[] packet, int offset, int length, byte[] iv) {
        if (length <= 0) {
            return;
        }
        Cipher cipher = ecbCipher();
        byte[] ctr = Arrays.copyOf(iv, iv.length);
        int outPos = 0;
        int blockCounter = 0;
        while (outPos < length) {
            ctr[14] = (byte) ((blockCounter >>> 8) & 0xFF);
            ctr[15] = (byte) (blockCounter & 0xFF);
            byte[] keystream = doAesBlock(cipher, ctr);
            int chunk = Math.min(16, length - outPos);
            for (int i = 0; i < chunk; i++) {
                packet[offset + outPos + i] = (byte) (packet[offset + outPos + i] ^ keystream[i]);
            }
            outPos += chunk;
            blockCounter++;
        }
    }

    private byte[] computeAuthTag(byte[] packet, int roc) {
        Mac mac = hmac();
        mac.update(packet);
        mac.update((byte) ((roc >>> 24) & 0xFF));
        mac.update((byte) ((roc >>> 16) & 0xFF));
        mac.update((byte) ((roc >>> 8) & 0xFF));
        mac.update((byte) (roc & 0xFF));
        return mac.doFinal();
    }

    private static byte[] createRtpIv(byte[] saltKey, long index, int ssrc) {
        byte[] iv = new byte[16];
        iv[4] = (byte) ((ssrc >>> 24) & 0xFF);
        iv[5] = (byte) ((ssrc >>> 16) & 0xFF);
        iv[6] = (byte) ((ssrc >>> 8) & 0xFF);
        iv[7] = (byte) (ssrc & 0xFF);
        byte[] indexBytes = new byte[8];
        indexBytes[0] = (byte) ((index >>> 56) & 0xFF);
        indexBytes[1] = (byte) ((index >>> 48) & 0xFF);
        indexBytes[2] = (byte) ((index >>> 40) & 0xFF);
        indexBytes[3] = (byte) ((index >>> 32) & 0xFF);
        indexBytes[4] = (byte) ((index >>> 24) & 0xFF);
        indexBytes[5] = (byte) ((index >>> 16) & 0xFF);
        indexBytes[6] = (byte) ((index >>> 8) & 0xFF);
        indexBytes[7] = (byte) (index & 0xFF);
        for (int i = 0; i < 8; i++) {
            iv[6 + i] ^= indexBytes[i];
        }
        for (int i = 0; i < 14; i++) {
            iv[i] ^= saltKey[i];
        }
        return iv;
    }

    private byte[] deriveSessionMaterial(byte[] masterKey, byte[] masterSalt, int label, int outLen) {
        byte[] input = new byte[16];
        System.arraycopy(masterSalt, 0, input, 0, MASTER_SALT_LEN);
        // RFC 3711 + widespread interop behavior:
        // right-aligned <label || r> with r=0 makes label land on byte[7] for SRTP keys.
        input[7] ^= (byte) (label & 0xFF);
        byte[] out = new byte[outLen];
        byte[] ctr = Arrays.copyOf(input, input.length);
        Cipher cipher = newEcbCipher(masterKey);
        int outPos = 0;
        int blockCounter = 0;
        while (outPos < outLen) {
            ctr[14] = (byte) ((blockCounter >>> 8) & 0xFF);
            ctr[15] = (byte) (blockCounter & 0xFF);
            byte[] block = doAesBlock(cipher, ctr);
            int chunk = Math.min(16, outLen - outPos);
            System.arraycopy(block, 0, out, outPos, chunk);
            outPos += chunk;
            blockCounter++;
        }
        return out;
    }

    private static int readRtpPayloadOffset(byte[] rtp) {
        if (rtp == null || rtp.length < 12) {
            return -1;
        }
        int b0 = rtp[0] & 0xFF;
        int cc = b0 & 0x0F;
        boolean extension = (b0 & 0x10) != 0;
        int offset = 12 + (cc * 4);
        if (offset > rtp.length) {
            return -1;
        }
        if (extension) {
            if (offset + 4 > rtp.length) {
                return -1;
            }
            int extWords = ((rtp[offset + 2] & 0xFF) << 8) | (rtp[offset + 3] & 0xFF);
            offset += 4 + (extWords * 4);
            if (offset > rtp.length) {
                return -1;
            }
        }
        return offset;
    }

    private Cipher ecbCipher() {
        Cipher c = aesCipher.get();
        if (c != null) {
            return c;
        }
        try {
            c = Cipher.getInstance(AES_ECB_NO_PADDING);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rtpEncKey, "AES"));
            aesCipher.set(c);
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to init SRTP AES cipher", e);
        }
    }

    private Mac hmac() {
        Mac m = hmacSha1.get();
        if (m != null) {
            m.reset();
            return m;
        }
        try {
            m = Mac.getInstance(HMAC_SHA1);
            m.init(new SecretKeySpec(rtpAuthKey, HMAC_SHA1));
            hmacSha1.set(m);
            return m;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to init SRTP HMAC", e);
        }
    }

    private static Cipher newEcbCipher(byte[] key) {
        try {
            Cipher c = Cipher.getInstance(AES_ECB_NO_PADDING);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to init AES cipher", e);
        }
    }

    private static byte[] doAesBlock(Cipher cipher, byte[] block16) {
        try {
            return cipher.doFinal(block16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SRTP AES block encrypt failed", e);
        }
    }

    private static final class SenderState {
        private int roc;
        private int lastSeq = -1;

        private int updateAndGetRoc(int seq) {
            if (lastSeq >= 0) {
                int delta = seq - lastSeq;
                if (delta < -32768) {
                    roc++;
                }
            }
            lastSeq = seq & 0xFFFF;
            return roc;
        }
    }
}
