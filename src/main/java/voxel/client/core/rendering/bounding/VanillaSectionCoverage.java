package voxel.client.core.rendering.bounding;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;

/**
 * Tracks which vanilla 16x16x16 sections have a compiled render mesh (including
 * intentionally empty sections). A LOD0 section is 32x32x32, so it is fully
 * covered only when all eight of its vanilla children are compiled. In that
 * case the vanilla chunks already render the same blocks and the LOD0 geometry
 * is redundant.
 *
 * <p>This set is only touched from the render thread. {@link #revision()} lets
 * the traversal rebuild its small GPU lookup mask only when the set changes.</p>
 */
public final class VanillaSectionCoverage {
    private final LongOpenHashSet compiled = new LongOpenHashSet();
    private long revision;

    public void markCompiled(long sectionPos, boolean isCompiled) {
        boolean changed = isCompiled
                ? this.compiled.add(sectionPos)
                : this.compiled.remove(sectionPos);
        if (changed) {
            this.revision++;
        }
    }

    public boolean coversLod0Section(int lodX, int lodY, int lodZ) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    long child = SectionPos.asLong(lodX * 2 + dx, lodY * 2 + dy, lodZ * 2 + dz);
                    if (!this.compiled.contains(IBoundStore.transformBeforeStore(child))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public long revision() {
        return this.revision;
    }

    public int size() {
        return this.compiled.size();
    }

    public void clear() {
        if (!this.compiled.isEmpty()) {
            this.compiled.clear();
            this.revision++;
        }
    }
}
