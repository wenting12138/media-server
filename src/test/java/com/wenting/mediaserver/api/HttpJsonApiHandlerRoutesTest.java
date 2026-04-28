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
                sessionManager,
                null,
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return 20000;
                    }
                });
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
    void webrtcTestPageShouldBeReachable() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        WebRtcSessionManager sessionManager = new WebRtcSessionManager(60_000L);
        HttpJsonApiHandler handler = new HttpJsonApiHandler(
                new MediaServerConfig(18080, 1554, 11935, 20000, 30000),
                registry,
                null,
                sessionManager,
                null,
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return 20000;
                    }
                });
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/webrtc-test");
            channel.writeInbound(req);
            FullHttpResponse resp = channel.readOutbound();
            try {
                assertNotNull(resp);
                assertEquals(HttpResponseStatus.OK, resp.status());
                String contentType = String.valueOf(resp.headers().get(HttpHeaderNames.CONTENT_TYPE));
                assertTrue(contentType.startsWith("text/html"));
                String body = resp.content().toString(CharsetUtil.UTF_8);
                assertTrue(body.contains("WHEP Playback"));
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
                sessionManager,
                null,
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return 20000;
                    }
                });
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            FullHttpRequest offer = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/whep/rtmp/live/cam_webrtc",
                    Unpooled.copiedBuffer(minimalWhepOffer(), CharsetUtil.UTF_8));
            offer.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, offer.content().readableBytes());
            channel.writeInbound(offer);

            FullHttpResponse offerResp = channel.readOutbound();
            String sessionId;
            try {
                assertNotNull(offerResp);
                assertEquals(HttpResponseStatus.CREATED, offerResp.status());
                assertTrue(String.valueOf(offerResp.headers().get(HttpHeaderNames.CONTENT_TYPE)).startsWith("application/sdp"));
                String location = offerResp.headers().get(HttpHeaderNames.LOCATION);
                assertNotNull(location);
                assertTrue(location.startsWith("/whep/session/"));
                sessionId = location.substring("/whep/session/".length());
                assertTrue(sessionId != null && !sessionId.isEmpty());
                String answerSdp = offerResp.content().toString(CharsetUtil.UTF_8);
                assertTrue(answerSdp.contains("m=video 9 UDP/TLS/RTP/SAVPF"));
                assertTrue(answerSdp.contains("a=sendonly"));
                assertTrue(answerSdp.contains(" 20000 typ host"));
            } finally {
                ReferenceCountUtil.safeRelease(offerResp);
            }

            FullHttpRequest patchReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.PATCH,
                    "/whep/session/" + sessionId,
                    Unpooled.copiedBuffer(minimalTrickleSdpFrag(), CharsetUtil.UTF_8));
            patchReq.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/trickle-ice-sdpfrag");
            patchReq.headers().set(HttpHeaderNames.IF_MATCH, "\"" + sessionId + "\"");
            patchReq.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, patchReq.content().readableBytes());
            channel.writeInbound(patchReq);
            FullHttpResponse patchResp = channel.readOutbound();
            try {
                assertNotNull(patchResp);
                assertEquals(HttpResponseStatus.NO_CONTENT, patchResp.status());
            } finally {
                ReferenceCountUtil.safeRelease(patchResp);
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
                Object first = ((java.util.List<?>) data.get("sessions")).get(0);
                Map<String, Object> firstSession = asMap(first);
                assertEquals("actpass", String.valueOf(firstSession.get("remoteOfferSetupRole")));
                assertEquals(1, asInt(firstSession.get("remoteOfferVideoMediaCount")));
                assertEquals(1, asInt(firstSession.get("remoteCandidateCount")));
                assertEquals(true, firstSession.get("remoteEndOfCandidates"));
                assertEquals("127.0.0.1", firstSession.get("selectedRtpCandidateHost"));
                assertEquals(61234, asInt(firstSession.get("selectedRtpCandidatePort")));
                assertEquals("udp", firstSession.get("selectedCandidateTransport"));
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
        StreamKey key = new StreamKey(StreamProtocol.RTMP, "live", "cam_patch_validation");
        EmbeddedChannel publisher = new EmbeddedChannel();
        PublishedStream published = registry.tryPublish(key, "pub_validation", "v=0\r\n", publisher).get();
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

            FullHttpRequest badOffer = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/whep/rtmp/live/cam_patch_validation",
                    Unpooled.copiedBuffer("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n", CharsetUtil.UTF_8));
            badOffer.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, badOffer.content().readableBytes());
            channel.writeInbound(badOffer);
            FullHttpResponse badOfferResp = channel.readOutbound();
            try {
                assertNotNull(badOfferResp);
                assertEquals(HttpResponseStatus.BAD_REQUEST, badOfferResp.status());
            } finally {
                ReferenceCountUtil.safeRelease(badOfferResp);
            }

            FullHttpRequest offer = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/whep/rtmp/live/cam_patch_validation",
                    Unpooled.copiedBuffer(minimalWhepOffer(), CharsetUtil.UTF_8));
            offer.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, offer.content().readableBytes());
            channel.writeInbound(offer);
            FullHttpResponse offerResp = channel.readOutbound();
            String sessionId;
            try {
                assertNotNull(offerResp);
                assertEquals(HttpResponseStatus.CREATED, offerResp.status());
                String location = offerResp.headers().get(HttpHeaderNames.LOCATION);
                assertNotNull(location);
                sessionId = location.substring("/whep/session/".length());
            } finally {
                ReferenceCountUtil.safeRelease(offerResp);
            }

            FullHttpRequest badPatchType = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.PATCH,
                    "/whep/session/" + sessionId,
                    Unpooled.copiedBuffer(minimalTrickleSdpFrag(), CharsetUtil.UTF_8));
            badPatchType.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            badPatchType.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, badPatchType.content().readableBytes());
            channel.writeInbound(badPatchType);
            FullHttpResponse badPatchTypeResp = channel.readOutbound();
            try {
                assertNotNull(badPatchTypeResp);
                assertEquals(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, badPatchTypeResp.status());
            } finally {
                ReferenceCountUtil.safeRelease(badPatchTypeResp);
            }

            FullHttpRequest badPatchBody = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.PATCH,
                    "/whep/session/" + sessionId,
                    Unpooled.copiedBuffer("v=0\r\n", CharsetUtil.UTF_8));
            badPatchBody.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/trickle-ice-sdpfrag");
            badPatchBody.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, badPatchBody.content().readableBytes());
            channel.writeInbound(badPatchBody);
            FullHttpResponse badPatchBodyResp = channel.readOutbound();
            try {
                assertNotNull(badPatchBodyResp);
                assertEquals(HttpResponseStatus.BAD_REQUEST, badPatchBodyResp.status());
            } finally {
                ReferenceCountUtil.safeRelease(badPatchBodyResp);
            }

            FullHttpRequest badPatchEtag = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.PATCH,
                    "/whep/session/" + sessionId,
                    Unpooled.copiedBuffer(minimalTrickleSdpFrag(), CharsetUtil.UTF_8));
            badPatchEtag.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/trickle-ice-sdpfrag");
            badPatchEtag.headers().set(HttpHeaderNames.IF_MATCH, "\"wrong\"");
            badPatchEtag.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, badPatchEtag.content().readableBytes());
            channel.writeInbound(badPatchEtag);
            FullHttpResponse badPatchEtagResp = channel.readOutbound();
            try {
                assertNotNull(badPatchEtagResp);
                assertEquals(HttpResponseStatus.PRECONDITION_FAILED, badPatchEtagResp.status());
            } finally {
                ReferenceCountUtil.safeRelease(badPatchEtagResp);
            }
        } finally {
            channel.finishAndReleaseAll();
            registry.unpublish(key, published.publisherSession().id());
            publisher.finishAndReleaseAll();
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

    private static String minimalWhepOffer() {
        return "v=0\r\n"
                + "o=- 123456789 2 IN IP4 127.0.0.1\r\n"
                + "s=-\r\n"
                + "t=0 0\r\n"
                + "a=group:BUNDLE 0\r\n"
                + "a=msid-semantic: WMS\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=rtcp:9 IN IP4 0.0.0.0\r\n"
                + "a=ice-ufrag:test\r\n"
                + "a=ice-pwd:testtesttesttesttesttest\r\n"
                + "a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:10:20:30:40:50:60:70:80:90:A0:B0:C0:D0:E0:F0:01\r\n"
                + "a=setup:actpass\r\n"
                + "a=mid:0\r\n"
                + "a=sendrecv\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:96 H264/90000\r\n"
                + "a=fmtp:96 packetization-mode=1;profile-level-id=42e01f\r\n";
    }

    private static String minimalTrickleSdpFrag() {
        return "a=ice-ufrag:testfrag\r\n"
                + "a=ice-pwd:testfragtestfragtestfrag\r\n"
                + "a=mid:0\r\n"
                + "a=candidate:1 1 udp 2130706431 127.0.0.1 61234 typ host\r\n"
                + "a=end-of-candidates\r\n";
    }
}
