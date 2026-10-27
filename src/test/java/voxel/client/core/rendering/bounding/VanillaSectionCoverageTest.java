package voxel.client.core.rendering.bounding;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaSectionCoverageTest {

    @Test
    void aLod0SectionIsCoveredOnlyWhenAllEightChildrenAreCompiled() {
        VanillaSectionCoverage coverage = new VanillaSectionCoverage();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    coverage.markCompiled(SectionPos.asLong(2 + dx, 4 + dy, 6 + dz), true);
                }
            }
        }

        assertTrue(coverage.coversLod0Section(1, 2, 3));

        coverage.markCompiled(SectionPos.asLong(3, 5, 7), false);
        assertFalse(coverage.coversLod0Section(1, 2, 3));
    }

    @Test
    void revisionChangesOnlyWhenMembershipChanges() {
        VanillaSectionCoverage coverage = new VanillaSectionCoverage();
        long start = coverage.revision();

        coverage.markCompiled(SectionPos.asLong(0, 0, 0), true);
        long afterAdd = coverage.revision();
        assertTrue(afterAdd > start);

        coverage.markCompiled(SectionPos.asLong(0, 0, 0), true);
        assertTrue(coverage.revision() == afterAdd);

        coverage.markCompiled(SectionPos.asLong(0, 0, 0), false);
        assertTrue(coverage.revision() > afterAdd);
        assertFalse(coverage.coversLod0Section(0, 0, 0));
    }
}
