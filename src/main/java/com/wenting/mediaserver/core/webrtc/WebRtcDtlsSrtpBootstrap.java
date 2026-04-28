package com.wenting.mediaserver.core.webrtc;

import com.wenting.mediaserver.core.registry.UdpIngressObserver;
import com.wenting.mediaserver.protocol.rtp.RtpUdpMediaPlane;
import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;

/**
 * Observes UDP ingress and updates per-session ICE/DTLS transport state.
 * Real DTLS handshake and SRTP keying will be attached on top in later phase.
 */
public final class WebRtcDtlsSrtpBootstrap implements UdpIngressObserver {

    private final WebRtcSessionManager sessionManager;
    private final RtpUdpMediaPlane rtpUdpMediaPlane;
    private final WebRtcDtlsEngine dtlsEngine;

    public WebRtcDtlsSrtpBootstrap(
            WebRtcSessionManager sessionManager,
            RtpUdpMediaPlane rtpUdpMediaPlane,
            WebRtcDtlsEngine dtlsEngine) {
        this.sessionManager = sessionManager;
        this.rtpUdpMediaPlane = rtpUdpMediaPlane;
        this.dtlsEngine = dtlsEngine;
    }

    @Override
    public void onDatagram(InetSocketAddress sender, InetSocketAddress recipient, ByteBuf payload) {
        if (sessionManager == null || sender == null || payload == null || !payload.isReadable()) {
            return;
        }
        WebRtcSession session = sessionManager.findBySelectedRtpEndpoint(sender);
        WebRtcUdpPacketClassifier.PacketType type = WebRtcUdpPacketClassifier.classify(payload);
        if (type == WebRtcUdpPacketClassifier.PacketType.STUN) {
            String username = WebRtcStunUtil.username(payload);
            if (session == null) {
                session = sessionManager.findByLocalIceUfrag(WebRtcStunUtil.localUfragFromUsername(username));
            }
            if (session == null) {
                return;
            }
            session.onStunConnectivityCheck(sender);
            sendStunBindingSuccess(session, sender, payload, username);
            return;
        }
        if (session == null) {
            return;
        }
        if (type == WebRtcUdpPacketClassifier.PacketType.DTLS) {
            boolean clientHello = WebRtcUdpPacketClassifier.isDtlsClientHello(payload);
            session.onDtlsPacket(clientHello);
            WebRtcDtlsEngineResult result = dtlsEngine == null
                    ? WebRtcDtlsEngineResult.noChange()
                    : dtlsEngine.onPacket(session, payload, clientHello);
            applyDtlsResult(session, sender, result);
            return;
        }
        if (type == WebRtcUdpPacketClassifier.PacketType.RTCP) {
            session.onRtcpIngressPacket();
            return;
        }
        if (type == WebRtcUdpPacketClassifier.PacketType.RTP) {
            session.onRtpIngressPacket();
            return;
        }
        session.onUnknownUdpPacket();
    }

    private void applyDtlsResult(WebRtcSession session, InetSocketAddress sender, WebRtcDtlsEngineResult result) {
        if (session == null || result == null) {
            return;
        }
        if (result.established()) {
            session.markDtlsEstablished();
            if (result.srtpTransformer() != null) {
                session.setSrtpTransformer(result.srtpTransformer());
            }
        }
        if (rtpUdpMediaPlane == null || sender == null) {
            return;
        }
        java.util.List<ByteBuf> packets = result.outboundPackets();
        if (packets == null || packets.isEmpty()) {
            return;
        }
        for (ByteBuf packet : packets) {
            if (packet == null || !packet.isReadable()) {
                io.netty.util.ReferenceCountUtil.safeRelease(packet);
                continue;
            }
            rtpUdpMediaPlane.sendUdpTo(sender, packet);
        }
    }

    private void sendStunBindingSuccess(WebRtcSession session, InetSocketAddress sender, ByteBuf request, String username) {
        if (rtpUdpMediaPlane == null || session == null || sender == null || request == null) {
            return;
        }
        ByteBuf response = WebRtcStunUtil.bindingSuccessResponse(request, sender, session.localIcePwd());
        if (response == null || !response.isReadable()) {
            io.netty.util.ReferenceCountUtil.safeRelease(response);
            session.onStunBindingFailure(username);
            return;
        }
        rtpUdpMediaPlane.sendUdpTo(sender, response);
        session.onStunBindingSuccessSent(username);
    }
}
