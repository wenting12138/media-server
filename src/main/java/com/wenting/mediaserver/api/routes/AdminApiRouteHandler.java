package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.api.ApiResponse;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
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
            sessions.add(row);
        }
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("sessionCount", webRtcSessionManager.sessionCount());
        data.put("sessions", sessions);
        return ApiResponse.ok(data);
    }
}
