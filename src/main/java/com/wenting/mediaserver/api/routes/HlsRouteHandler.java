package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.api.ApiResponse;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.hls.HlsStreamFrameProcessor;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class HlsRouteHandler {

    private final MediaServerConfig config;
    private final HlsStreamFrameProcessor hlsProcessor;
    private final ApiHttpResponder responder;
    private final Path hlsRoot;

    public HlsRouteHandler(MediaServerConfig config, HlsStreamFrameProcessor hlsProcessor, ApiHttpResponder responder) {
        this.config = config;
        this.hlsProcessor = hlsProcessor;
        this.responder = responder;
        this.hlsRoot = Paths.get(config.hlsRoot()).toAbsolutePath().normalize();
    }

    public boolean tryHandle(ChannelHandlerContext ctx, FullHttpRequest req, String path) throws Exception {
        if (path == null || !path.startsWith("/hls/")) {
            return false;
        }
        if (!config.hlsEnabled()) {
            responder.sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "hls disabled"));
            return true;
        }
        String rel = path.substring("/hls/".length());
        if (rel.isEmpty()) {
            responder.sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "hls file not found"));
            return true;
        }
        if (hlsProcessor != null) {
            hlsProcessor.onHlsRequest(rel);
        }
        Path file = hlsRoot.resolve(rel).normalize();
        if (!file.startsWith(hlsRoot)) {
            responder.sendJson(ctx, req, HttpResponseStatus.FORBIDDEN, ApiResponse.error(403, "forbidden"));
            return true;
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            responder.sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "hls file not found"));
            return true;
        }
        byte[] bytes = Files.readAllBytes(file);
        responder.sendBytes(ctx, req, HttpResponseStatus.OK, hlsContentType(file), bytes);
        return true;
    }

    private String hlsContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (name.endsWith(".ts")) {
            return "video/mp2t";
        }
        return "application/octet-stream";
    }
}
