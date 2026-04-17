package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.rtsp.sdp.SdpParser;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit RTSP state machine for one connection: publish vs play paths and RTP ingress.
 */
final class RtspStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RtspStateMachine.class);

    private static final HttpResponseStatus STREAM_IN_USE =
            new HttpResponseStatus(453, "Stream Already Published");
    private static final HttpResponseStatus WRONG_STATE =
            new HttpResponseStatus(455, "Method Not Valid In This State");
    private static final HttpResponseStatus UNSUPPORTED_MEDIA =
            new HttpResponseStatus(461, "Unsupported Media Type");
    private static final HttpResponseStatus SESSION_NOT_FOUND =
            new HttpResponseStatus(454, "Session Not Found");

    private final StreamRegistry registry;
    private final RtspSessionContext session = new RtspSessionContext();
    private RtspFsmState state = RtspFsmState.IDLE;

    RtspStateMachine(StreamRegistry registry) {
        this.registry = registry;
    }

    RtspFsmState rtspState() {
        return state;
    }

    RtspConnectionRole connectionRole() {
        return RtspConnectionRole.fromState(state);
    }

    void onConnectionInactive(Channel ch) {
        try {
            if (state == RtspFsmState.PUBLISHER_LIVE
                    && session.publisherMediaSessionId() != null
                    && session.streamKey() != null) {
                log.info(
                        "RTSP publisher TCP closed -> unpublish {} session={} remote={}",
                        session.streamKey().path(),
                        session.publisherMediaSessionId(),
                        ch.remoteAddress());
                registry.unpublish(session.streamKey(), session.publisherMediaSessionId());
            } else if (state == RtspFsmState.SUBSCRIBER_LIVE
                    && session.subscribedStream() != null) {
                log.info(
                        "RTSP subscriber TCP closed -> stop pull {} remote={}",
                        session.streamKey() != null ? session.streamKey().path() : "(unknown)",
                        ch.remoteAddress());
                session.subscribedStream().removeSubscriber(ch);
            } else if (state.isSubscriberSide()) {
                log.info(
                        "RTSP subscriber TCP closed (no PLAY yet) path={} remote={}",
                        session.streamKey() != null ? session.streamKey().path() : "(unknown)",
                        ch.remoteAddress());
            }
        } finally {
            session.clear();
            state = RtspFsmState.IDLE;
        }
    }

    void handleRequest(ChannelHandlerContext ctx, RtspRequestMessage req) {
        String method = req.method().toUpperCase(Locale.ROOT);
        int cseq = req.cSeq();
        if (cseq < 0) {
            ctx.writeAndFlush(RtspResponses.error(-1, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        log.info("RTSP MESSAGE {}", req.toString());
        if ("OPTIONS".equals(method)) {
            ctx.writeAndFlush(RtspResponses.options(cseq));
        } else if ("ANNOUNCE".equals(method)) {
            onAnnounce(ctx, req, cseq);
        } else if ("DESCRIBE".equals(method)) {
            onDescribe(ctx, req, cseq);
        } else if ("SETUP".equals(method)) {
            onSetup(ctx, req, cseq);
        } else if ("RECORD".equals(method)) {
            onRecord(ctx, req, cseq);
        } else if ("PLAY".equals(method)) {
            onPlay(ctx, req, cseq);
        } else if ("TEARDOWN".equals(method)) {
            onTeardown(ctx, req, cseq);
        } else if ("GET_PARAMETER".equals(method) || "SET_PARAMETER".equals(method)) {
            log.info("RTSP {} {}", method, req.uri());
            ctx.writeAndFlush(RtspResponses.okEmpty(cseq));
        } else if ("PAUSE".equals(method)) {
            log.info("RTSP {} {}", method, req.uri());
            ctx.writeAndFlush(RtspResponses.okEmpty(cseq));
        } else {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.METHOD_NOT_ALLOWED));
        }
    }

    void handleInterleaved(ChannelHandlerContext ctx, InterleavedRtpPacket p) {
        if (state != RtspFsmState.PUBLISHER_LIVE || session.publishedStream() == null) {
            return;
        }
        if (p.channel() == session.videoRtpInterleaved()) {
            session.publishedStream().onPublisherVideoRtp(p.payload());
        }
    }

    private boolean sdpNegotiatedForCurrentRole() {
        if (state.isPublisherSide()) {
            return session.sdpText() != null;
        }
        if (state.isSubscriberSide()) {
            return session.streamKey() != null;
        }
        return false;
    }

    private void onAnnounce(ChannelHandlerContext ctx, RtspRequestMessage req, int cseq) {
        if (state.isSubscriberSide()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        if (state == RtspFsmState.PUBLISHER_LIVE) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        StreamKey key;
        try {
            key = RtspPathUtil.streamKeyFromRtspUri(req.uri());
        } catch (IllegalArgumentException e) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        if (!SdpParser.containsH264Video(req.body())) {
            ctx.writeAndFlush(RtspResponses.error(cseq, UNSUPPORTED_MEDIA));
            return;
        }
        log.info("RTSP ANNOUNCE {}", key.path());
        session.setStreamKey(key);
        session.setSdpText(req.body().toString(CharsetUtil.UTF_8));
        state = RtspFsmState.PUBLISHER_NEGOTIATING;
        ctx.writeAndFlush(RtspResponses.announceOk(cseq));
    }

    private void onDescribe(ChannelHandlerContext ctx, RtspRequestMessage req, int cseq) {
        if (state.isPublisherSide()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        StreamKey key;
        try {
            key = RtspPathUtil.streamKeyFromRtspUri(req.uri());
        } catch (IllegalArgumentException e) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        Optional<PublishedStream> ps = registry.published(key);
        if (!ps.isPresent()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.NOT_FOUND));
            return;
        }
        String sdp = ps.get().sdp();
        if (sdp == null || sdp.isEmpty()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.NOT_FOUND));
            return;
        }
        log.info("RTSP DESCRIBE {}", key.path());
        session.setStreamKey(key);
        if (state == RtspFsmState.IDLE) {
            state = RtspFsmState.SUBSCRIBER_NEGOTIATING;
        }
        ctx.writeAndFlush(RtspResponses.describeOk(cseq, sdp));
    }

    private void onSetup(ChannelHandlerContext ctx, RtspRequestMessage req, int cseq) {
        if (!sdpNegotiatedForCurrentRole()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, WRONG_STATE));
            return;
        }
        String transport = req.header("Transport");
        if (!RtspTransport.isTcpTransport(transport)) {
            ctx.writeAndFlush(RtspResponses.error(cseq, UNSUPPORTED_MEDIA));
            return;
        }
        log.info("RTSP SETUP {} transport={}", session.streamKey().path(), transport);
        int[] ch = RtspTransport.parseInterleavedPairs(transport);
        if (session.rtspSessionId() == null) {
            session.setRtspSessionId(Long.toHexString(System.nanoTime())
                    + UUID.randomUUID().toString().replace("-", ""));
        }
        if (state == RtspFsmState.PUBLISHER_NEGOTIATING && session.setupRound() == 0) {
            session.setVideoInterleaved(ch[0], ch[1]);
        }
        session.incrementSetupRound();
        ctx.writeAndFlush(RtspResponses.setupTcp(cseq, session.rtspSessionId(), ch[0], ch[1]));
    }

    private void onRecord(ChannelHandlerContext ctx, RtspRequestMessage req, int cseq) {
        if (state != RtspFsmState.PUBLISHER_NEGOTIATING) {
            ctx.writeAndFlush(RtspResponses.error(cseq, WRONG_STATE));
            return;
        }
        if (!sessionMatches(req)) {
            ctx.writeAndFlush(RtspResponses.error(cseq, SESSION_NOT_FOUND));
            return;
        }
        String publisherSessionId = req.header("Session");
        Optional<PublishedStream> publishResult =
                registry.tryPublish(session.streamKey(), publisherSessionId, session.sdpText(), ctx.channel());
        if (!publishResult.isPresent()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, STREAM_IN_USE));
            return;
        }
        session.setPublishedStream(publishResult.get());
        session.setPublisherMediaSessionId(session.publishedStream().publisherSession().id());
        state = RtspFsmState.PUBLISHER_LIVE;
        log.info("RTSP RECORD {} session={}", session.streamKey().path(), session.publisherMediaSessionId());
        ctx.writeAndFlush(RtspResponses.okEmpty(cseq));
    }

    private void onPlay(ChannelHandlerContext ctx, RtspRequestMessage req, int cseq) {
        if (!state.isSubscriberSide()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, WRONG_STATE));
            return;
        }
        if (!sessionMatches(req)) {
            ctx.writeAndFlush(RtspResponses.error(cseq, SESSION_NOT_FOUND));
            return;
        }
        Optional<PublishedStream> ps = registry.published(session.streamKey());
        if (!ps.isPresent()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.NOT_FOUND));
            return;
        }
        session.setSubscribedStream(ps.get());
        session.subscribedStream().addSubscriber(ctx.channel());
        state = RtspFsmState.SUBSCRIBER_LIVE;
        log.info("RTSP PLAY {}", session.streamKey().path());
        ctx.writeAndFlush(RtspResponses.okEmpty(cseq));
    }

    private void onTeardown(ChannelHandlerContext ctx, RtspRequestMessage req, int cseq) {
        String path = session.streamKey() != null ? session.streamKey().path() : "(no stream)";
        boolean stopPull = state == RtspFsmState.SUBSCRIBER_LIVE && session.subscribedStream() != null;
        boolean stopPush = state == RtspFsmState.PUBLISHER_LIVE
                && session.publisherMediaSessionId() != null
                && session.streamKey() != null;
        if (stopPull) {
            log.info("RTSP TEARDOWN subscriber -> stop pull {} remote={}", path, ctx.channel().remoteAddress());
        } else if (stopPush) {
            log.info("RTSP TEARDOWN publisher -> stop push {}", path);
        } else {
            log.info("RTSP TEARDOWN (no active media) path={} state={} remote={}", path, state, ctx.channel().remoteAddress());
        }
        if (stopPush) {
            registry.unpublish(session.streamKey(), session.publisherMediaSessionId());
        }
        if (stopPull) {
            session.subscribedStream().removeSubscriber(ctx.channel());
        }
        session.clear();
        state = RtspFsmState.IDLE;
        ctx.writeAndFlush(RtspResponses.okEmpty(cseq));
    }

    private boolean sessionMatches(RtspRequestMessage req) {
        String sh = req.header("Session");
        if (sh == null || session.rtspSessionId() == null) {
            return false;
        }
        String sid = sh.split(";")[0].trim();
        return session.rtspSessionId().equals(sid);
    }
}
