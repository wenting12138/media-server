package com.wenting.mediaserver.protocol.rtsp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspTcpFramingDecoderTest {

    @Test
    void decodesInterleavedThenRtsp() {
        EmbeddedChannel ch = new EmbeddedChannel(new RtspTcpFramingDecoder());
        ByteBuf in = Unpooled.buffer();
        in.writeByte(0x24);
        in.writeByte(0);
        in.writeShort(2);
        in.writeByte(0x80);
        in.writeByte(0x60);
        String req = "OPTIONS rtsp://x/live/a RTSP/1.0\r\nCSeq: 1\r\n\r\n";
        in.writeBytes(req.getBytes(CharsetUtil.US_ASCII));
        assertTrue(ch.writeInbound(in));
        InterleavedRtpPacket rtp = (InterleavedRtpPacket) ch.readInbound();
        try {
            assertEquals(0, rtp.channel());
            assertEquals(2, rtp.payload().readableBytes());
        } finally {
            rtp.release();
        }
        RtspRequestMessage rtsp = (RtspRequestMessage) ch.readInbound();
        try {
            assertEquals("OPTIONS", rtsp.method());
            assertEquals(1, rtsp.cSeq());
        } finally {
            ReferenceCountUtil.release(rtsp.body());
        }
    }

    @Test
    void decodesRtspWithBody() {
        EmbeddedChannel ch = new EmbeddedChannel(new RtspTcpFramingDecoder());
        String body = "v=0\r\n";
        String req = "ANNOUNCE rtsp://x/live/s RTSP/1.0\r\n"
                + "CSeq: 2\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "\r\n"
                + body;
        assertTrue(ch.writeInbound(Unpooled.copiedBuffer(req, CharsetUtil.US_ASCII)));
        RtspRequestMessage rtsp = (RtspRequestMessage) ch.readInbound();
        try {
            assertEquals("ANNOUNCE", rtsp.method());
            assertEquals(body, rtsp.body().toString(CharsetUtil.US_ASCII));
        } finally {
            ReferenceCountUtil.release(rtsp.body());
        }
    }

    @Test
    void rtspRequestMessageToString_smoke() {
        String body = "v=0\r\n";
        String req = "ANNOUNCE rtsp://x/live/s RTSP/1.0\r\n"
                + "CSeq: 42\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "\r\n"
                + body;
        RtspRequestMessage rtsp = RtspRequestMessage.parse(Unpooled.copiedBuffer(req, CharsetUtil.US_ASCII));
        try {
            String s = rtsp.toString();
            assertTrue(s.contains("method=ANNOUNCE"));
            assertTrue(s.contains("uri=rtsp://x/live/s"));
            assertTrue(s.contains("cSeq=42"));
            assertTrue(s.contains("bodyBytes=" + body.length()));
            assertTrue(s.contains("bodyPreview=v=0\\r\\n"));
        } finally {
            ReferenceCountUtil.release(rtsp.body());
        }
    }
}
