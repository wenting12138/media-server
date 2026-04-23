package com.wenting.mediaserver.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public HttpJsonApiHandler(MediaServerConfig config, StreamRegistry registry) {
        this.config = config;
        this.registry = registry;
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
        data.put("transcodeSuffix", config.transcodeOutputSuffix());
        data.put("transcodeInputHost", config.transcodeInputHost());
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
}
