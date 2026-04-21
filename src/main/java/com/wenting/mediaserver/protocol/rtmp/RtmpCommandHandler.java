package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.PublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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
    private String app = "live";
    private StreamKey publishingKey;
    private String publisherSessionId;
    private PublishedStream publishingStream;
    private PublishedStream playingStream;
    private int playingMessageStreamId = 1;

    RtmpCommandHandler(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RtmpMessage msg) {
        try {
            if (msg.typeId() == RtmpConstants.TYPE_AUDIO || msg.typeId() == RtmpConstants.TYPE_VIDEO) {
                onMedia(msg);
                return;
            }
            if (msg.typeId() != RtmpConstants.TYPE_COMMAND_AMF0) {
                return;
            }
            ByteBuf p = msg.payload();
            String command = RtmpAmf0.readString(p);
            log.info("RTMP command {}", command);
            Double txn = RtmpAmf0.readNumber(p);
            if ("connect".equals(command)) {
                onConnect(ctx, txn == null ? 0.0 : txn.doubleValue(), p);
            } else if ("createStream".equals(command)) {
                onCreateStream(ctx, txn == null ? 0.0 : txn.doubleValue());
            } else if ("publish".equals(command)) {
                onPublish(ctx, msg.messageStreamId(), p);
            } else if ("play".equals(command)) {
                onPlay(ctx, msg.messageStreamId(), p);
            } else if ("deleteStream".equals(command) || "closeStream".equals(command)) {
                onClose(ctx.channel());
            }
        } finally {
            ReferenceCountUtil.release(msg.payload());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        onClose(ctx.channel());
        if (playingStream != null) {
            playingStream.removeRtmpSubscriber(ctx);
        }
    }

    private void onConnect(ChannelHandlerContext ctx, double txn, ByteBuf payload) {
        Map<String, Object> obj = RtmpAmf0.readObject(payload);
        Object appV = obj.get("app");
        if (appV instanceof String && !((String) appV).isEmpty()) {
            app = (String) appV;
        }
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

    private void onCreateStream(ChannelHandlerContext ctx, double txn) {
        ByteBuf resp = Unpooled.buffer();
        RtmpAmf0.writeString(resp, "_result");
        RtmpAmf0.writeNumber(resp, txn);
        RtmpAmf0.writeNull(resp);
        RtmpAmf0.writeNumber(resp, 1.0);
        RtmpWriter.writeCommand(ctx, 0, resp);
    }

    private void onPublish(ChannelHandlerContext ctx, int messageStreamId, ByteBuf payload) {
        RtmpAmf0.readValue(payload); // null
        String streamName = RtmpAmf0.readString(payload);
        if (streamName == null || streamName.isEmpty()) {
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Publish.BadName", "empty stream name");
            return;
        }
        StreamKey key = toStreamKey(streamName);
        String sid = UUID.randomUUID().toString().replace("-", "");
        Optional<PublishedStream> published = registry.tryPublish(key, sid, "", ctx.channel());
        if (!published.isPresent()) {
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Publish.BadName", "stream already exists");
            return;
        }
        publishingKey = key;
        publisherSessionId = sid;
        publishingStream = published.get();
        log.info("RTMP publish start {}", key.path());
        sendOnStatus(ctx, messageStreamId, "status", "NetStream.Publish.Start", "publish started");
    }

    private void onPlay(ChannelHandlerContext ctx, int messageStreamId, ByteBuf payload) {
        RtmpAmf0.readValue(payload); // null
        String streamName = RtmpAmf0.readString(payload);
        if (streamName == null || streamName.isEmpty()) {
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Play.BadName", "empty stream name");
            return;
        }
        StreamKey key = toStreamKey(streamName);
        Optional<PublishedStream> published = registry.published(key);
        if (!published.isPresent()) {
            sendOnStatus(ctx, messageStreamId, "error", "NetStream.Play.StreamNotFound", "stream not found");
            return;
        }
        playingStream = published.get();
        playingMessageStreamId = messageStreamId <= 0 ? 1 : messageStreamId;
        playingStream.addRtmpSubscriber(ctx, playingMessageStreamId);
        log.info("RTMP play start {}", key.path());
        sendOnStatus(ctx, messageStreamId, "status", "NetStream.Play.Start", "play started");
    }

    private void onClose(Channel ch) {
        if (publishingKey != null && publisherSessionId != null) {
            registry.unpublish(publishingKey, publisherSessionId);
            log.info("RTMP publish stop {}", publishingKey.path());
        }
        if (playingStream != null && ch != null) {
            ChannelHandlerContext subCtx = ch.pipeline().context(this);
            if (subCtx != null) {
                playingStream.removeRtmpSubscriber(subCtx);
            }
        }
        publishingKey = null;
        publisherSessionId = null;
        publishingStream = null;
        playingStream = null;
    }

    private void onMedia(RtmpMessage msg) {
        if (publishingStream == null) {
            return;
        }
        if (msg.typeId() == RtmpConstants.TYPE_VIDEO) {
            publishingStream.onPublisherRtmpVideo(msg.payload(), msg.timestamp(), msg.messageStreamId() <= 0 ? 1 : msg.messageStreamId());
            return;
        }
        publishingStream.onPublisherRtmpAudio(msg.payload(), msg.timestamp(), msg.messageStreamId() <= 0 ? 1 : msg.messageStreamId());
    }

    private StreamKey toStreamKey(String streamName) {
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
