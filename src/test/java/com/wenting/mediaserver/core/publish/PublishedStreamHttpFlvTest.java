package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.model.MediaSession;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.StreamProtocol;
import com.wenting.mediaserver.core.transcode.StreamFrameProcessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedStreamHttpFlvTest {

    @Test
    void relaysRtspRtpVideoAsHttpFlvTags() throws Exception {
        StreamKey key = new StreamKey(StreamProtocol.RTSP, "live", "rtsp_flv");
        MediaSession publisher = new MediaSession(key, "sid", MediaSession.Role.PUBLISHER);
        PublishedStream stream = new PublishedStream(key, publisher, StreamFrameProcessor.NOOP);
        String sdp = "v=0\r\n"
                + "m=video 0 RTP/AVP 96\r\n"
                + "a=rtpmap:96 H264/90000\r\n"
                + "a=fmtp:96 packetization-mode=1;sprop-parameter-sets="
                + Base64.getEncoder().encodeToString(sps())
                + ","
                + Base64.getEncoder().encodeToString(pps())
                + "\r\n";
        stream.setSdp(sdp);

        CaptureCtxHandler capture = new CaptureCtxHandler();
        EmbeddedChannel channel = new EmbeddedChannel(capture);
        try {
            stream.addHttpFlvSubscriber(capture.ctx);
            ByteBuf rtp = buildRtpPacket(1, 0, true, idr());
            try {
                stream.onPublisherVideoRtp(rtp);
            } finally {
                ReferenceCountUtil.safeRelease(rtp);
            }
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();

            byte[] out = readOutboundBytes(channel);
            assertTrue(indexOf(out, new byte[]{'F', 'L', 'V'}) >= 0);
            assertTrue(indexOf(out, new byte[]{0x17, 0x00, 0x00, 0x00, 0x00}) >= 0);
            assertTrue(indexOf(out, new byte[]{0x17, 0x01, 0x00, 0x00, 0x00}) >= 0);
        } finally {
            stream.shutdown();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void secondHttpFlvSubscriberShouldReceiveCachedSeqAndKeyframe() throws Exception {
        StreamKey key = new StreamKey(StreamProtocol.RTSP, "live", "rtsp_flv_second");
        MediaSession publisher = new MediaSession(key, "sid2", MediaSession.Role.PUBLISHER);
        PublishedStream stream = new PublishedStream(key, publisher, StreamFrameProcessor.NOOP);
        String sdp = "v=0\r\n"
                + "m=video 0 RTP/AVP 96\r\n"
                + "a=rtpmap:96 H264/90000\r\n"
                + "a=fmtp:96 packetization-mode=1;sprop-parameter-sets="
                + Base64.getEncoder().encodeToString(sps())
                + ","
                + Base64.getEncoder().encodeToString(pps())
                + "\r\n";
        stream.setSdp(sdp);

        CaptureCtxHandler first = new CaptureCtxHandler();
        EmbeddedChannel firstChannel = new EmbeddedChannel(first);
        CaptureCtxHandler second = new CaptureCtxHandler();
        EmbeddedChannel secondChannel = new EmbeddedChannel(second);
        try {
            stream.addHttpFlvSubscriber(first.ctx);
            ByteBuf rtp = buildRtpPacket(1, 0, true, idr());
            try {
                stream.onPublisherVideoRtp(rtp);
            } finally {
                ReferenceCountUtil.safeRelease(rtp);
            }
            firstChannel.runPendingTasks();
            firstChannel.runScheduledPendingTasks();
            readOutboundBytes(firstChannel); // drain first subscriber outputs

            stream.addHttpFlvSubscriber(second.ctx);
            secondChannel.runPendingTasks();
            secondChannel.runScheduledPendingTasks();
            byte[] out2 = readOutboundBytes(secondChannel);

            assertTrue(indexOf(out2, new byte[]{'F', 'L', 'V'}) >= 0);
            assertTrue(indexOf(out2, new byte[]{0x17, 0x00, 0x00, 0x00, 0x00}) >= 0);
            assertTrue(indexOf(out2, new byte[]{0x17, 0x01, 0x00, 0x00, 0x00}) >= 0);
        } finally {
            stream.shutdown();
            firstChannel.finishAndReleaseAll();
            secondChannel.finishAndReleaseAll();
        }
    }

    private static byte[] readOutboundBytes(EmbeddedChannel channel) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            try {
                if (!(msg instanceof HttpContent)) {
                    continue;
                }
                ByteBuf content = ((HttpContent) msg).content();
                byte[] bytes = new byte[content.readableBytes()];
                content.getBytes(content.readerIndex(), bytes);
                out.write(bytes);
            } finally {
                ReferenceCountUtil.safeRelease(msg);
            }
        }
        return out.toByteArray();
    }

    private static ByteBuf buildRtpPacket(int seq, int timestamp, boolean marker, byte[] nal) {
        ByteBuf rtp = Unpooled.buffer(12 + nal.length);
        rtp.writeByte(0x80);
        rtp.writeByte((marker ? 0x80 : 0x00) | 96);
        rtp.writeShort(seq & 0xFFFF);
        rtp.writeInt(timestamp);
        rtp.writeInt(0x01020304);
        rtp.writeBytes(nal);
        return rtp;
    }

    private static byte[] sps() {
        return new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x50, 0x1E, (byte) 0xD0};
    }

    private static byte[] pps() {
        return new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80};
    }

    private static byte[] idr() {
        return new byte[]{0x65, (byte) 0x88, (byte) 0x84, 0x21};
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean matched = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    private static final class CaptureCtxHandler extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }
}
