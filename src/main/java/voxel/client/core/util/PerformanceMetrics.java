package voxel.client.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import voxel.client.RenderStatistics;
import voxel.client.TimingStatistics;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkTexture;
import voxel.common.config.storage.rocksdb.RocksDBStorageBackend;
import voxel.common.util.AtomicFiles;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.PerformanceCounters;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Frame-time distribution and counter snapshot for profiling sessions.
 *
 * <p>The hot path is a ring-buffer write per frame; sorting, JSON and CSV
 * generation only happen on an explicit {@code /vx perf dump}. Counters live in
 * the common {@link PerformanceCounters} so workers and storage can record
 * without loading client classes.</p>
 */
public final class PerformanceMetrics {

    private static final int FRAME_CAPACITY = 4096;
    private static final long GC_SPIKE_THRESHOLD_NANOS = 4_000_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private static final long[] frameNanos = new long[FRAME_CAPACITY];
    private static final int[] frameSpikes = new int[FRAME_CAPACITY];

    private static int frameIndex;
    private static int frameCount;
    private static boolean frameOpen;
    private static long frameStartNanos;
    private static long lastFrameNanos;
    private static long totalFrameNanos;
    private static long maxFrameNanos;
    private static long totalFrames;
    private static long framesWithSpikes;
    private static long lastGcTimeMillis;

    private PerformanceMetrics() {
    }

    public static boolean isEnabled() {
        return PerformanceCounters.isEnabled();
    }

    public static void setEnabled(boolean enabled) {
        if (enabled) {
            start();
        } else {
            stop();
        }
    }

    public static void start() {
        PerformanceCounters.start();
        resetFrames();
        lastGcTimeMillis = currentGcTimeMillis();
    }

    public static void stop() {
        PerformanceCounters.stop();
        frameOpen = false;
    }

    public static void reset() {
        PerformanceCounters.reset();
        resetFrames();
        lastGcTimeMillis = currentGcTimeMillis();
    }

    private static void resetFrames() {
        frameIndex = 0;
        frameCount = 0;
        frameOpen = false;
        frameStartNanos = 0L;
        lastFrameNanos = 0L;
        totalFrameNanos = 0L;
        maxFrameNanos = 0L;
        totalFrames = 0L;
        framesWithSpikes = 0L;
        Arrays.fill(frameNanos, 0L);
        Arrays.fill(frameSpikes, 0);
    }

    public static void onFrameBegin() {
        if (!PerformanceCounters.isEnabled()) {
            return;
        }
        if (frameOpen) {
            // A frame that never reached submitFrame (pause/resize). Discard it
            // rather than folding the gap into the next frame.
            frameOpen = false;
        }
        frameStartNanos = System.nanoTime();
        frameOpen = true;
    }

    public static void onFrameEnd() {
        if (!PerformanceCounters.isEnabled() || !frameOpen) {
            return;
        }
        long now = System.nanoTime();
        frameOpen = false;
        sampleGc();

        long duration = now - frameStartNanos;
        int spikes = PerformanceCounters.drainSpikeMask();

        frameNanos[frameIndex] = duration;
        frameSpikes[frameIndex] = spikes;
        frameIndex = (frameIndex + 1) % FRAME_CAPACITY;
        if (frameCount < FRAME_CAPACITY) {
            frameCount++;
        }

        lastFrameNanos = duration;
        totalFrameNanos += duration;
        totalFrames++;
        if (duration > maxFrameNanos) {
            maxFrameNanos = duration;
        }
        if (spikes != 0) {
            framesWithSpikes++;
        }
    }

    private static void sampleGc() {
        long gcTime = currentGcTimeMillis();
        if (gcTime > lastGcTimeMillis) {
            long deltaMs = gcTime - lastGcTimeMillis;
            if (deltaMs >= GC_SPIKE_THRESHOLD_NANOS / 1_000_000L) {
                PerformanceCounters.recordGcPause(deltaMs * 1_000_000L);
            }
        }
        lastGcTimeMillis = gcTime;
    }

    private static long currentGcTimeMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long time = bean.getCollectionTime();
            if (time > 0L) {
                total += time;
            }
        }
        return total;
    }

    private static long currentGcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long count = bean.getCollectionCount();
            if (count > 0L) {
                total += count;
            }
        }
        return total;
    }

    public static void addDebug(List<String> debug) {
        if (!PerformanceCounters.isEnabled()) {
            return;
        }
        double avgMs = totalFrames == 0 ? 0.0 : totalFrameNanos / (double) totalFrames / 1_000_000.0;
        debug.add(String.format(Locale.ROOT,
                "Perf: last=%.2fms avg=%.2f max=%.2f frames=%d spikes=%d waitIdle=%d barriers=%d/%d",
                lastFrameNanos / 1_000_000.0,
                avgMs,
                maxFrameNanos / 1_000_000.0,
                totalFrames,
                framesWithSpikes,
                PerformanceCounters.WAIT_IDLE_COUNT.get(),
                PerformanceCounters.FULL_BARRIER_COUNT.get(),
                PerformanceCounters.COMPUTE_BARRIER_COUNT.get()));
    }

    public static DumpResult dump(Path directory) throws IOException {
        Files.createDirectories(directory);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path csv = directory.resolve("voxel-perf-" + stamp + ".csv");
        Path json = directory.resolve("voxel-perf-" + stamp + ".json");

        long[] sorted = new long[frameCount];
        for (int i = 0; i < frameCount; i++) {
            sorted[i] = frameNanos[(frameIndex - frameCount + i + FRAME_CAPACITY) % FRAME_CAPACITY];
        }
        long[] sortedFrames = sorted.clone();
        Arrays.sort(sortedFrames);

        double avgMs = frameCount == 0 ? 0.0 : Arrays.stream(sorted).average().orElse(0.0) / 1_000_000.0;
        double p50 = percentileMs(sortedFrames, 0.50);
        double p95 = percentileMs(sortedFrames, 0.95);
        double p99 = percentileMs(sortedFrames, 0.99);
        double p999 = percentileMs(sortedFrames, 0.999);
        double max = frameCount == 0 ? 0.0 : sortedFrames[sortedFrames.length - 1] / 1_000_000.0;
        double onePercentLow = worstAverageMs(sortedFrames, Math.max(1, frameCount / 100));
        double pointOnePercentLow = worstAverageMs(sortedFrames, Math.max(1, frameCount / 1000));

        Map<String, Object> frameTime = new LinkedHashMap<>();
        frameTime.put("avg", avgMs);
        frameTime.put("p50", p50);
        frameTime.put("p95", p95);
        frameTime.put("p99", p99);
        frameTime.put("p99_9", p999);
        frameTime.put("max", max);
        frameTime.put("one_percent_low", onePercentLow);
        frameTime.put("point_one_percent_low", pointOnePercentLow);
        frameTime.put("frames", frameCount);
        frameTime.put("seconds", totalFrameNanos / 1_000_000_000.0);
        frameTime.put("fps_avg", avgMs > 0.0 ? 1_000.0 / avgMs : 0.0);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("native_buffer_count", MemoryBuffer.getCount());
        memory.put("native_buffer_bytes", MemoryBuffer.getTotalSize());
        memory.put("vulkan_buffer_count", VkBuffer.getCount());
        memory.put("vulkan_buffer_bytes", VkBuffer.getTotalSize());
        memory.put("vulkan_texture_count", VkTexture.getCount());
        memory.put("vulkan_texture_bytes", VkTexture.getEstimatedTotalSize());

        Map<String, Object> gc = new LinkedHashMap<>();
        gc.put("collection_count", currentGcCount());
        gc.put("collection_time_ms", currentGcTimeMillis());

        Map<String, Object> cpuTiming = new LinkedHashMap<>();
        cpuTiming.put("all", TimingStatistics.all.getRolling());
        cpuTiming.put("main", TimingStatistics.main.getRolling());
        cpuTiming.put("dynamic", TimingStatistics.dynamic.getRolling());
        cpuTiming.put("post_dynamic", TimingStatistics.postDynamic.getRolling());
        cpuTiming.put("setup", TimingStatistics.E.getRolling());
        cpuTiming.put("traverse", TimingStatistics.F.getRolling());
        cpuTiming.put("opaque", TimingStatistics.G.getRolling());
        cpuTiming.put("model", TimingStatistics.H.getRolling());
        cpuTiming.put("blit", TimingStatistics.I.getRolling());

        Map<String, Object> renderStats = new LinkedHashMap<>();
        renderStats.put("hierarchical_traversal", Arrays.stream(RenderStatistics.hierarchicalTraversalCounts).boxed().toList());
        renderStats.put("hierarchical_render_sections", Arrays.stream(RenderStatistics.hierarchicalRenderSections).boxed().toList());
        renderStats.put("visible_sections", Arrays.stream(RenderStatistics.visibleSections).boxed().toList());
        renderStats.put("quad_count", Arrays.stream(RenderStatistics.quadCount).boxed().toList());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated", LocalDateTime.now().toString());
        root.put("frame_time_ms", frameTime);
        root.put("spikes", PerformanceCounters.spikeSnapshot());
        root.put("spike_frames", framesWithSpikes);
        root.put("counters", PerformanceCounters.snapshot());
        root.put("gauges", PerformanceCounters.gaugeSnapshot());
        root.put("memory", memory);
        root.put("gc", gc);
        root.put("cpu_timing_ms", cpuTiming);
        root.put("render_stats", renderStats);
        root.put("gpu_timing", GPUTiming.INSTANCE.getDebug());
        root.put("rocksdb", RocksDBStorageBackend.collectStats());

        AtomicFiles.writeString(json, GSON.toJson(root));

        StringBuilder sb = new StringBuilder(64 + frameCount * 32);
        sb.append("frame,frame_ms,spike_mask,spikes\n");
        for (int i = 0; i < frameCount; i++) {
            int index = (frameIndex - frameCount + i + FRAME_CAPACITY) % FRAME_CAPACITY;
            int mask = frameSpikes[index];
            sb.append(i).append(',')
                    .append(String.format(Locale.ROOT, "%.4f", frameNanos[index] / 1_000_000.0)).append(',')
                    .append(mask).append(',')
                    .append(spikeNames(mask)).append('\n');
        }
        AtomicFiles.writeString(csv, sb.toString());
        return new DumpResult(json, csv, frameCount);
    }

    private static double percentileMs(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index] / 1_000_000.0;
    }

    private static double worstAverageMs(long[] sorted, int count) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int n = Math.min(count, sorted.length);
        long sum = 0L;
        for (int i = sorted.length - n; i < sorted.length; i++) {
            sum += sorted[i];
        }
        return sum / (double) n / 1_000_000.0;
    }

    private static String spikeNames(int mask) {
        StringBuilder sb = new StringBuilder();
        for (PerformanceCounters.Spike spike : PerformanceCounters.Spike.values()) {
            if ((mask & (1 << spike.ordinal())) == 0) {
                continue;
            }
            if (sb.length() != 0) {
                sb.append('|');
            }
            sb.append(spike.name());
        }
        return sb.toString();
    }

    public record DumpResult(Path json, Path csv, int frames) {
    }
}
