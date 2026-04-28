package com.wenting.mediaserver.api.routes;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class WebRtcTestPageRouteHandler {

    private static final String PAGE_RESOURCE = "webrtc-test.html";

    private final ApiHttpResponder responder;
    private final byte[] pageBytes;

    public WebRtcTestPageRouteHandler(ApiHttpResponder responder) {
        this.responder = responder;
        this.pageBytes = loadPageBytes();
    }

    public boolean tryHandle(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        if (!"/webrtc-test".equals(path) && !"/webrtc/test".equals(path)) {
            return false;
        }
        responder.sendBytes(
                ctx,
                req,
                HttpResponseStatus.OK,
                "text/html; charset=UTF-8",
                pageBytes);
        return true;
    }

    private static byte[] loadPageBytes() {
        InputStream in = WebRtcTestPageRouteHandler.class.getClassLoader().getResourceAsStream(PAGE_RESOURCE);
        if (in == null) {
            return "<!doctype html><html><body>webrtc test page not found</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return "<!doctype html><html><body>webrtc test page load failed</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
