package voxel.client.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveLodTest {

    @Test
    void coarsensWhenFpsIsLow() {
        assertEquals(112.0f, AdaptiveLod.step(96.0f, 96.0f, 40, true));
        assertEquals(256.0f, AdaptiveLod.step(248.0f, 96.0f, 40, true));
    }

    @Test
    void refinesWhenFpsIsHighAndTheQueueIsShort() {
        assertEquals(104.0f, AdaptiveLod.step(112.0f, 96.0f, 80, true));
        assertEquals(96.0f, AdaptiveLod.step(100.0f, 96.0f, 80, true));
    }

    @Test
    void neverRefinesWhileTheMeshQueueIsBusy() {
        assertEquals(112.0f, AdaptiveLod.step(112.0f, 96.0f, 80, false));
    }

    @Test
    void configuredValueIsTheQualityFloor() {
        assertEquals(200.0f, AdaptiveLod.step(96.0f, 200.0f, 80, true));
        assertEquals(216.0f, AdaptiveLod.step(200.0f, 200.0f, 40, true));
        assertEquals(64.0f, AdaptiveLod.step(32.0f, 32.0f, 80, true));
    }
}
