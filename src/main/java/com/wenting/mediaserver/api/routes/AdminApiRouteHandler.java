package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.api.ApiResponse;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.webrtc.WebRtcDtlsMode;
import com.wenting.mediaserver.core.webrtc.WebRtcSession;
import com.wenting.mediaserver.core.webrtc.WebRtcSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AdminApiRouteHandler {

    private final MediaServerConfig config;
    private final com.wenting.mediaserver.core.registry.StreamRegistry registry;
    private final WebRtcSessionManager webRtcSessionManager;
    private final ApiHttpResponder responder;

    public AdminApiRouteHandler(
            MediaServerConfig config,
            com.wenting.mediaserver.core.registry.StreamRegistry registry,
            WebRtcSessionManager webRtcSessionManager,
            ApiHttpResponder responder) {
        this.config = config;
        this.registry = registry;
        this.webRtcSessionManager = webRtcSessionManager;
        this.responder = responder;
    }

    public boolean tryHandle(ChannelHandlerContext ctx, FullHttpRequest req, String path) throws Exception {
        if ("/index/api/getServerConfig".equals(path)) {
            responder.sendJson(ctx, req, HttpResponseStatus.OK, serverConfig());
            return true;
        }
        if ("/index/api/getMediaList".equals(path)) {
            responder.sendJson(ctx, req, HttpResponseStatus.OK, mediaList());
            return true;
        }
        if ("/index/api/getWebRtcSessionList".equals(path)) {
            responder.sendJson(ctx, req, HttpResponseStatus.OK, webRtcSessionList());
            return true;
        }
        if ("/health".equals(path)) {
            responder.sendJson(ctx, req, HttpResponseStatus.OK, ApiResponse.okSimple("status", "UP"));
            return true;
        }
        return false;
    }

    private ApiResponse serverConfig() {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("httpPort", config.httpPort());
        data.put("rtspPort", config.rtspPort());
        data.put("rtmpPort", config.rtmpPort());
        data.put("transcodeEnabled", config.transcodeEnabled());
        data.put("rtmpTranscoder", config.rtmpTranscoder());
        data.put("javaVisibleWatermarkEnabled", config.javaVisibleWatermarkEnabled());
        data.put("transcodeSuffix", config.transcodeOutputSuffix());
        data.put("transcodeInputHost", config.transcodeInputHost());
        data.put("hlsEnabled", config.hlsEnabled());
        data.put("hlsRoot", config.hlsRoot());
        data.put("hlsSegmentSeconds", config.hlsSegmentSeconds());
        data.put("hlsListSize", config.hlsListSize());
        data.put("hlsDeleteSegments", config.hlsDeleteSegments());
        WebRtcDtlsMode dtlsMode = WebRtcDtlsMode.parse(System.getenv("MEDIA_WEBRTC_DTLS_MODE"), WebRtcDtlsMode.REAL);
        data.put("webrtcDtlsMode", dtlsMode.name().toLowerCase());
        data.put("webrtcPseudoAllowPlainRtp", parseBoolean(System.getenv("MEDIA_WEBRTC_PSEUDO_ALLOW_PLAIN_RTP"), true));
        data.put("version", config.version());
        data.put("serverId", config.serverId());
        return ApiResponse.ok(data);
    }

    private ApiResponse mediaList() {
        List<String> streams = new ArrayList<String>();
        for (StreamKey key : registry.listPublishedKeys()) {
            streams.add(key.path());
        }
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("streamCount", registry.publisherCount());
        data.put("streams", streams);
        return ApiResponse.ok(data);
    }

    private ApiResponse webRtcSessionList() {
        List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
        for (WebRtcSession session : webRtcSessionManager.listSessions()) {
            if (session == null) {
                continue;
            }
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("sessionId", session.id());
            row.put("stream", session.streamKey() == null ? null : session.streamKey().path());
            row.put("protocol", session.streamKey() == null ? null : session.streamKey().protocol().name().toLowerCase());
            row.put("createdAtMs", session.createdAtMs());
            row.put("lastSeenAtMs", session.lastSeenAtMs());
            row.put("remoteAddress", session.remoteAddress());
            row.put("remoteIceUfrag", session.remoteIceUfrag());
            row.put("remoteIcePwd", session.remoteIcePwd());
            row.put("remoteCandidateCount", session.remoteCandidateCount());
            row.put("remoteEndOfCandidates", session.remoteEndOfCandidates());
            row.put("remoteOfferFingerprint", session.remoteOfferFingerprint());
            row.put("remoteOfferSetupRole", session.remoteOfferSetupRole());
            row.put("remoteOfferMediaCount", session.remoteOfferMediaCount());
            row.put("remoteOfferAudioMediaCount", session.remoteOfferAudioMediaCount());
            row.put("remoteOfferVideoMediaCount", session.remoteOfferVideoMediaCount());
            row.put("selectedRtpCandidateHost", session.selectedRtpCandidateHost());
            row.put("selectedRtpCandidatePort", session.selectedRtpCandidatePort());
            row.put("selectedCandidateType", session.selectedCandidateType());
            row.put("selectedCandidateTransport", session.selectedCandidateTransport());
            row.put("transportState", session.transportState() == null ? null : session.transportState().name().toLowerCase());
            row.put("iceConnectedAtMs", session.iceConnectedAtMs());
            row.put("dtlsClientHelloAtMs", session.dtlsClientHelloAtMs());
            row.put("dtlsEstablishedAtMs", session.dtlsEstablishedAtMs());
            row.put("srtpReady", session.srtpReady());
            row.put("mediaAttachedAtMs", session.mediaAttachedAtMs());
            row.put("mediaLastPacketAtMs", session.mediaLastPacketAtMs());
            row.put("mediaSourceProtocol", session.mediaSourceProtocol());
            row.put("mediaPayloadFormat", session.mediaPayloadFormat());
            row.put("mediaVideoConfigSeen", session.mediaVideoConfigSeen());
            row.put("mediaVideoKeyFrameSeen", session.mediaVideoKeyFrameSeen());
            row.put("mediaVideoPackets", session.mediaVideoPackets());
            row.put("mediaAudioPackets", session.mediaAudioPackets());
            row.put("mediaDataPackets", session.mediaDataPackets());
            row.put("plainRtpRelayPackets", session.plainRtpRelayPackets());
            row.put("plainRtpRelayBytes", session.plainRtpRelayBytes());
            row.put("srtpProtectedPackets", session.srtpProtectedPackets());
            row.put("srtpProtectedBytes", session.srtpProtectedBytes());
            row.put("stunIngressPackets", session.stunIngressPackets());
            row.put("stunBindingSuccessPackets", session.stunBindingSuccessPackets());
            row.put("stunBindingFailurePackets", session.stunBindingFailurePackets());
            row.put("lastStunUsername", session.lastStunUsername());
            row.put("dtlsIngressPackets", session.dtlsIngressPackets());
            row.put("rtpIngressPackets", session.rtpIngressPackets());
            row.put("rtcpIngressPackets", session.rtcpIngressPackets());
            row.put("unknownUdpIngressPackets", session.unknownUdpIngressPackets());
            sessions.add(row);
        }
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("sessionCount", webRtcSessionManager.sessionCount());
        data.put("sessions", sessions);
        return ApiResponse.ok(data);
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String v = raw.trim().toLowerCase();
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "off".equals(v)) {
            return false;
        }
        return fallback;
    }
}
