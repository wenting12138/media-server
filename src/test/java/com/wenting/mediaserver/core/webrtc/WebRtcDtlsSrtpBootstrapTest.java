package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcDtlsSrtpBootstrapTest {

    @Test
    void shouldUpdateTransportStateFromIngressPackets() {
        WebRtcSessionManager manager = new WebRtcSessionManager(60_000L);
        WebRtcDtlsSrtpBootstrap bootstrap = new WebRtcDtlsSrtpBootstrap(
                manager,
                null,
                new WebRtcPseudoDtlsEngine());
        WebRtcSession session = manager.createPlaybackSession(
                new StreamKey(StreamProtocol.RTMP, "live", "cam_dtls"),
                "v=0\r\n",
                "127.0.0.1");
        session.applyRemoteIceFragment(WebRtcIceSdpFragment.parse(
                "a=ice-ufrag:u1\r\n"
                        + "a=ice-pwd:pwdpwdpwdpwdpwdpwdpwdpwd\r\n"
                        + "a=candidate:1 1 udp 2130706431 127.0.0.1 50123 typ host\r\n"));

        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 50123);
        InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 18080);

        bootstrap.onDatagram(sender, recipient, Unpooled.wrappedBuffer(stunBindingRequest()));
        assertEquals(WebRtcTransportState.ICE_CONNECTED, session.transportState());
        assertEquals(1L, session.stunIngressPackets());

        bootstrap.onDatagram(sender, recipient, Unpooled.wrappedBuffer(dtlsClientHello()));
        assertEquals(WebRtcTransportState.DTLS_CLIENT_HELLO_SEEN, session.transportState());
        assertEquals(1L, session.dtlsIngressPackets());

        byte[] dtlsFollowUp = new byte[]{
                23, (byte) 0xFE, (byte) 0xFD, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
                0x00, 0x08,
                0x11, 0x22, 0x33, 0x44
        };
        bootstrap.onDatagram(sender, recipient, Unpooled.wrappedBuffer(dtlsFollowUp));
        assertEquals(WebRtcTransportState.DTLS_ESTABLISHED, session.transportState());
        assertFalse(session.srtpReady());

        bootstrap.onDatagram(sender, recipient, Unpooled.wrappedBuffer(new byte[]{
                (byte) 0x80, (byte) 200, 0, 6, 0, 0, 0, 1
        }));
        bootstrap.onDatagram(sender, recipient, Unpooled.wrappedBuffer(new byte[]{
                (byte) 0x80, (byte) 96, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1
        }));
        assertEquals(1L, session.rtcpIngressPackets());
        assertEquals(1L, session.rtpIngressPackets());
    }

    @Test
    void shouldSelectSessionFromStunUsernameWhenNoRemoteCandidateWasPatched() {
        WebRtcSessionManager manager = new WebRtcSessionManager(60_000L);
        WebRtcDtlsSrtpBootstrap bootstrap = new WebRtcDtlsSrtpBootstrap(
                manager,
                null,
                new WebRtcPseudoDtlsEngine());
        WebRtcSession session = manager.createPlaybackSession(
                new StreamKey(StreamProtocol.RTMP, "live", "cam_stun_username"),
                "v=0\r\n",
                "127.0.0.1");
        session.setAnswerSdp("v=0\r\na=ice-ufrag:localu\r\na=ice-pwd:localpwdlocalpwdlocalpwd\r\n");

        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 50124);
        InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 20000);

        bootstrap.onDatagram(sender, recipient, Unpooled.wrappedBuffer(stunBindingRequestWithUsername("localu:remoteu")));

        assertEquals(WebRtcTransportState.ICE_CONNECTED, session.transportState());
        assertEquals(1L, session.stunIngressPackets());
        assertEquals("127.0.0.1", session.selectedRtpCandidateHost());
        assertEquals(50124, session.selectedRtpCandidatePort());
        assertEquals("peer-reflexive", session.selectedCandidateType());
    }

    private static byte[] stunBindingRequest() {
        return new byte[]{
                0x00, 0x01, 0x00, 0x00,
                0x21, 0x12, (byte) 0xA4, 0x42,
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
                0x77, (byte) 0x88, 0x10, 0x20, 0x30, 0x40
        };
    }

    private static byte[] stunBindingRequestWithUsername(String username) {
        byte[] name = username.getBytes(io.netty.util.CharsetUtil.UTF_8);
        int padded = (name.length + 3) & ~3;
        byte[] out = new byte[20 + 4 + padded];
        out[0] = 0x00;
        out[1] = 0x01;
        out[2] = (byte) (((4 + padded) >>> 8) & 0xFF);
        out[3] = (byte) ((4 + padded) & 0xFF);
        out[4] = 0x21;
        out[5] = 0x12;
        out[6] = (byte) 0xA4;
        out[7] = 0x42;
        out[8] = 0x11;
        out[9] = 0x22;
        out[10] = 0x33;
        out[11] = 0x44;
        out[12] = 0x55;
        out[13] = 0x66;
        out[14] = 0x77;
        out[15] = (byte) 0x88;
        out[16] = 0x10;
        out[17] = 0x20;
        out[18] = 0x30;
        out[19] = 0x40;
        out[20] = 0x00;
        out[21] = 0x06;
        out[22] = (byte) ((name.length >>> 8) & 0xFF);
        out[23] = (byte) (name.length & 0xFF);
        System.arraycopy(name, 0, out, 24, name.length);
        return out;
    }

    private static byte[] dtlsClientHello() {
        return new byte[]{
                22, (byte) 0xFE, (byte) 0xFD, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x14,
                0x01, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00
        };
    }
}
