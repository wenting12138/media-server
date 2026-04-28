package com.wenting.mediaserver.api.routes;

import com.wenting.mediaserver.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.util.Collections;
import java.util.Map;

public final class ApiHttpResponder {

    private final ObjectMapper mapper = new ObjectMapper();

    public void sendJson(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, ApiResponse body)
            throws Exception {
        byte[] json = mapper.writeValueAsBytes(body);
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(json));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
        writeAndCloseWhenNeeded(ctx, req, resp);
    }

    public void sendBytes(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, String contentType, byte[] bytes) {
        sendBytes(ctx, req, status, contentType, bytes, Collections.<String, String>emptyMap());
    }

    public void sendBytes(
            ChannelHandlerContext ctx,
            FullHttpRequest req,
            HttpResponseStatus status,
            String contentType,
            byte[] bytes,
            Map<String, String> headers) {
        byte[] safe = bytes == null ? new byte[0] : bytes;
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(safe));
        if (contentType != null && !contentType.isEmpty()) {
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                resp.headers().set(entry.getKey(), entry.getValue());
            }
        }
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, safe.length);
        writeAndCloseWhenNeeded(ctx, req, resp);
    }

    public void sendNoContent(ChannelHandlerContext ctx, FullHttpRequest req) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.NO_CONTENT,
                Unpooled.EMPTY_BUFFER);
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        writeAndCloseWhenNeeded(ctx, req, resp);
    }

    public void sendChunkedOk(ChannelHandlerContext ctx, String contentType, Map<String, String> headers) {
        io.netty.handler.codec.http.HttpResponse resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        HttpUtil.setTransferEncodingChunked(resp, true);
        resp.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                resp.headers().set(entry.getKey(), entry.getValue());
            }
        }
        ctx.writeAndFlush(resp);
    }

    private void writeAndCloseWhenNeeded(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse resp) {
        final boolean keepAlive = HttpUtil.isKeepAlive(req);
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
