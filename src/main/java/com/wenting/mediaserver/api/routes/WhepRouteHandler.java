package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.api.ApiResponse;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.webrtc.WebRtcSession;
import com.wenting.mediaserver.core.webrtc.WebRtcSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public final class WhepRouteHandler {

    private static final Logger log = LoggerFactory.getLogger(WhepRouteHandler.class);

    private final PlaybackStreamResolver playbackResolver;
    private final WebRtcSessionManager sessionManager;
    private final ApiHttpResponder responder;

    public WhepRouteHandler(PlaybackStreamResolver playbackResolver, WebRtcSessionManager sessionManager, ApiHttpResponder responder) {
        this.playbackResolver = playbackResolver;
        this.sessionManager = sessionManager;
        this.responder = responder;
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
        WebRtcSession session = sessionManager.createPlaybackSession(
                stream.key(),
                offerSdp,
                String.valueOf(ctx.channel().remoteAddress()));
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("sessionId", session.id());
        data.put("stream", stream.key().path());
        data.put("resolvedProtocol", stream.key().protocol().name().toLowerCase());
        data.put("requestedProtocol", request.protocol == null ? "auto" : request.protocol.name().toLowerCase());
        data.put("message", "WHEP signaling accepted. WebRTC media plane is not enabled yet.");
        data.put("resourcePath", "/whep/session/" + session.id());
        responder.sendJson(ctx, req, HttpResponseStatus.NOT_IMPLEMENTED, new ApiResponse(501, "whep not implemented", data));
        log.info("WHEP offer accepted request={}/{} protocol={} resolved={} session={}",
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
        session.touch();
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
}
