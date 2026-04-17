package com.wenting.mediaserver.protocol.rtsp;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspVersions;
import io.netty.util.CharsetUtil;

/**
 * Builds common RTSP responses with mandatory {@code CSeq} header.
 */
public final class RtspResponses {

    private RtspResponses() {
    }

    public static FullHttpResponse withCseq(FullHttpResponse resp, int cseq) {
        if (cseq >= 0) {
            resp.headers().set(RtspHeaderNames.CSEQ, String.valueOf(cseq));
        }
        return resp;
    }

    public static FullHttpResponse options(int cseq) {
        DefaultFullHttpResponse r = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0,
                HttpResponseStatus.OK,
                Unpooled.EMPTY_BUFFER);
        withCseq(r, cseq);
        r.headers().set(RtspHeaderNames.PUBLIC,
                "OPTIONS, DESCRIBE, ANNOUNCE, SETUP, PLAY, RECORD, TEARDOWN, GET_PARAMETER, SET_PARAMETER");
        return r;
    }

    public static FullHttpResponse okEmpty(int cseq) {
        DefaultFullHttpResponse r = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0,
                HttpResponseStatus.OK,
                Unpooled.EMPTY_BUFFER);
        return withCseq(r, cseq);
    }

    public static FullHttpResponse describeOk(int cseq, String sdp) {
        byte[] bytes = sdp.getBytes(CharsetUtil.UTF_8);
        DefaultFullHttpResponse r = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0,
                HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(bytes));
        withCseq(r, cseq);
        r.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "application/sdp");
        r.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return r;
    }

    public static FullHttpResponse announceOk(int cseq) {
        return okEmpty(cseq);
    }

    public static FullHttpResponse setupTcp(int cseq, String sessionId, int rtpCh, int rtcpCh) {
        DefaultFullHttpResponse r = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0,
                HttpResponseStatus.OK,
                Unpooled.EMPTY_BUFFER);
        withCseq(r, cseq);
        r.headers().set(RtspHeaderNames.SESSION, sessionId + ";timeout=60");
        r.headers().set(RtspHeaderNames.TRANSPORT,
                "RTP/AVP/TCP;unicast;interleaved=" + rtpCh + "-" + rtcpCh);
        return r;
    }

    public static FullHttpResponse error(int cseq, HttpResponseStatus status) {
        DefaultFullHttpResponse r = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0,
                status,
                Unpooled.EMPTY_BUFFER);
        return withCseq(r, cseq);
    }
}
