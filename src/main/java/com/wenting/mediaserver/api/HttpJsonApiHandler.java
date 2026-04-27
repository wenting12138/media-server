package com.wenting.mediaserver.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.hls.HlsStreamFrameProcessor;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal admin HTTP API. Paths mirror a subset of ZLM's {@code /index/api/*} style for familiarity.
 */
public final class HttpJsonApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonApiHandler.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaServerConfig config;
    private final StreamRegistry registry;
    private final HlsStreamFrameProcessor hlsProcessor;
    private final Path hlsRoot;

    public HttpJsonApiHandler(MediaServerConfig config, StreamRegistry registry, HlsStreamFrameProcessor hlsProcessor) {
        this.config = config;
        this.registry = registry;
        this.hlsProcessor = hlsProcessor;
        this.hlsRoot = Paths.get(config.hlsRoot()).toAbsolutePath().normalize();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (!req.decoderResult().isSuccess()) {
            send(ctx, req, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(400, "bad request"));
            return;
        }

        if (req.method() != HttpMethod.GET) {
            send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, ApiResponse.error(405, "method not allowed"));
            return;
        }

        String uri = req.uri();
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        HttpFlvRequest flvRequest = parseHttpFlvRequest(path);
        if (flvRequest != null) {
            serveHttpFlv(ctx, req, flvRequest);
            return;
        }
        if (path.startsWith("/hls/")) {
            serveHlsFile(ctx, req, path);
            return;
        }

        try {
            if ("/index/api/getServerConfig".equals(path)) {
                send(ctx, req, HttpResponseStatus.OK, serverConfig());
            } else if ("/index/api/getMediaList".equals(path)) {
                send(ctx, req, HttpResponseStatus.OK, mediaList());
            } else if ("/health".equals(path)) {
                send(ctx, req, HttpResponseStatus.OK, ApiResponse.okSimple("status", "UP"));
            } else {
                send(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "not found: " + path));
            }
        } catch (Exception e) {
            log.warn("API error", e);
            send(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, ApiResponse.error(500, "internal error"));
        }
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

    private void send(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, ApiResponse body)
            throws Exception {
        byte[] json = mapper.writeValueAsBytes(body);
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(json));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());

        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        }

        ctx.writeAndFlush(resp).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(io.netty.channel.ChannelFuture future) {
                if (!keepAlive) {
                    future.channel().close();
                }
            }
        });
    }

    private void serveHlsFile(ChannelHandlerContext ctx, FullHttpRequest req, String path) throws Exception {
        if (!config.hlsEnabled()) {
            send(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "hls disabled"));
            return;
        }
        String rel = path.substring("/hls/".length());
        if (rel.isEmpty()) {
            send(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "hls file not found"));
            return;
        }
        if (hlsProcessor != null) {
            hlsProcessor.onHlsRequest(rel);
        }
        Path file = hlsRoot.resolve(rel).normalize();
        if (!file.startsWith(hlsRoot)) {
            send(ctx, req, HttpResponseStatus.FORBIDDEN, ApiResponse.error(403, "forbidden"));
            return;
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            send(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "hls file not found"));
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(bytes));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, hlsContentType(file));
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        }
        ctx.writeAndFlush(resp).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(io.netty.channel.ChannelFuture future) {
                if (!keepAlive) {
                    future.channel().close();
                }
            }
        });
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

    private void serveHttpFlv(ChannelHandlerContext ctx, FullHttpRequest req, HttpFlvRequest flvRequest) throws Exception {
        PublishedStream stream = resolvePlayback(flvRequest.app, flvRequest.stream, flvRequest.protocol);
        if (stream == null) {
            send(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "stream not found"));
            return;
        }
        HttpResponse resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "video/x-flv");
        resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        resp.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
        resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        HttpUtil.setTransferEncodingChunked(resp, true);
        resp.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        ctx.writeAndFlush(resp);

        stream.addHttpFlvSubscriber(ctx);
        ctx.channel().closeFuture().addListener(future -> stream.removeHttpFlvSubscriber(ctx));
        log.info("HTTP-FLV play start request={}/{} protocol={} resolved={}",
                flvRequest.app,
                flvRequest.stream,
                flvRequest.protocol == null ? "auto" : flvRequest.protocol.name().toLowerCase(),
                stream.key().path());
    }

    private PublishedStream resolvePlayback(String app, String stream, StreamProtocol forceProtocol) {
        if (forceProtocol != null) {
            StreamKey forced = new StreamKey(forceProtocol, app, stream);
            java.util.Optional<PublishedStream> found = registry.publishedForPlayback(forced);
            return found.orElse(null);
        }
        StreamProtocol[] order = new StreamProtocol[] {
                StreamProtocol.RTMP,
                StreamProtocol.RTSP,
                StreamProtocol.UNKNOWN
        };
        for (StreamProtocol protocol : order) {
            StreamKey key = new StreamKey(protocol, app, stream);
            java.util.Optional<PublishedStream> found = registry.publishedForPlayback(key);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }

    private HttpFlvRequest parseHttpFlvRequest(String path) {
        if (path == null || !path.startsWith("/live/") || !path.endsWith(".flv")) {
            return null;
        }
        String rel = path.substring("/live/".length(), path.length() - ".flv".length());
        if (rel.isEmpty()) {
            return null;
        }
        String[] parts = rel.split("/");
        StreamProtocol forceProtocol = null;
        String app;
        String stream;
        if (parts.length == 1) {
            app = "live";
            stream = parts[0];
        } else if (parts.length == 2) {
            StreamProtocol maybeProtocol = parseProtocol(parts[0]);
            if (maybeProtocol != null) {
                forceProtocol = maybeProtocol;
                app = "live";
                stream = parts[1];
            } else {
                app = parts[0];
                stream = parts[1];
            }
        } else if (parts.length == 3) {
            StreamProtocol maybeProtocol = parseProtocol(parts[0]);
            if (maybeProtocol == null) {
                return null;
            }
            forceProtocol = maybeProtocol;
            app = parts[1];
            stream = parts[2];
        } else {
            return null;
        }
        if (app.trim().isEmpty() || stream.trim().isEmpty()) {
            return null;
        }
        return new HttpFlvRequest(app, stream, forceProtocol);
    }

    private StreamProtocol parseProtocol(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        if ("rtsp".equals(v)) {
            return StreamProtocol.RTSP;
        }
        if ("rtmp".equals(v)) {
            return StreamProtocol.RTMP;
        }
        if ("unknown".equals(v)) {
            return StreamProtocol.UNKNOWN;
        }
        return null;
    }

    private static final class HttpFlvRequest {
        final String app;
        final String stream;
        final StreamProtocol protocol;

        HttpFlvRequest(String app, String stream, StreamProtocol protocol) {
            this.app = app;
            this.stream = stream;
            this.protocol = protocol;
        }
    }
}
