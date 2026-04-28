package com.wenting.mediaserver.core.webrtc;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DTLS engine output: optional outbound DTLS packets and whether handshake reached established state.
 */
public final class WebRtcDtlsEngineResult {

    private static final WebRtcDtlsEngineResult NO_CHANGE =
            new WebRtcDtlsEngineResult(false, false, Collections.<ByteBuf>emptyList(), null);

    private final boolean stateChanged;
    private final boolean established;
    private final List<ByteBuf> outboundPackets;
    private final WebRtcSrtpTransformer srtpTransformer;

    private WebRtcDtlsEngineResult(
            boolean stateChanged,
            boolean established,
            List<ByteBuf> outboundPackets,
            WebRtcSrtpTransformer srtpTransformer) {
        this.stateChanged = stateChanged;
        this.established = established;
        this.outboundPackets = outboundPackets == null
                ? Collections.<ByteBuf>emptyList()
                : Collections.unmodifiableList(new ArrayList<ByteBuf>(outboundPackets));
        this.srtpTransformer = srtpTransformer;
    }

    public boolean stateChanged() {
        return stateChanged;
    }

    public boolean established() {
        return established;
    }

    public List<ByteBuf> outboundPackets() {
        return outboundPackets;
    }

    public WebRtcSrtpTransformer srtpTransformer() {
        return srtpTransformer;
    }

    public static WebRtcDtlsEngineResult noChange() {
        return NO_CHANGE;
    }

    public static WebRtcDtlsEngineResult established(WebRtcSrtpTransformer transformer) {
        return new WebRtcDtlsEngineResult(true, true, Collections.<ByteBuf>emptyList(), transformer);
    }

    public static WebRtcDtlsEngineResult established(List<ByteBuf> packets, WebRtcSrtpTransformer transformer) {
        return new WebRtcDtlsEngineResult(true, true, packets, transformer);
    }

    public static WebRtcDtlsEngineResult outbound(List<ByteBuf> packets) {
        if (packets == null || packets.isEmpty()) {
            return NO_CHANGE;
        }
        return new WebRtcDtlsEngineResult(false, false, packets, null);
    }
}
