package voxel.client.core.vk;

import voxel.client.vk.VkVoxelMappedBuffer;
import voxel.common.util.TrackedObject;

/**
 * Vulkan replacement for the original persistently-mapped GL buffer
 * ({@code glMapNamedBufferRange} with {@code GL_MAP_PERSISTENT_BIT}).
 *
 * <p>Backed by a host-visible, host-coherent {@link VkVoxelMappedBuffer}; the CPU
 * writes through {@link #addr()} while the GPU reads the same memory.</p>
 */
public class VkPersistentMappedBuffer extends TrackedObject {

    /** Vulkan buffer handle. */
    public final long id;

    private final VkVoxelMappedBuffer buffer;
    private final long size;

    public VkPersistentMappedBuffer(long size, int flags) {
        this.buffer = new VkVoxelMappedBuffer("voxel-mapped", size);
        this.size = size;
        this.id = this.buffer.handle();
    }

    @Override
    public void free() {
        this.free0();
        this.buffer.free();
    }

    public long size() {
        return this.size;
    }

    /** Native address of the mapped memory. */
    public long addr() {
        return this.buffer.addr();
    }

    public long handle() {
        return this.id;
    }

    public void zero() {
        this.buffer.zero();
    }

    public VkPersistentMappedBuffer name(String name) {
        return this;
    }
}
