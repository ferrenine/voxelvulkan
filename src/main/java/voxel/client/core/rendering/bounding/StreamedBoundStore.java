package voxel.client.core.rendering.bounding;

import org.lwjgl.system.MemoryUtil;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.core.vk.VkBuffer;

import java.util.Arrays;

/**
 * Simple streamed bound store: the caller resets the list and puts every
 * visible section, and the list is uploaded once when it changed. Useful for
 * sources that already produce a per-frame list (e.g. a FREX/column path)
 * instead of maintaining an add/remove heap. Vulkan port of Voxy's
 * {@code StreamedBoundStore}.
 */
public final class StreamedBoundStore implements IBoundStore {
    private static final int INIT_MAX_CHUNK_COUNT = 1 << 12;

    private VkBuffer chunkPosBuffer = new VkBuffer(INIT_MAX_CHUNK_COUNT * 8L);
    // Count is in ints, not longs: each packed position is two ints.
    private int count;
    private boolean didChange = true;
    private int[] visibleSections = new int[INIT_MAX_CHUNK_COUNT * 2];

    @Override
    public void preRender(Viewport<?> viewport) {
        if (this.count == 0 || !this.didChange) {
            return;
        }
        long required = this.count * 4L;
        if (required > this.chunkPosBuffer.size()) {
            this.chunkPosBuffer.free();
            this.chunkPosBuffer = new VkBuffer(Math.max(required, (long) Math.ceil(this.count * 1.25) * 4L));
        }

        long address = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 0, required);
        for (int i = 0; i < this.count; i++) {
            MemoryUtil.memPutInt(address + i * 4L, this.visibleSections[i]);
        }
        UploadStream.INSTANCE.commit();
        this.didChange = false;
    }

    public void put(long pos) {
        pos = IBoundStore.transformBeforeStore(pos);
        int idx = this.count;
        if (idx + 2 > this.visibleSections.length) {
            this.visibleSections = Arrays.copyOf(this.visibleSections, (int) (this.visibleSections.length * 1.25) + 2);
        }
        this.visibleSections[idx] = (int) (pos & 0xFFFFFFFFL);
        this.visibleSections[idx + 1] = (int) ((pos >>> 32) & 0xFFFFFFFFL);
        this.count = idx + 2;
        this.didChange = true;
    }

    public void reset() {
        this.count = 0;
        this.didChange = true;
    }

    @Override
    public VkBuffer getBuffer() {
        return this.chunkPosBuffer;
    }

    @Override
    public int getCount() {
        return this.count >> 1;
    }

    @Override
    public void free() {
        this.chunkPosBuffer.free();
    }
}
