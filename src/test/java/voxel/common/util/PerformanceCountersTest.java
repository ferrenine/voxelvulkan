package voxel.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceCountersTest {

    @AfterEach
    void tearDown() {
        PerformanceCounters.stop();
        PerformanceCounters.reset();
    }

    @Test
    void recordsCountersAndSpikeReasons() {
        PerformanceCounters.start();

        PerformanceCounters.recordWaitIdle(1_000L);
        PerformanceCounters.recordUpload(100L);
        PerformanceCounters.recordUploadOverflow();
        PerformanceCounters.recordDownload(200L);
        PerformanceCounters.recordDownloadCommit(3);
        PerformanceCounters.recordDownloadOverflow();
        PerformanceCounters.recordMemoryBarrier();
        PerformanceCounters.recordFullBarrier();
        PerformanceCounters.recordComputeBarrier();
        PerformanceCounters.recordPipelineCreate(2_000_000L);
        PerformanceCounters.recordMeshSync(2, 300L, true);
        PerformanceCounters.recordGeometryCache(true);
        PerformanceCounters.recordGeometryCache(false);
        PerformanceCounters.recordGeometryClone(512L);
        PerformanceCounters.recordHeapGrow(1_024L);
        PerformanceCounters.recordDiskRead(3_000_000L, 600L);
        PerformanceCounters.recordDiskWrite(1_000_000L, 600L);
        PerformanceCounters.recordGcPause(5_000_000L);

        int mask = PerformanceCounters.drainSpikeMask();
        assertTrue(has(mask, PerformanceCounters.Spike.DEVICE_WAIT));
        assertTrue(has(mask, PerformanceCounters.Spike.UPLOAD_OVERFLOW));
        assertTrue(has(mask, PerformanceCounters.Spike.DOWNLOAD_OVERFLOW));
        assertTrue(has(mask, PerformanceCounters.Spike.PIPELINE_CREATE));
        assertTrue(has(mask, PerformanceCounters.Spike.MESH_BURST));
        assertTrue(has(mask, PerformanceCounters.Spike.HEAP_GROW));
        assertTrue(has(mask, PerformanceCounters.Spike.DISK_READ));
        assertTrue(has(mask, PerformanceCounters.Spike.GC_PAUSE));
        assertFalse(has(mask, PerformanceCounters.Spike.DISK_WRITE)); // 1 ms is below the spike threshold

        assertEquals(1L, PerformanceCounters.snapshot().get("wait_idle_count"));
        assertEquals(100L, PerformanceCounters.snapshot().get("upload_bytes"));
        assertEquals(200L, PerformanceCounters.snapshot().get("download_bytes"));
        assertEquals(1L, PerformanceCounters.snapshot().get("geometry_cache_hits"));
        assertEquals(1L, PerformanceCounters.snapshot().get("geometry_cache_misses"));
        assertEquals(512L, PerformanceCounters.snapshot().get("geometry_clone_bytes"));
        assertEquals(3L, PerformanceCounters.snapshot().get("download_batch_max"));
    }

    @Test
    void stoppedCountersIgnoreNewEvents() {
        PerformanceCounters.stop();
        PerformanceCounters.recordWaitIdle(1_000L);
        PerformanceCounters.mark(PerformanceCounters.Spike.DEVICE_WAIT);
        assertEquals(0, PerformanceCounters.drainSpikeMask());
        assertEquals(0L, PerformanceCounters.WAIT_IDLE_COUNT.get());
    }

    private static boolean has(int mask, PerformanceCounters.Spike spike) {
        return (mask & (1 << spike.ordinal())) != 0;
    }
}
