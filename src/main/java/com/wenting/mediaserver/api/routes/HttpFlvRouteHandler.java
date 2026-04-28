package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.api.ApiResponse;
import com.wenting.mediaserver.core.publish.PublishedStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public final class HttpFlvRouteHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpFlvRouteHandler.class);

    private final PlaybackStreamResolver playbackResolver;
    private final ApiHttpResponder responder;

    public HttpFlvRouteHandler(PlaybackStreamResolver playbackResolver, ApiHttpResponder responder) {
        this.playbackResolver = playbackResolver;
        this.responder = responder;
    }

    public boolean tryHandle(ChannelHandlerContext ctx, FullHttpRequest req, String path) throws Exception {
        PlaybackRequest flvRequest = PlaybackPathParser.parseHttpFlvRequest(path);
        if (flvRequest == null) {
            return false;
        }
        PublishedStream stream = playbackResolver.resolve(flvRequest.app, flvRequest.stream, flvRequest.protocol);
        if (stream == null) {
            responder.sendJson(ctx, req, HttpResponseStatus.NOT_FOUND, ApiResponse.error(404, "stream not found"));
            return true;
        }
        Map<String, String> headers = new HashMap<String, String>();
        headers.put(HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache");
        headers.put(HttpHeaderNames.PRAGMA.toString(), "no-cache");
        headers.put(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN.toString(), "*");
        responder.sendChunkedOk(ctx, "video/x-flv", headers);

        stream.addHttpFlvSubscriber(ctx);
        ctx.channel().closeFuture().addListener(future -> stream.removeHttpFlvSubscriber(ctx));
        log.info("HTTP-FLV play start request={}/{} protocol={} resolved={}",
                flvRequest.app,
                flvRequest.stream,
                flvRequest.protocol == null ? "auto" : flvRequest.protocol.name().toLowerCase(),
                stream.key().path());
        return true;
    }
}
