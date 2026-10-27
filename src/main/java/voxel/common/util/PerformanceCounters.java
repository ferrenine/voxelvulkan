package voxel.common.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cheap, always-safe counters used by the renderer, storage and mesh workers.
 *
 * <p>Recording is gated by {@link #enabled}; when disabled every helper is a
 * single volatile read and returns. Counters are atomic because disk reads,
 * mesh syncing and GPU waits can happen on different threads.</p>
 */
public final class PerformanceCounters {

    public enum Spike {
        DEVICE_WAIT,
        UPLOAD_OVERFLOW,
        DOWNLOAD_OVERFLOW,
        MESH_BURST,
        PIPELINE_CREATE,
        DISK_READ,
        DISK_WRITE,
        HEAP_GROW,
        GC_PAUSE
    }

    private static final Spike[] SPIKES = Spike.values();
    private static final AtomicLong[] SPIKE_COUNTS = new AtomicLong[SPIKES.length];
    private static final AtomicInteger CURRENT_SPIKES = new AtomicInteger();

    public static volatile boolean enabled;

    public static final AtomicLong WAIT_IDLE_COUNT = new AtomicLong();
    public static final AtomicLong WAIT_IDLE_NANOS = new AtomicLong();
    public static final AtomicLong MEMORY_BARRIER_COUNT = new AtomicLong();
    public static final AtomicLong FULL_BARRIER_COUNT = new AtomicLong();
    public static final AtomicLong COMPUTE_BARRIER_COUNT = new AtomicLong();
    public static final AtomicLong UPLOAD_COUNT = new AtomicLong();
    public static final AtomicLong UPLOAD_BYTES = new AtomicLong();
    public static final AtomicLong UPLOAD_OVERFLOW_COUNT = new AtomicLong();
    public static final AtomicLong DOWNLOAD_COUNT = new AtomicLong();
    public static final AtomicLong DOWNLOAD_BYTES = new AtomicLong();
    public static final AtomicLong DOWNLOAD_COMMIT_COUNT = new AtomicLong();
    public static final AtomicLong DOWNLOAD_BATCH_MAX = new AtomicLong();
    public static final AtomicLong DOWNLOAD_OVERFLOW_COUNT = new AtomicLong();
    public static final AtomicLong PIPELINE_CREATE_COUNT = new AtomicLong();
    public static final AtomicLong PIPELINE_CREATE_NANOS = new AtomicLong();
    public static final AtomicLong MESH_SYNC_JOBS = new AtomicLong();
    public static final AtomicLong MESH_SYNC_BYTES = new AtomicLong();
    public static final AtomicLong GEOMETRY_CACHE_HITS = new AtomicLong();
    public static final AtomicLong GEOMETRY_CACHE_MISSES = new AtomicLong();
    public static final AtomicLong GEOMETRY_CLONE_BYTES = new AtomicLong();
    public static final AtomicLong HEAP_GROW_COUNT = new AtomicLong();
    public static final AtomicLong HEAP_GROW_BYTES = new AtomicLong();
    public static final AtomicLong DISK_READ_COUNT = new AtomicLong();
    public static final AtomicLong DISK_READ_NANOS = new AtomicLong();
    public static final AtomicLong DISK_READ_BYTES = new AtomicLong();
    public static final AtomicLong DISK_WRITE_COUNT = new AtomicLong();
    public static final AtomicLong DISK_WRITE_NANOS = new AtomicLong();
    public static final AtomicLong DISK_WRITE_BYTES = new AtomicLong();
    public static final AtomicLong GC_PAUSE_COUNT = new AtomicLong();
    public static final AtomicLong GC_PAUSE_NANOS = new AtomicLong();

    // Gauges are sampled once per frame by the renderer.
    public static final AtomicLong MESH_QUEUE = new AtomicLong();
    public static final AtomicLong MESH_FAILED = new AtomicLong();
    public static final AtomicLong GEOMETRY_UPDATE_QUEUE = new AtomicLong();
    public static final AtomicLong GEOMETRY_USED = new AtomicLong();
    public static final AtomicLong GEOMETRY_CAPACITY = new AtomicLong();
    public static final AtomicLong MODEL_QUEUE = new AtomicLong();

    static {
        for (int i = 0; i < SPIKES.length; i++) {
            SPIKE_COUNTS[i] = new AtomicLong();
        }
    }

    private PerformanceCounters() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void start() {
        reset();
        enabled = true;
    }

    public static void stop() {
        enabled = false;
    }

    public static void reset() {
        WAIT_IDLE_COUNT.set(0);
        WAIT_IDLE_NANOS.set(0);
        MEMORY_BARRIER_COUNT.set(0);
        FULL_BARRIER_COUNT.set(0);
        COMPUTE_BARRIER_COUNT.set(0);
        UPLOAD_COUNT.set(0);
        UPLOAD_BYTES.set(0);
        UPLOAD_OVERFLOW_COUNT.set(0);
        DOWNLOAD_COUNT.set(0);
        DOWNLOAD_BYTES.set(0);
        DOWNLOAD_COMMIT_COUNT.set(0);
        DOWNLOAD_BATCH_MAX.set(0);
        DOWNLOAD_OVERFLOW_COUNT.set(0);
        PIPELINE_CREATE_COUNT.set(0);
        PIPELINE_CREATE_NANOS.set(0);
        MESH_SYNC_JOBS.set(0);
        MESH_SYNC_BYTES.set(0);
        GEOMETRY_CACHE_HITS.set(0);
        GEOMETRY_CACHE_MISSES.set(0);
        GEOMETRY_CLONE_BYTES.set(0);
        HEAP_GROW_COUNT.set(0);
        HEAP_GROW_BYTES.set(0);
        DISK_READ_COUNT.set(0);
        DISK_READ_NANOS.set(0);
        DISK_READ_BYTES.set(0);
        DISK_WRITE_COUNT.set(0);
        DISK_WRITE_NANOS.set(0);
        DISK_WRITE_BYTES.set(0);
        GC_PAUSE_COUNT.set(0);
        GC_PAUSE_NANOS.set(0);
        MESH_QUEUE.set(0);
        MESH_FAILED.set(0);
        GEOMETRY_UPDATE_QUEUE.set(0);
        GEOMETRY_USED.set(0);
        GEOMETRY_CAPACITY.set(0);
        MODEL_QUEUE.set(0);
        for (AtomicLong count : SPIKE_COUNTS) {
            count.set(0);
        }
        CURRENT_SPIKES.set(0);
    }

    /** Records a spike reason on the current frame and in the lifetime totals. */
    public static void mark(Spike spike) {
        if (!enabled) {
            return;
        }
        CURRENT_SPIKES.accumulateAndGet(1 << spike.ordinal(), (a, b) -> a | b);
        SPIKE_COUNTS[spike.ordinal()].incrementAndGet();
    }

    public static int drainSpikeMask() {
        return CURRENT_SPIKES.getAndSet(0);
    }

    public static void resetSpikeMask() {
        CURRENT_SPIKES.set(0);
    }

    public static void recordWaitIdle(long nanos) {
        if (!enabled) {
            return;
        }
        WAIT_IDLE_COUNT.incrementAndGet();
        WAIT_IDLE_NANOS.addAndGet(nanos);
        mark(Spike.DEVICE_WAIT);
    }

    public static void recordMemoryBarrier() {
        if (enabled) {
            MEMORY_BARRIER_COUNT.incrementAndGet();
        }
    }

    public static void recordFullBarrier() {
        if (enabled) {
            FULL_BARRIER_COUNT.incrementAndGet();
        }
    }

    public static void recordComputeBarrier() {
        if (enabled) {
            COMPUTE_BARRIER_COUNT.incrementAndGet();
        }
    }

    public static void recordUpload(long bytes) {
        if (!enabled) {
            return;
        }
        UPLOAD_COUNT.incrementAndGet();
        UPLOAD_BYTES.addAndGet(bytes);
    }

    public static void recordUploadOverflow() {
        if (!enabled) {
            return;
        }
        UPLOAD_OVERFLOW_COUNT.incrementAndGet();
        mark(Spike.UPLOAD_OVERFLOW);
    }

    public static void recordDownload(long bytes) {
        if (!enabled) {
            return;
        }
        DOWNLOAD_COUNT.incrementAndGet();
        DOWNLOAD_BYTES.addAndGet(bytes);
    }

    public static void recordDownloadCommit(int batchSize) {
        if (!enabled) {
            return;
        }
        DOWNLOAD_COMMIT_COUNT.incrementAndGet();
        DOWNLOAD_BATCH_MAX.accumulateAndGet(batchSize, Math::max);
    }

    public static void recordDownloadOverflow() {
        if (!enabled) {
            return;
        }
        DOWNLOAD_OVERFLOW_COUNT.incrementAndGet();
        mark(Spike.DOWNLOAD_OVERFLOW);
    }

    public static void recordPipelineCreate(long nanos) {
        if (!enabled) {
            return;
        }
        PIPELINE_CREATE_COUNT.incrementAndGet();
        PIPELINE_CREATE_NANOS.addAndGet(nanos);
        mark(Spike.PIPELINE_CREATE);
    }

    public static void recordMeshSync(int jobs, long bytes, boolean budgetHit) {
        if (!enabled) {
            return;
        }
        MESH_SYNC_JOBS.addAndGet(jobs);
        MESH_SYNC_BYTES.addAndGet(bytes);
        if (budgetHit) {
            mark(Spike.MESH_BURST);
        }
    }

    public static void recordGeometryCache(boolean hit) {
        if (!enabled) {
            return;
        }
        if (hit) {
            GEOMETRY_CACHE_HITS.incrementAndGet();
        } else {
            GEOMETRY_CACHE_MISSES.incrementAndGet();
        }
    }

    public static void recordGeometryClone(long bytes) {
        if (enabled) {
            GEOMETRY_CLONE_BYTES.addAndGet(bytes);
        }
    }

    public static void recordHeapGrow(long bytes) {
        if (!enabled) {
            return;
        }
        HEAP_GROW_COUNT.incrementAndGet();
        HEAP_GROW_BYTES.addAndGet(bytes);
        mark(Spike.HEAP_GROW);
    }

    public static void recordDiskRead(long nanos, long bytes) {
        if (!enabled) {
            return;
        }
        DISK_READ_COUNT.incrementAndGet();
        DISK_READ_NANOS.addAndGet(nanos);
        DISK_READ_BYTES.addAndGet(bytes);
        if (nanos > 2_000_000L) {
            mark(Spike.DISK_READ);
        }
    }

    public static void recordDiskWrite(long nanos, long bytes) {
        if (!enabled) {
            return;
        }
        DISK_WRITE_COUNT.incrementAndGet();
        DISK_WRITE_NANOS.addAndGet(nanos);
        DISK_WRITE_BYTES.addAndGet(bytes);
        if (nanos > 2_000_000L) {
            mark(Spike.DISK_WRITE);
        }
    }

    public static void recordGcPause(long nanos) {
        if (!enabled) {
            return;
        }
        GC_PAUSE_COUNT.incrementAndGet();
        GC_PAUSE_NANOS.addAndGet(nanos);
        mark(Spike.GC_PAUSE);
    }

    public static void setMeshQueue(long value) {
        if (enabled) {
            MESH_QUEUE.set(value);
        }
    }

    public static void setMeshFailed(long value) {
        if (enabled) {
            MESH_FAILED.set(value);
        }
    }

    public static void setGeometryUpdateQueue(long value) {
        if (enabled) {
            GEOMETRY_UPDATE_QUEUE.set(value);
        }
    }

    public static void setGeometryUsage(long used, long capacity) {
        if (enabled) {
            GEOMETRY_USED.set(used);
            GEOMETRY_CAPACITY.set(capacity);
        }
    }

    public static void setModelQueue(long value) {
        if (enabled) {
            MODEL_QUEUE.set(value);
        }
    }

    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("wait_idle_count", WAIT_IDLE_COUNT.get());
        out.put("wait_idle_nanos", WAIT_IDLE_NANOS.get());
        out.put("memory_barrier_count", MEMORY_BARRIER_COUNT.get());
        out.put("full_barrier_count", FULL_BARRIER_COUNT.get());
        out.put("compute_barrier_count", COMPUTE_BARRIER_COUNT.get());
        out.put("upload_count", UPLOAD_COUNT.get());
        out.put("upload_bytes", UPLOAD_BYTES.get());
        out.put("upload_overflow_count", UPLOAD_OVERFLOW_COUNT.get());
        out.put("download_count", DOWNLOAD_COUNT.get());
        out.put("download_bytes", DOWNLOAD_BYTES.get());
        out.put("download_commit_count", DOWNLOAD_COMMIT_COUNT.get());
        out.put("download_batch_max", DOWNLOAD_BATCH_MAX.get());
        out.put("download_overflow_count", DOWNLOAD_OVERFLOW_COUNT.get());
        out.put("pipeline_create_count", PIPELINE_CREATE_COUNT.get());
        out.put("pipeline_create_nanos", PIPELINE_CREATE_NANOS.get());
        out.put("mesh_sync_jobs", MESH_SYNC_JOBS.get());
        out.put("mesh_sync_bytes", MESH_SYNC_BYTES.get());
        out.put("geometry_cache_hits", GEOMETRY_CACHE_HITS.get());
        out.put("geometry_cache_misses", GEOMETRY_CACHE_MISSES.get());
        out.put("geometry_clone_bytes", GEOMETRY_CLONE_BYTES.get());
        out.put("heap_grow_count", HEAP_GROW_COUNT.get());
        out.put("heap_grow_bytes", HEAP_GROW_BYTES.get());
        out.put("disk_read_count", DISK_READ_COUNT.get());
        out.put("disk_read_nanos", DISK_READ_NANOS.get());
        out.put("disk_read_bytes", DISK_READ_BYTES.get());
        out.put("disk_write_count", DISK_WRITE_COUNT.get());
        out.put("disk_write_nanos", DISK_WRITE_NANOS.get());
        out.put("disk_write_bytes", DISK_WRITE_BYTES.get());
        out.put("gc_pause_count", GC_PAUSE_COUNT.get());
        out.put("gc_pause_nanos", GC_PAUSE_NANOS.get());
        return out;
    }

    public static Map<String, Long> spikeSnapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < SPIKES.length; i++) {
            out.put(SPIKES[i].name().toLowerCase(), SPIKE_COUNTS[i].get());
        }
        return out;
    }

    public static Map<String, Long> gaugeSnapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("mesh_queue", MESH_QUEUE.get());
        out.put("mesh_failed", MESH_FAILED.get());
        out.put("geometry_update_queue", GEOMETRY_UPDATE_QUEUE.get());
        out.put("geometry_used_bytes", GEOMETRY_USED.get());
        out.put("geometry_capacity_bytes", GEOMETRY_CAPACITY.get());
        out.put("model_queue", MODEL_QUEUE.get());
        return out;
    }
}
