package voxel.client.core.rendering.bounding;

import net.minecraft.core.SectionPos;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.vk.VkBuffer;
import voxel.commonimpl.VoxelCommon;

/**
 * Supplies the chunk/section bound list used by {@link BoundRenderer}.
 *
 * <p>Voxy keeps the storage policy behind this interface: a list of real
 * loaded sections ({@link ChunkBoundStore}), a streamed list, or a
 * distance-derived exact list. The renderer only needs a buffer of packed
 * section positions and a count.</p>
 */
public interface IBoundStore {
    VkBuffer getBuffer();

    int getCount();

    default void preRender(Viewport<?> viewport) {
    }

    default void postRender(Viewport<?> viewport) {
    }

    void free();

    static long transformBeforeStore(long pos) {
        // Keep the same Mine-in-Abyss remap as the rest of the renderer.
        if (VoxelCommon.IS_MINE_IN_ABYSS) {
            int x = SectionPos.x(pos);
            int y = SectionPos.y(pos);
            int sector = (x + 512) >> 10;
            x -= sector << 10;
            y += 16 + (256 - 32 - sector * 30);
            return SectionPos.asLong(x, y, SectionPos.z(pos));
        }
        return pos;
    }
}
