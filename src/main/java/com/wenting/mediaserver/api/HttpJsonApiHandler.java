package com.wenting.mediaserver.api;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.hls.HlsStreamFrameProcessor;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.webrtc.WebRtcSessionManager;
import com.wenting.mediaserver.api.routes.AdminApiRouteHandler;
import com.wenting.mediaserver.api.routes.ApiHttpResponder;
import com.wenting.mediaserver.api.routes.HlsRouteHandler;
import com.wenting.mediaserver.api.routes.HttpFlvRouteHandler;
import com.wenting.mediaserver.api.routes.PlaybackStreamResolver;
import com.wenting.mediaserver.api.routes.WebRtcTestPageRouteHandler;
import com.wenting.mediaserver.api.routes.WhepRouteHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntSupplier;

/**
 * Minimal admin HTTP API. Paths mirror a subset of ZLM's {@code /index/api/*} style for familiarity.
 */
public final class HttpJsonApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonApiHandler.class);

    private final WebRtcSessionManager webRtcSessionManager;
    private final ApiHttpResponder responder;
    private final WhepRouteHandler whepRouteHandler;
    private final HttpFlvRouteHandler httpFlvRouteHandler;
    private final HlsRouteHandler hlsRouteHandler;
    private final WebRtcTestPageRouteHandler webRtcTestPageRouteHandler;
    private final AdminApiRouteHandler adminApiRouteHandler;

    public HttpJsonApiHandler(
            MediaServerConfig config,
            StreamRegistry registry,
            HlsStreamFrameProcessor hlsProcessor,
            WebRtcSessionManager webRtcSessionManager) {
        this(config, registry, hlsProcessor, webRtcSessionManager, null);
    }

    public HttpJsonApiHandler(
            MediaServerConfig config,
            StreamRegistry registry,
            HlsStreamFrameProcessor hlsProcessor,
            WebRtcSessionManager webRtcSessionManager,
            String webRtcLocalFingerprint) {
        this(config, registry, hlsProcessor, webRtcSessionManager, webRtcLocalFingerprint, null);
    }

    public HttpJsonApiHandler(
            MediaServerConfig config,
            StreamRegistry registry,
            HlsStreamFrameProcessor hlsProcessor,
            WebRtcSessionManager webRtcSessionManager,
            String webRtcLocalFingerprint,
            IntSupplier webRtcLocalCandidatePortSupplier) {
        this.webRtcSessionManager = webRtcSessionManager == null ? new WebRtcSessionManager() : webRtcSessionManager;
        this.responder = new ApiHttpResponder();
        PlaybackStreamResolver playbackResolver = new PlaybackStreamResolver(registry);
        this.whepRouteHandler = new WhepRouteHandler(
                playbackResolver,
                this.webRtcSessionManager,
                responder,
                webRtcLocalFingerprint,
                webRtcLocalCandidatePortSupplier);
        this.httpFlvRouteHandler = new HttpFlvRouteHandler(playbackResolver, responder);
        this.hlsRouteHandler = new HlsRouteHandler(config, hlsProcessor, responder);
        this.webRtcTestPageRouteHandler = new WebRtcTestPageRouteHandler(responder);
        this.adminApiRouteHandler = new AdminApiRouteHandler(config, registry, this.webRtcSessionManager, responder);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (!req.decoderResult().isSuccess()) {
            responder.sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(400, "bad request"));
            return;
        }
        webRtcSessionManager.cleanupExpired(System.currentTimeMillis());
        try {
            String path = extractPath(req.uri());
            if (whepRouteHandler.tryHandle(ctx, req, path)) {
                return;
            }
            if (req.method() != HttpMethod.GET) {
                responder.sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, ApiResponse.error(405, "method not allowed"));
                return;
            }
            if (httpFlvRouteHandler.tryHandle(ctx, req, path)) {
                return;
            }
            if (hlsRouteHandler.tryHandle(ctx, req, path)) {
                return;
            }
            if (webRtcTestPageRouteHandler.tryHandle(ctx, req, path)) {
                return;
            }
            if (adminApiRouteHandler.tryHandle(ctx, req, path)) {
                return;
            }
            responder.sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "not found: " + path));
        } catch (Exception e) {
            log.warn("API error", e);
            responder.sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, ApiResponse.error(500, "internal error"));
        }
    }

    private String extractPath(String uri) {
        if (uri == null) {
            return "";
        }
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }
}
