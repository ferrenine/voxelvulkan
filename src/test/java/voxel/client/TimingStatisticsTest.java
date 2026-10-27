package voxel.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingStatisticsTest {

    @Test
    void updateSafeDiscardsAnInterruptedSamplerInsteadOfThrowing() {
        TimingStatistics.TimeSampler sampler = new TimingStatistics.TimeSampler();
        sampler.start();

        assertDoesNotThrow(TimingStatistics::updateSafe);

        // The interrupted frame was discarded, so the sampler can be reused.
        assertDoesNotThrow(sampler::start);
        sampler.stop();
        TimingStatistics.updateSafe();
        assertTrue(sampler.getRolling() >= 0.0);
    }
}
