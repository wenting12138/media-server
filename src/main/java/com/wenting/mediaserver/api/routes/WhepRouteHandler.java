package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.api.ApiResponse;
import com.wenting.mediaserver.core.webrtc.WebRtcIceSdpFragment;
import com.wenting.mediaserver.core.webrtc.WebRtcOfferInfo;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.webrtc.WebRtcSdpAnswerGenerator;
import com.wenting.mediaserver.core.webrtc.WebRtcSession;
import com.wenting.mediaserver.core.webrtc.WebRtcSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

public final class WhepRouteHandler {

    private static final Logger log = LoggerFactory.getLogger(WhepRouteHandler.class);

    private final PlaybackStreamResolver playbackResolver;
    private final WebRtcSessionManager sessionManager;
    private final ApiHttpResponder responder;
    private final WebRtcSdpAnswerGenerator sdpAnswerGenerator;
    private final IntSupplier localCandidatePortSupplier;

    public WhepRouteHandler(PlaybackStreamResolver playbackResolver, WebRtcSessionManager sessionManager, ApiHttpResponder responder) {
        this(playbackResolver, sessionManager, responder, null);
    }

    public WhepRouteHandler(
            PlaybackStreamResolver playbackResolver,
            WebRtcSessionManager sessionManager,
            ApiHttpResponder responder,
            String localDtlsFingerprint) {
        this(playbackResolver, sessionManager, responder, localDtlsFingerprint, null);
    }

    public WhepRouteHandler(
            PlaybackStreamResolver playbackResolver,
            WebRtcSessionManager sessionManager,
            ApiHttpResponder responder,
            String localDtlsFingerprint,
            IntSupplier localCandidatePortSupplier) {
        this.playbackResolver = playbackResolver;
        this.sessionManager = sessionManager;
        this.responder = responder;
        this.sdpAnswerGenerator = new WebRtcSdpAnswerGenerator(localDtlsFingerprint);
        this.localCandidatePortSupplier = localCandidatePortSupplier;
    }

    public boolean tryHandle(ChannelHandlerContext ctx, FullHttpRequest req, String path) throws Exception {
        String sessionId = PlaybackPathParser.parseWhepSessionId(path);
        if (sessionId != null) {
            handleSessionResource(ctx, req, sessionId);
            return true;
        }
        PlaybackRequest request = PlaybackPathParser.parseWhepPlayRequest(path);
        if (request != null) {
            handleOffer(ctx, req, request);
            return true;
        }
        return false;
    }

    private void handleOffer(ChannelHandlerContext ctx, FullHttpRequest req, PlaybackRequest request) throws Exception {
        if (req.method() != HttpMethod.POST) {
            responder.sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, ApiResponse.error(405, "whep requires POST"));
            return;
        }
        String offerSdp = req.content().toString(CharsetUtil.UTF_8);
        if (offerSdp == null || offerSdp.trim().isEmpty()) {
            responder.sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(400, "whep offer sdp is empty"));
            return;
        }
        PublishedStream stream = playbackResolver.resolve(request.app, request.stream, request.protocol);
        if (stream == null) {
            responder.sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "stream not found"));
            return;
        }
        WebRtcOfferInfo offerInfo;
        try {
            offerInfo = WebRtcOfferInfo.parse(offerSdp);
        } catch (IllegalArgumentException e) {
            responder.sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(400, "invalid whep offer: " + e.getMessage()));
            return;
        }
        WebRtcSession session = sessionManager.createPlaybackSession(
                stream.key(),
                offerSdp,
                String.valueOf(ctx.channel().remoteAddress()));
        session.applyOfferInfo(offerInfo);
        String candidateIp = resolveLocalIp(ctx);
        String answerSdp;
        try {
            answerSdp = sdpAnswerGenerator.createAnswer(offerSdp, session.id(), candidateIp, resolveLocalCandidatePort());
        } catch (IllegalArgumentException e) {
            sessionManager.remove(session.id());
            responder.sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(400, "invalid whep offer: " + e.getMessage()));
            return;
        }
        session.setAnswerSdp(answerSdp);
        String resourcePath = "/whep/session/" + session.id();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put(HttpHeaderNames.LOCATION.toString(), resourcePath);
        headers.put(HttpHeaderNames.ETAG.toString(), "\"" + session.id() + "\"");
        headers.put("Accept-Patch", "application/trickle-ice-sdpfrag");
        responder.sendBytes(
                ctx,
                req,
                HttpResponseStatus.CREATED,
                "application/sdp",
                answerSdp.getBytes(CharsetUtil.UTF_8),
                headers);
        log.info("WHEP offer answered request={}/{} protocol={} resolved={} session={}",
                request.app,
                request.stream,
                request.protocol == null ? "auto" : request.protocol.name().toLowerCase(),
                stream.key(),
                session.id());
    }

    private void handleSessionResource(ChannelHandlerContext ctx, FullHttpRequest req, String sessionId) throws Exception {
        if (req.method() == HttpMethod.PATCH) {
            handlePatchCandidate(ctx, req, sessionId);
            return;
        }
        if (req.method() == HttpMethod.DELETE) {
            handleDeleteSession(ctx, req, sessionId);
            return;
        }
        responder.sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, ApiResponse.error(405, "whep session requires PATCH or DELETE"));
    }

    private void handlePatchCandidate(ChannelHandlerContext ctx, FullHttpRequest req, String sessionId) throws Exception {
        WebRtcSession session = sessionManager.get(sessionId);
        if (session == null) {
            responder.sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "whep session not found"));
            return;
        }
        if (!ifMatchAccepted(req.headers().get(HttpHeaderNames.IF_MATCH), session.id())) {
            responder.sendJson(ctx, req, HttpResponseStatus.PRECONDITION_FAILED, ApiResponse.error(412, "etag mismatch"));
            return;
        }
        String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null && !contentType.trim().isEmpty()) {
            String lower = contentType.toLowerCase();
            if (!lower.startsWith("application/trickle-ice-sdpfrag") && !lower.startsWith("application/sdp")) {
                responder.sendJson(ctx, req, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, ApiResponse.error(415, "unsupported content-type for whep patch"));
                return;
            }
        }
        String sdpFrag = req.content().toString(CharsetUtil.UTF_8);
        WebRtcIceSdpFragment fragment;
        try {
            fragment = WebRtcIceSdpFragment.parse(sdpFrag);
        } catch (IllegalArgumentException e) {
            responder.sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(400, "invalid trickle sdpfrag: " + e.getMessage()));
            return;
        }
        session.applyRemoteIceFragment(fragment);
        responder.sendNoContent(ctx, req);
    }

    private void handleDeleteSession(ChannelHandlerContext ctx, FullHttpRequest req, String sessionId) throws Exception {
        WebRtcSession removed = sessionManager.remove(sessionId);
        if (removed == null) {
            responder.sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "whep session not found"));
            return;
        }
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("sessionId", removed.id());
        data.put("stream", removed.streamKey() == null ? null : removed.streamKey().path());
        responder.sendJson(ctx, req, HttpResponseStatus.OK, ApiResponse.ok(data));
    }

    private static String resolveLocalIp(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null) {
            return "127.0.0.1";
        }
        java.net.SocketAddress local = ctx.channel().localAddress();
        if (local instanceof InetSocketAddress) {
            InetSocketAddress inet = (InetSocketAddress) local;
            if (inet.getAddress() != null) {
                String ip = inet.getAddress().getHostAddress();
                if (ip != null && !ip.trim().isEmpty()) {
                    return ip;
                }
            }
            String host = inet.getHostString();
            if (host != null && !host.trim().isEmpty()) {
                return host;
            }
        }
        return "127.0.0.1";
    }

    private int resolveLocalCandidatePort() {
        if (localCandidatePortSupplier == null) {
            return 9;
        }
        try {
            return localCandidatePortSupplier.getAsInt();
        } catch (RuntimeException e) {
            return 9;
        }
    }

    private static boolean ifMatchAccepted(String ifMatchHeader, String sessionId) {
        if (ifMatchHeader == null || ifMatchHeader.trim().isEmpty()) {
            return true;
        }
        String value = ifMatchHeader.trim();
        if ("*".equals(value)) {
            return true;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        return value.equals(sessionId) || value.equals("\"" + sessionId + "\"");
    }
}
