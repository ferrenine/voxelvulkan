package voxel.client.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceMetricsTest {

    @TempDir
    Path directory;

    @AfterEach
    void tearDown() {
        PerformanceMetrics.stop();
        PerformanceMetrics.reset();
    }

    @Test
    void dumpWritesJsonAndCsv() throws IOException {
        PerformanceMetrics.start();
        for (int i = 0; i < 8; i++) {
            PerformanceMetrics.onFrameBegin();
            PerformanceMetrics.onFrameEnd();
        }

        PerformanceMetrics.DumpResult result = PerformanceMetrics.dump(this.directory);

        assertTrue(Files.exists(result.json()));
        assertTrue(Files.exists(result.csv()));
        assertTrue(Files.readString(result.json()).contains("\"frame_time_ms\""));
        assertTrue(Files.readString(result.json()).contains("\"stw_collection_count\""));
        assertTrue(Files.readString(result.csv()).startsWith("frame,frame_ms,spike_mask,spikes"));
        assertTrue(result.frames() > 0);
    }

    @Test
    void concurrentCollectorsAreNotReportedAsPauses() {
        assertTrue(PerformanceMetrics.isStopTheWorldCollector("G1 Young Generation"));
        assertTrue(PerformanceMetrics.isStopTheWorldCollector("G1 Old Generation"));
        assertTrue(PerformanceMetrics.isStopTheWorldCollector("ZGC Pauses"));
        assertFalse(PerformanceMetrics.isStopTheWorldCollector("G1 Concurrent GC"));
        assertFalse(PerformanceMetrics.isStopTheWorldCollector("ZGC Cycles"));
        assertFalse(PerformanceMetrics.isStopTheWorldCollector("Shenandoah Cycles"));
    }
}
