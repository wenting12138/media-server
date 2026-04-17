package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.rtp.RtpUdpMediaPlane;
import com.wenting.mediaserver.protocol.rtsp.sdp.SdpParser;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
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
    private final RtpUdpMediaPlane rtpUdp;
    private final RtspSessionContext session = new RtspSessionContext();
    private RtspFsmState state = RtspFsmState.IDLE;

    RtspStateMachine(StreamRegistry registry, RtpUdpMediaPlane rtpUdp) {
        this.registry = registry;
        this.rtpUdp = rtpUdp;
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
                if (session.subscriberVideoUdpRtpDest() != null) {
                    session.subscribedStream().removeUdpVideoSubscriber(session.subscriberVideoUdpRtpDest());
                }
                if (session.subscriberAudioUdpRtpDest() != null) {
                    session.subscribedStream().removeUdpAudioSubscriber(session.subscriberAudioUdpRtpDest());
                }
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
//        log.info("RTSP MESSAGE {}", req.toString());
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
        if (session.rtpTransportMode() != RtpTransportMode.TCP_INTERLEAVED) {
            return;
        }
        if (state != RtspFsmState.PUBLISHER_LIVE || session.publishedStream() == null) {
            return;
        }
        if (p.channel() == session.videoRtpInterleaved()) {
            session.publishedStream().onPublisherVideoRtp(p.payload());
        } else if (p.channel() == session.videoRtpInterleaved() + 2) {
            session.publishedStream().onPublisherAudioRtp(p.payload());
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
        boolean tcp = RtspTransport.isTcpTransport(transport);
        boolean udp = RtspTransport.isUdpTransport(transport);
        if (!tcp && !udp) {
            ctx.writeAndFlush(RtspResponses.error(cseq, UNSUPPORTED_MEDIA));
            return;
        }
        if (session.rtspSessionId() == null) {
            session.setRtspSessionId(Long.toHexString(System.nanoTime())
                    + UUID.randomUUID().toString().replace("-", ""));
        }
        if (tcp) {
            session.setRtpTransportMode(RtpTransportMode.TCP_INTERLEAVED);
            int[] ch = RtspTransport.parseInterleavedPairs(transport);
            log.info("RTSP SETUP {} transport={}", session.streamKey().path(), transport);
            if (state == RtspFsmState.PUBLISHER_NEGOTIATING && session.setupRound() == 0) {
                session.setVideoInterleaved(ch[0], ch[1]);
            }
            session.incrementSetupRound();
            ctx.writeAndFlush(RtspResponses.setupTcp(cseq, session.rtspSessionId(), ch[0], ch[1]));
            return;
        }
        session.setRtpTransportMode(RtpTransportMode.UDP);
        int[] clientPorts = RtspTransport.parseClientPorts(transport);
        if (clientPorts[0] <= 0 || clientPorts[1] <= 0) {
            ctx.writeAndFlush(RtspResponses.error(cseq, UNSUPPORTED_MEDIA));
            return;
        }
        log.info("RTSP SETUP {} transport={}", session.streamKey().path(), transport);
        if (state.isPublisherSide()) {
            int trackId = RtspPathUtil.streamTrackIdFromRtspUri(req.uri()).orElse(0);
            RtpUdpMediaPlane.PublisherRtpReceiver recv = null;
            if (trackId == 0) {
                recv = session.publisherVideoUdpReceiver();
                if (recv != null) {
                    recv.close();
                    session.setPublisherVideoUdpReceiver(null);
                }
                try {
                    recv = rtpUdp.openPublisherReceiver();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                    return;
                } catch (Exception e) {
                    log.warn("RTSP publisher UDP bind failed for video", e);
                    ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                    return;
                }
                session.setPublisherVideoUdpReceiver(recv);
                log.info("RTSP publisher UDP video recv server_port={}-{}", recv.serverRtpPort(), recv.serverRtcpPort());
            } else if (trackId == 1) {
                recv = session.publisherAudioUdpReceiver();
                if (recv != null) {
                    recv.close();
                    session.setPublisherAudioUdpReceiver(null);
                }
                try {
                    recv = rtpUdp.openPublisherReceiver();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                    return;
                } catch (Exception e) {
                    log.warn("RTSP publisher UDP bind failed for audio", e);
                    ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                    return;
                }
                session.setPublisherAudioUdpReceiver(recv);
                log.info("RTSP publisher UDP audio recv server_port={}-{}", recv.serverRtpPort(), recv.serverRtcpPort());
            } else {
                try {
                    recv = rtpUdp.openPublisherReceiver();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                    return;
                } catch (Exception e) {
                    log.warn("RTSP publisher UDP bind failed for unknown track streamid={}", trackId, e);
                    ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                    return;
                }
                session.addPublisherAuxUdpReceiver(recv);
                log.info("RTSP publisher UDP unknown streamid={} -> discard server_port={}-{}", trackId, recv.serverRtpPort(), recv.serverRtcpPort());
            }
            session.incrementSetupRound();
            ctx.writeAndFlush(RtspResponses.setupUdp(
                    cseq,
                    session.rtspSessionId(),
                    recv.serverRtpPort(),
                    recv.serverRtcpPort(),
                    clientPorts[0],
                    clientPorts[1]));
            return;
        }
        if (state.isSubscriberSide()) {
            try {
                rtpUdp.ensureEgressStarted();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                return;
            }
            int sRtp = rtpUdp.egressServerRtpPort();
            int sRtcp = rtpUdp.egressServerRtcpPort();
            if (sRtp <= 0 || sRtcp <= 0) {
                ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                return;
            }
            InetSocketAddress rtspRemote = (InetSocketAddress) ctx.channel().remoteAddress();
            // FFmpeg/Lavf SETUP twice: streamid=0 (video) then streamid=1 (audio). Only video RTP is relayed;
            // must not overwrite the video UDP destination with the second track's client_port.
            int trackId = RtspPathUtil.streamTrackIdFromRtspUri(req.uri()).orElse(0);
            if (trackId == 0) {
                session.setSubscriberVideoUdpRtpDest(new InetSocketAddress(rtspRemote.getAddress(), clientPorts[0]));
                log.info(
                        "RTSP subscriber UDP video dest {} (streamid=0)",
                        session.subscriberVideoUdpRtpDest());
            } else if (trackId == 1) {
                session.setSubscriberAudioUdpRtpDest(new InetSocketAddress(rtspRemote.getAddress(), clientPorts[0]));
                log.info(
                        "RTSP subscriber UDP audio dest {} (streamid=1)",
                        session.subscriberAudioUdpRtpDest());
            } else {
                log.info("RTSP subscriber UDP SETUP ignored for unknown streamid={} (no relay)", trackId);
            }
            session.incrementSetupRound();
            ctx.writeAndFlush(RtspResponses.setupUdp(
                    cseq,
                    session.rtspSessionId(),
                    sRtp,
                    sRtcp,
                    clientPorts[0],
                    clientPorts[1]));
            return;
        }
        ctx.writeAndFlush(RtspResponses.error(cseq, WRONG_STATE));
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
        PublishedStream ps = publishResult.get();
        session.setPublishedStream(ps);
        session.setPublisherMediaSessionId(ps.publisherSession().id());
        ps.attachRtpUdpMediaPlane(rtpUdp);
        if (session.publisherVideoUdpReceiver() != null) {
            session.publisherVideoUdpReceiver().bindSink(ps, 0);
        }
        if (session.publisherAudioUdpReceiver() != null) {
            session.publisherAudioUdpReceiver().bindSink(ps, 1);
        }
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
            log.warn(
                    "RTSP PLAY session mismatch cseq={} header={} expected={}",
                    cseq,
                    req.header("Session"),
                    session.rtspSessionId());
            ctx.writeAndFlush(RtspResponses.error(cseq, SESSION_NOT_FOUND));
            return;
        }
        Optional<PublishedStream> ps = registry.published(session.streamKey());
        if (!ps.isPresent()) {
            ctx.writeAndFlush(RtspResponses.error(cseq, HttpResponseStatus.NOT_FOUND));
            return;
        }
        PublishedStream stream = ps.get();
        session.setSubscribedStream(stream);
        stream.attachRtpUdpMediaPlane(rtpUdp);
        if (session.rtpTransportMode() == RtpTransportMode.TCP_INTERLEAVED) {
            stream.addSubscriber(ctx.channel());
        }
        if (session.rtpTransportMode() == RtpTransportMode.UDP) {
            if (session.subscriberVideoUdpRtpDest() != null) {
                stream.addUdpVideoSubscriber(session.subscriberVideoUdpRtpDest());
            } else {
                log.warn(
                        "RTSP PLAY UDP but no video track destination (missing SETUP for streamid=0 / base URI?) {}",
                        session.streamKey().path());
            }
            if (session.subscriberAudioUdpRtpDest() != null) {
                stream.addUdpAudioSubscriber(session.subscriberAudioUdpRtpDest());
            }
        }
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
            PublishedStream ss = session.subscribedStream();
            if (ss != null) {
                if (session.subscriberVideoUdpRtpDest() != null) {
                    ss.removeUdpVideoSubscriber(session.subscriberVideoUdpRtpDest());
                }
                if (session.subscriberAudioUdpRtpDest() != null) {
                    ss.removeUdpAudioSubscriber(session.subscriberAudioUdpRtpDest());
                }
                ss.removeSubscriber(ctx.channel());
            }
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
