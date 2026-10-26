package voxel.client.vk;

import voxel.common.util.TrackedObject;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A host-visible, persistently mapped buffer. This is the Vulkan replacement for
 * {@code VkPersistentMappedBuffer}: CPU code writes directly through
 * {@link #addr()}, the GPU reads the same memory.
 *
 * <p>VulkanMod's HOST_MEM memory type is host coherent, so no explicit flush is
 * required; {@link #flush()} is kept for API parity and does nothing.</p>
 */
public class VkVoxelMappedBuffer extends TrackedObject {

    public static final int DEFAULT_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private final String name;
    private final Buffer buffer;
    private final long size;

    public VkVoxelMappedBuffer(String name, long size) {
        this(name, size, DEFAULT_USAGE);
    }

    public VkVoxelMappedBuffer(String name, long size, int vkUsage) {
        this.name = name;
        this.size = size;
        this.buffer = new Buffer(name, vkUsage, MemoryTypes.HOST_MEM);
        this.buffer.createBuffer(size);
    }

    public String name() {
        return this.name;
    }

    /** Native address of the mapped memory; write CPU data here directly. */
    public long addr() {
        return this.buffer.getDataPtr();
    }

    public long handle() {
        return this.buffer.getId();
    }

    public long size() {
        return this.size;
    }

    public Buffer raw() {
        return this.buffer;
    }

    public void zero() {
        MemoryUtil.memSet(this.addr(), 0, this.size);
    }

    public void zeroRange(long offset, long length) {
        MemoryUtil.memSet(this.addr() + offset, 0, length);
    }

    /** No-op: HOST_MEM is host coherent. */
    public void flush() {
    }

    /** No-op: HOST_MEM is host coherent. */
    public void flush(long offset, long length) {
    }

    @Override
    public void free() {
        this.free0();
        this.buffer.scheduleFree();
    }

    @Override
    public String toString() {
        return "VkVoxelMappedBuffer[" + this.name + ", " + this.size + " bytes]";
    }
}
