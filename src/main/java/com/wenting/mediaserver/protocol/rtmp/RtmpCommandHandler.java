package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class RtmpCommandHandler extends SimpleChannelInboundHandler<RtmpMessage> {
    private static final Logger log = LoggerFactory.getLogger(RtmpCommandHandler.class);
    private final StreamRegistry registry;

    RtmpCommandHandler(StreamRegistry registry) {
        this.registry = registry;
    }

    private RtmpSession session(ChannelHandlerContext ctx) {
        return ctx.channel().attr(RtmpSession.SESSION_KEY).get();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RtmpMessage msg) {
        try {
            RtmpSession session = session(ctx);
            if (session == null || session.isClosed()) {
                return;
            }

            if (msg.typeId() == RtmpConstants.TYPE_AUDIO || msg.typeId() == RtmpConstants.TYPE_VIDEO) {
                onMedia(session, msg);
                return;
            }
            if (msg.typeId() == RtmpConstants.TYPE_DATA_AMF0) {
                onData(session, msg);
                return;
            }
            if (msg.typeId() != RtmpConstants.TYPE_COMMAND_AMF0) {
                return;
            }

            ByteBuf p = msg.payload();
            String command = RtmpAmf0.readString(p);
            log.info("RTMP command {} state={}", command, session.state());
            Double txn = RtmpAmf0.readNumber(p);
            double txnVal = txn == null ? 0.0 : txn.doubleValue();

            switch (command) {
                case "connect":
                    onConnect(ctx, session, txnVal, p);
                    break;
                case "createStream":
                    onCreateStream(ctx, session, txnVal);
                    break;
                case "publish":
                    onPublish(ctx, session, msg.messageStreamId(), p);
                    break;
                case "play":
                    onPlay(ctx, session, msg.messageStreamId(), p);
                    break;
                case "FCPublish":
                    onFCPublish(ctx, session, txnVal, p);
                    break;
                case "getStreamLength":
                    onGetStreamLength(ctx, txnVal);
                    break;
                case "deleteStream":
                case "closeStream":
                    onCloseStream(ctx, session);
                    break;
                default:
                    break;
            }
        } finally {
            ReferenceCountUtil.release(msg.payload());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        RtmpSession session = session(ctx);
        if (session != null) {
            cleanupSession(ctx, session);
            session.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof java.io.IOException) {
            log.debug("RTMP connection closed: {}", cause.getMessage());
        } else {
            log.warn("RTMP unexpected exception", cause);
        }
        ctx.close();
    }

    private void onConnect(ChannelHandlerContext ctx, RtmpSession session, double txn, ByteBuf payload) {
        Map<String, Object> obj = RtmpAmf0.readObject(payload);
        Object appV = obj.get("app");
        String app = "live";
        if (appV instanceof String && !((String) appV).isEmpty()) {
            app = (String) appV;
        }
        if (!session.connect(app)) {
            log.warn("connect rejected: invalid state {}", session.state());
            return;
        }

        log.info("RTMP connect obj={}", obj);

        RtmpWriter.writeProtocolControl(ctx, RtmpConstants.TYPE_WINDOW_ACK_SIZE, 5000000);
        RtmpWriter.writeSetPeerBandwidth(ctx, 5000000, RtmpConstants.CSID_PROTOCOL);
        RtmpWriter.writeProtocolControl(ctx, RtmpConstants.TYPE_SET_CHUNK_SIZE, 4096);

        ByteBuf resp = Unpooled.buffer();
        RtmpAmf0.writeString(resp, "_result");
        RtmpAmf0.writeNumber(resp, txn);
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("fmsVer", "FMS/3,5,7,7009");
        props.put("capabilities", Double.valueOf(31));
        RtmpAmf0.writeObject(resp, props);
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("level", "status");
        info.put("code", "NetConnection.Connect.Success");
        info.put("description", "Connection succeeded.");
        info.put("objectEncoding", Double.valueOf(0));
        RtmpAmf0.writeObject(resp, info);
        RtmpWriter.writeCommand(ctx, 0, resp);
    }

    private void onCreateStream(ChannelHandlerContext ctx, RtmpSession session, double txn) {
        if (!session.createStream()) {
            log.warn("createStream rejected: invalid state {}", session.state());
            return;
        }
        ByteBuf resp = Unpooled.buffer();
        RtmpAmf0.writeString(resp, "_result");
        RtmpAmf0.writeNumber(resp, txn);
        RtmpAmf0.writeNull(resp);
        RtmpAmf0.writeNumber(resp, 1.0);
        RtmpWriter.writeCommand(ctx, 0, resp);
    }

    private void onPublish(ChannelHandlerContext ctx, RtmpSession session, int messageStreamId, ByteBuf payload) {
        if (session.state() != RtmpSession.State.STREAM_CREATED) {
            log.warn("publish rejected: invalid state {}", session.state());
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Publish.BadName", "invalid state");
            return;
        }
        RtmpAmf0.readValue(payload); // null
        String streamName = RtmpAmf0.readString(payload);
        if (streamName == null || streamName.isEmpty()) {
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Publish.BadName", "empty stream name");
            return;
        }
        StreamKey key = toStreamKey(session.app(), streamName);
        String sid = UUID.randomUUID().toString().replace("-", "");
        Optional<PublishedStream> published = registry.tryPublish(key, sid, "", ctx.channel());
        if (!published.isPresent()) {
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Publish.BadName", "stream already exists");
            return;
        }
        if (!session.publish(key, sid, published.get())) {
            log.warn("publish rejected: state changed concurrently {}", session.state());
            registry.unpublish(key, sid);
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Publish.BadName", "invalid state");
            return;
        }
        log.info("RTMP publish start {}", key.path());
        sendOnStatus(ctx, messageStreamId, "status", "NetStream.Publish.Start", "publish started");
    }

    private void onPlay(ChannelHandlerContext ctx, RtmpSession session, int messageStreamId, ByteBuf payload) {
        if (session.state() != RtmpSession.State.STREAM_CREATED) {
            log.warn("play rejected: invalid state {}", session.state());
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Play.BadName", "invalid state");
            return;
        }
        RtmpAmf0.readValue(payload); // null
        String streamName = RtmpAmf0.readString(payload);
        if (streamName == null || streamName.isEmpty()) {
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Play.BadName", "empty stream name");
            return;
        }
        StreamKey key = toStreamKey(session.app(), streamName);
        Optional<PublishedStream> published = registry.publishedForPlayback(key);
        if (!published.isPresent()) {
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Play.StreamNotFound", "stream not found");
            return;
        }
        PublishedStream stream = published.get();
        StreamKey resolvedKey = stream.key();
        int msid = messageStreamId <= 0 ? 1 : messageStreamId;

        RtmpWriter.writeStreamBegin(ctx, msid);
        sendOnStatus(ctx, msid, "status", "NetStream.Play.Reset", "Playing and resetting stream.");

        if (!session.play(stream, msid)) {
            log.warn("play rejected: state changed concurrently {}", session.state());
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Play.BadName", "invalid state");
            return;
        }
        stream.addRtmpSubscriber(ctx, msid);
        sendOnStatus(ctx, msid, "status", "NetStream.Play.Start", "play started");
        RtmpWriter.writeSampleAccess(ctx, msid);
        log.info("RTMP play start request={} resolved={}", key.path(), resolvedKey.path());
    }

    private void onFCPublish(ChannelHandlerContext ctx, RtmpSession session, double txn, ByteBuf payload) {
        if (session.state() != RtmpSession.State.STREAM_CREATED) {
            log.warn("FCPublish rejected: invalid state {}", session.state());
            return;
        }
        RtmpAmf0.readValue(payload); // null
        String streamName = RtmpAmf0.readString(payload);
        log.info("RTMP FCPublish stream={}", streamName);

        ByteBuf resp = Unpooled.buffer();
        RtmpAmf0.writeString(resp, "_result");
        RtmpAmf0.writeNumber(resp, txn);
        RtmpAmf0.writeNull(resp);
        RtmpWriter.writeCommand(ctx, 0, resp);
    }

    private void onGetStreamLength(ChannelHandlerContext ctx, double txn) {
        ByteBuf resp = Unpooled.buffer();
        RtmpAmf0.writeString(resp, "_result");
        RtmpAmf0.writeNumber(resp, txn);
        RtmpAmf0.writeNull(resp);
        RtmpAmf0.writeNumber(resp, 0.0);
        RtmpWriter.writeCommand(ctx, 0, resp);
    }

    private void onCloseStream(ChannelHandlerContext ctx, RtmpSession session) {
        if (session.state() == RtmpSession.State.PUBLISHING) {
            StreamKey key = session.publishingKey();
            String sid = session.publisherSessionId();
            if (key != null && sid != null) {
                registry.unpublish(key, sid);
                log.info("RTMP publish stop {}", key.path());
            }
        }
        PublishedStream playing = session.playingStream();
        if (playing != null) {
            playing.removeRtmpSubscriber(ctx);
        }
        session.closeStream();
    }

    private void cleanupSession(ChannelHandlerContext ctx, RtmpSession session) {
        if (session.state() == RtmpSession.State.PUBLISHING) {
            StreamKey key = session.publishingKey();
            String sid = session.publisherSessionId();
            if (key != null && sid != null) {
                registry.unpublish(key, sid);
                log.info("RTMP publish stop {}", key.path());
            }
        }
        log.info("RTMP play stop {}", session.playingStream().key().path());
        PublishedStream playing = session.playingStream();
        if (playing != null) {
            playing.removeRtmpSubscriber(ctx);
        }
    }

    private void onMedia(RtmpSession session, RtmpMessage msg) {
        if (session.state() != RtmpSession.State.PUBLISHING) {
            return;
        }
        PublishedStream stream = session.publishingStream();
        if (stream == null) {
            return;
        }
        int msid = msg.messageStreamId() <= 0 ? 1 : msg.messageStreamId();
        if (msg.typeId() == RtmpConstants.TYPE_VIDEO) {
            stream.onPublisherRtmpVideo(msg.payload(), msg.timestamp(), msid);
        } else {
            stream.onPublisherRtmpAudio(msg.payload(), msg.timestamp(), msid);
        }
    }

    private void onData(RtmpSession session, RtmpMessage msg) {
        if (session.state() != RtmpSession.State.PUBLISHING) {
            return;
        }
        PublishedStream stream = session.publishingStream();
        if (stream == null) {
            return;
        }
        int msid = msg.messageStreamId() <= 0 ? 1 : msg.messageStreamId();
        stream.onPublisherRtmpData(msg.payload(), msg.timestamp(), msid);
    }

    private StreamKey toStreamKey(String app, String streamName) {
        String stream = streamName;
        int slash = streamName.lastIndexOf('/');
        if (slash >= 0 && slash < streamName.length() - 1) {
            stream = streamName.substring(slash + 1);
        }
        return new StreamKey(app, stream);
    }

    private void sendOnStatus(ChannelHandlerContext ctx, int messageStreamId, String level, String code, String desc) {
        ByteBuf status = Unpooled.buffer();
        RtmpAmf0.writeString(status, "onStatus");
        RtmpAmf0.writeNumber(status, 0.0);
        RtmpAmf0.writeNull(status);
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("level", level);
        info.put("code", code);
        info.put("description", desc);
        RtmpAmf0.writeObject(status, info);
        RtmpWriter.writeCommand(ctx, messageStreamId <= 0 ? 1 : messageStreamId, status);
    }
}
