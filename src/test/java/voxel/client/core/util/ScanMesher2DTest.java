package voxel.client.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanMesher2DTest {

    @Test
    void mergesAUniform32x32PlaneIntoFour16x16Quads() {
        CountingMesher mesher = new CountingMesher();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                mesher.putNext(1L);
            }
            mesher.endRow();
        }
        mesher.finish();

        // 1024 cells -> four 16x16 rectangles. The 16-cell cap is the quad
        // format's 4-bit size field, not an algorithmic limit.
        assertEquals(4, mesher.quads);
    }

    @Test
    void keepsDifferentBlockKeysInSeparateQuads() {
        CountingMesher mesher = new CountingMesher();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                mesher.putNext(x < 16 ? 1L : 2L);
            }
            mesher.endRow();
        }
        mesher.finish();

        // Two 16-column patches, each split into two 16-row rectangles.
        assertEquals(4, mesher.quads);
    }

    @Test
    void doesNotMergeAcrossAnAirGap() {
        CountingMesher mesher = new CountingMesher();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 16; x++) {
                mesher.putNext(1L);
            }
            mesher.skip(16);
            mesher.endRow();
        }
        mesher.finish();

        assertEquals(2, mesher.quads);
    }

    private static final class CountingMesher extends ScanMesher2D {
        int quads;

        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            this.quads++;
        }
    }
}
