package com.wenting.mediaserver.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.webrtc.WebRtcSessionManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJsonApiHandlerRoutesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void healthRouteShouldReturnUp() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        WebRtcSessionManager sessionManager = new WebRtcSessionManager(60_000L);
        HttpJsonApiHandler handler = new HttpJsonApiHandler(
                new MediaServerConfig(18080, 1554, 11935, 20000, 30000),
                registry,
                null,
                sessionManager);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/health");
            channel.writeInbound(req);

            FullHttpResponse resp = channel.readOutbound();
            try {
                assertNotNull(resp);
                assertEquals(HttpResponseStatus.OK, resp.status());
                Map<String, Object> body = readJson(resp);
                assertEquals(0, asInt(body.get("code")));
                Map<String, Object> data = asMap(body.get("data"));
                assertEquals("UP", data.get("status"));
            } finally {
                ReferenceCountUtil.safeRelease(resp);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void whepSessionLifecycleShouldBeReachable() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        StreamKey key = new StreamKey(StreamProtocol.RTMP, "live", "cam_webrtc");
        EmbeddedChannel publisher = new EmbeddedChannel();
        PublishedStream published = registry.tryPublish(key, "pub1", "v=0\r\n", publisher).get();

        WebRtcSessionManager sessionManager = new WebRtcSessionManager(60_000L);
        HttpJsonApiHandler handler = new HttpJsonApiHandler(
                new MediaServerConfig(18080, 1554, 11935, 20000, 30000),
                registry,
                null,
                sessionManager);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            FullHttpRequest offer = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/whep/rtmp/live/cam_webrtc",
                    Unpooled.copiedBuffer("v=0\r\n", CharsetUtil.UTF_8));
            offer.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, offer.content().readableBytes());
            channel.writeInbound(offer);

            FullHttpResponse offerResp = channel.readOutbound();
            String sessionId;
            try {
                assertNotNull(offerResp);
                assertEquals(HttpResponseStatus.NOT_IMPLEMENTED, offerResp.status());
                Map<String, Object> body = readJson(offerResp);
                Map<String, Object> data = asMap(body.get("data"));
                sessionId = String.valueOf(data.get("sessionId"));
                assertTrue(sessionId != null && !sessionId.isEmpty());
            } finally {
                ReferenceCountUtil.safeRelease(offerResp);
            }

            FullHttpRequest listReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/index/api/getWebRtcSessionList");
            channel.writeInbound(listReq);
            FullHttpResponse listResp = channel.readOutbound();
            try {
                assertNotNull(listResp);
                assertEquals(HttpResponseStatus.OK, listResp.status());
                Map<String, Object> body = readJson(listResp);
                Map<String, Object> data = asMap(body.get("data"));
                assertEquals(1, asInt(data.get("sessionCount")));
            } finally {
                ReferenceCountUtil.safeRelease(listResp);
            }

            FullHttpRequest delReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.DELETE, "/whep/session/" + sessionId);
            channel.writeInbound(delReq);
            FullHttpResponse delResp = channel.readOutbound();
            try {
                assertNotNull(delResp);
                assertEquals(HttpResponseStatus.OK, delResp.status());
            } finally {
                ReferenceCountUtil.safeRelease(delResp);
            }

            FullHttpRequest listReqAfter = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/index/api/getWebRtcSessionList");
            channel.writeInbound(listReqAfter);
            FullHttpResponse listRespAfter = channel.readOutbound();
            try {
                assertNotNull(listRespAfter);
                assertEquals(HttpResponseStatus.OK, listRespAfter.status());
                Map<String, Object> body = readJson(listRespAfter);
                Map<String, Object> data = asMap(body.get("data"));
                assertEquals(0, asInt(data.get("sessionCount")));
            } finally {
                ReferenceCountUtil.safeRelease(listRespAfter);
            }
        } finally {
            channel.finishAndReleaseAll();
            registry.unpublish(key, published.publisherSession().id());
            publisher.finishAndReleaseAll();
        }
    }

    @Test
    void whepMethodValidationShouldWork() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        HttpJsonApiHandler handler = new HttpJsonApiHandler(
                new MediaServerConfig(18080, 1554, 11935, 20000, 30000),
                registry,
                null,
                new WebRtcSessionManager(60_000L));
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            FullHttpRequest badWhep = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/whep/live/cam1");
            channel.writeInbound(badWhep);
            FullHttpResponse badWhepResp = channel.readOutbound();
            try {
                assertNotNull(badWhepResp);
                assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, badWhepResp.status());
            } finally {
                ReferenceCountUtil.safeRelease(badWhepResp);
            }

            FullHttpRequest badSession = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/whep/session/not-exist");
            channel.writeInbound(badSession);
            FullHttpResponse badSessionResp = channel.readOutbound();
            try {
                assertNotNull(badSessionResp);
                assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, badSessionResp.status());
            } finally {
                ReferenceCountUtil.safeRelease(badSessionResp);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(FullHttpResponse resp) throws Exception {
        byte[] bytes = new byte[resp.content().readableBytes()];
        resp.content().getBytes(resp.content().readerIndex(), bytes);
        return MAPPER.readValue(bytes, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static int asInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
