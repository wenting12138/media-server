package com.wenting.mediaserver.core.transcode;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.model.StreamKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stream-level ffmpeg transcode worker manager.
 */
public final class FfmpegTranscodeProcessor implements StreamTranscoder {

    private static final Logger log = LoggerFactory.getLogger(FfmpegTranscodeProcessor.class);
    private static final String DRAW_TEXT_TEMPLATE =
            "drawtext=fontfile='%s':text='%%{localtime}':x=10:y=10:fontsize=24:fontcolor=white:box=1:boxcolor=0x00000099";
    private static final String[] FONT_CANDIDATES = new String[]{
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/Library/Fonts/Arial.ttf"
    };

    private final boolean enabled;
    private final String ffmpegBin;
    private final String inputHost;
    private final int rtspPort;
    private final int rtmpPort;
    private final String outputSuffix;
    private final int queueSize;
    private final String drawTextFilter;
    private final Map<StreamKey, Worker> workers = new ConcurrentHashMap<StreamKey, Worker>();

    public FfmpegTranscodeProcessor(MediaServerConfig config) {
        this.enabled = config.transcodeEnabled();
        this.ffmpegBin = config.ffmpegBin();
        this.inputHost = config.transcodeInputHost();
        this.rtspPort = config.rtspPort();
        this.rtmpPort = config.rtmpPort();
        this.outputSuffix = config.transcodeOutputSuffix();
        this.queueSize = config.transcodeQueueSize();
        this.drawTextFilter = buildDrawTextFilter();
        if (enabled) {
            log.info("FFmpeg transcode enabled (bin={}, host={}, rtspPort={}, rtmpPort={}, suffix={}, queue={}, drawtext={})",
                    ffmpegBin, inputHost, rtspPort, rtmpPort, outputSuffix, queueSize, drawTextFilter);
        } else {
            log.info("FFmpeg transcode disabled");
        }
    }

    @Override
    public String name() {
        return "ffmpeg";
    }

    @Override
    public void onPublishStart(PublishContext context) {
        StreamKey key = context.streamKey();
        String sdpText = context.sdpText();
        if (!enabled || isDerivedStream(key)) {
            return;
        }
        Worker worker = new Worker(key, queueSize, sdpText != null && !sdpText.trim().isEmpty());
        Worker prev = workers.putIfAbsent(key, worker);
        if (prev == null) {
            worker.start();
        }
    }

    @Override
    public void onPacket(PublishContext context, EncodedMediaPacket packet) {
        StreamKey key = context.streamKey();
        if (!enabled || isDerivedStream(key)) {
            return;
        }
        Worker worker = workers.get(key);
        if (worker != null) {
            worker.enqueue(packet);
        }
    }

    @Override
    public void onPublishStop(PublishContext context) {
        StreamKey key = context.streamKey();
        Worker worker = workers.remove(key);
        if (worker != null) {
            worker.stop();
        }
    }

    @Override
    public void close() {
        for (Worker worker : workers.values()) {
            worker.stop();
        }
        workers.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    private boolean isDerivedStream(StreamKey key) {
        return key.stream().endsWith(outputSuffix);
    }

    private final class Worker implements Runnable {
        private static final int LOG_TAIL_SIZE = 40;
        private final StreamKey key;
        private final ArrayBlockingQueue<FrameEvent> queue;
        private final ArrayBlockingQueue<String> ffmpegLogTail = new ArrayBlockingQueue<String>(LOG_TAIL_SIZE);
        private final AtomicLong droppedEvents = new AtomicLong();
        private final AtomicLong consumedEvents = new AtomicLong();
        private final boolean rtspSource;
        private final Thread thread;
        private volatile boolean running = true;
        private volatile Process process;

        private Worker(StreamKey key, int queueSize, boolean rtspSource) {
            this.key = key;
            this.queue = new ArrayBlockingQueue<FrameEvent>(Math.max(64, queueSize));
            this.rtspSource = rtspSource;
            this.thread = new Thread(this, "ffmpeg-transcode-" + key.path().replace('/', '_'));
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void stop() {
            running = false;
            thread.interrupt();
            destroyProcess();
            try {
                thread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void enqueue(EncodedMediaPacket packet) {
            FrameEvent event = new FrameEvent(packet.trackType(), packet.timestamp(), packet.payload().readableBytes());
            if (!queue.offer(event)) {
                queue.poll();
                if (!queue.offer(event)) {
                    droppedEvents.incrementAndGet();
                }
            }
        }

        @Override
        public void run() {
            try {
                process = startProcess(key);
                long lastLogMs = System.currentTimeMillis();
                while (running) {
                    FrameEvent ev = queue.poll(500L, TimeUnit.MILLISECONDS);
                    if (ev != null) {
                        consumedEvents.incrementAndGet();
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastLogMs >= 5000L) {
                        lastLogMs = now;
                        log.info("Transcode worker stream={} q={} consumed={} dropped={}",
                                key.path(),
                                queue.size(),
                                consumedEvents.get(),
                                droppedEvents.get());
                    }
                    Process p = process;
                    if (p != null && !p.isAlive()) {
                        int exitCode = p.exitValue();
                        if (running) {
                            log.warn("ffmpeg exited for stream {} with code {} tail={}", key.path(), exitCode, dumpTailLogs());
                        }
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Transcode worker failed for stream {}", key.path(), e);
            } finally {
                destroyProcess();
                log.info("Transcode worker stopped for stream={} consumed={} dropped={}",
                        key.path(), consumedEvents.get(), droppedEvents.get());
            }
        }

        private Process startProcess(StreamKey key) throws IOException {
            String inputUrl = rtspSource
                    ? "rtsp://" + inputHost + ":" + rtspPort + "/" + key.path()
                    : "rtmp://" + inputHost + ":" + rtmpPort + "/" + key.path();
            String outputRtsp = "rtsp://" + inputHost + ":" + rtspPort + "/" + key.app() + "/" + key.stream() + outputSuffix;
            String outputRtmp = "rtmp://" + inputHost + ":" + rtmpPort + "/" + key.app() + "/" + key.stream() + outputSuffix;
            boolean outputToRtsp = rtspSource;
            String outputUrl = outputToRtsp ? outputRtsp : outputRtmp;

            List<String> command = new ArrayList<String>();
            command.add(ffmpegBin);
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("info");
            command.add("-fflags");
            command.add("nobuffer");
            if (rtspSource) {
                command.add("-rtsp_transport");
                command.add("tcp");
            }
            command.add("-i");
            command.add(inputUrl);
            command.add("-vf");
            command.add(drawTextFilter);
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add("veryfast");
            command.add("-tune");
            command.add("zerolatency");
            command.add("-g");
            command.add("25");
            command.add("-keyint_min");
            command.add("25");
            command.add("-sc_threshold");
            command.add("0");
            command.add("-map");
            command.add("0:v:0");
            command.add("-map");
            command.add("0:a:0?");
            command.add("-c:a");
            command.add("aac");
            if (outputToRtsp) {
                command.add("-rtsp_transport");
                command.add("tcp");
            }
            command.add("-f");
            command.add(outputToRtsp ? "rtsp" : "flv");
            command.add(outputUrl);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            startLogPump(key, p);
            log.info("Started ffmpeg transcode stream={} source={} input={} output={}",
                    key.path(), rtspSource ? "rtsp" : "rtmp", inputUrl, outputUrl);
            return p;
        }

        private void startLogPump(StreamKey key, Process p) {
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        pushTailLog(line);
                        if (line.contains("Error") || line.contains("error")) {
                            log.warn("ffmpeg[{}] {}", key.path(), line);
                        } else {
                            log.debug("ffmpeg[{}] {}", key.path(), line);
                        }
                    }
                } catch (IOException e) {
                    log.debug("ffmpeg log stream closed for {}", key.path());
                }
            }, "ffmpeg-log-" + key.path().replace('/', '_'));
            t.setDaemon(true);
            t.start();
        }

        private void pushTailLog(String line) {
            if (line == null) {
                return;
            }
            while (!ffmpegLogTail.offer(line)) {
                ffmpegLogTail.poll();
            }
        }

        private String dumpTailLogs() {
            StringBuilder sb = new StringBuilder();
            for (String s : ffmpegLogTail) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(s);
            }
            return sb.toString();
        }

        private void destroyProcess() {
            Process p = process;
            process = null;
            if (p == null) {
                return;
            }
            p.destroy();
            try {
                if (!p.waitFor(1, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    private String buildDrawTextFilter() {
        String font = resolveFontFile();
        if (font == null) {
            log.warn("No explicit font file found for drawtext; fallback may depend on fontconfig availability");
            return "drawtext=text='%{localtime}':x=10:y=10:fontsize=24:fontcolor=white:box=1:boxcolor=0x00000099";
        }
        String escaped = escapeFilterValue(font);
        return String.format(DRAW_TEXT_TEMPLATE, escaped);
    }

    private String resolveFontFile() {
        for (String candidate : FONT_CANDIDATES) {
            File file = new File(candidate);
            if (file.exists() && file.isFile()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private String escapeFilterValue(String value) {
        String normalized = value.replace("\\", "/");
        return normalized.replace(":", "\\:");
    }

    private static final class FrameEvent {
        private final EncodedMediaPacket.TrackType kind;
        private final int timestamp;
        private final int payloadSize;

        private FrameEvent(EncodedMediaPacket.TrackType kind, int timestamp, int payloadSize) {
            this.kind = kind;
            this.timestamp = timestamp;
            this.payloadSize = payloadSize;
        }
    }
}
