package voxel.client.vk;

import voxel.common.util.TrackedObject;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A device-local GPU buffer (SSBO / indirect / transfer target), the Vulkan
 * replacement for the old GL wrapper's vertex-array-storage buffers.
 *
 * <p>Zeroing and filling is recorded either into the current frame's main command
 * buffer (when called while the frame is being recorded) or into a one-shot
 * submission (during initialisation).</p>
 */
public class VkVoxelBuffer extends TrackedObject {

    public static final int DEFAULT_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
            | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
            | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private final String name;
    private final Buffer buffer;
    private final long size;

    private static int COUNT;
    private static long TOTAL_SIZE;

    public VkVoxelBuffer(String name, long size) {
        this(name, size, DEFAULT_USAGE);
    }

    public VkVoxelBuffer(String name, long size, int vkUsage) {
        this.name = name;
        this.size = size;
        this.buffer = new Buffer(name, vkUsage, MemoryTypes.GPU_MEM);
        this.buffer.createBuffer(size);
        COUNT++;
        TOTAL_SIZE += size;
    }

    public String name() {
        return this.name;
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

    public static int getCount() {
        return COUNT;
    }

    public static long getTotalSize() {
        return TOTAL_SIZE;
    }

    // ------------------------------------------------------------------
    // CPU -> GPU upload
    // ------------------------------------------------------------------

    /** Uploads the remainder of the given buffer through VulkanMod's staging path. */
    public void upload(ByteBuffer data) {
        upload(data, data.remaining());
    }

    public void upload(ByteBuffer data, int bytes) {
        assertNotFreed();
        this.buffer.copyBuffer(data, bytes);
    }

    /** Uploads to a specific offset inside this buffer via VulkanMod's staging path. */
    public void uploadAt(long dstOffset, ByteBuffer data) {
        uploadAt(dstOffset, data, data.remaining());
    }

    public void uploadAt(long dstOffset, ByteBuffer data, int bytes) {
        assertNotFreed();
        this.buffer.copyBuffer(data, bytes, (int) dstOffset);
    }

    /**
     * Reads the given range back into {@code dst}. Blocking: waits for the GPU to
     * become idle first (debug / readback paths only).
     */
    public void readBack(long srcOffset, ByteBuffer dst, int bytes) {
        assertNotFreed();
        net.vulkanmod.vulkan.Vulkan.waitIdle();
        VkVoxelMappedBuffer staging = new VkVoxelMappedBuffer("readback", bytes);
        try {
            VkVoxel.oneShot(cb -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                    region.get(0).srcOffset(srcOffset).dstOffset(0).size(bytes);
                    vkCmdCopyBuffer(cb, this.buffer.getId(), staging.handle(), region);
                }
            });
            MemoryUtil.memCopy(staging.addr(), MemoryUtil.memAddress(dst), bytes);
        } finally {
            staging.free();
        }
    }

    // ------------------------------------------------------------------
    // Fills / zeroing
    // ------------------------------------------------------------------

    public void zero() {
        this.fillRange(0, this.size, 0);
    }

    public void zeroRange(long offset, long length) {
        this.fillRange(offset, length, 0);
    }

    public void fill(int value) {
        this.fillRange(0, this.size, value);
    }

    public void fillRange(long offset, long length, int value) {
        assertNotFreed();
        long len = length & ~3L; // vkCmdFillBuffer requires a multiple of 4
        if (len <= 0) {
            return;
        }
        VkCommandBuffer cb = VkVoxel.commandBufferOrNull();
        if (cb != null) {
            VkVoxel.ensureOutsideRenderPass();
            vkCmdFillBuffer(cb, this.buffer.getId(), offset, len, value);
        } else {
            VkVoxel.oneShot(c -> vkCmdFillBuffer(c, this.buffer.getId(), offset, len, value));
        }
    }

    /** Copies a byte range from this buffer into {@code dst}. */
    public void copyTo(VkVoxelBuffer dst, long srcOffset, long dstOffset, long bytes) {
        assertNotFreed();
        dst.assertNotFreed();
        if (bytes <= 0) {
            return;
        }
        VkCommandBuffer cb = VkVoxel.commandBufferOrNull();
        if (cb != null) {
            VkVoxel.ensureOutsideRenderPass();
            recordCopy(cb, this.buffer.getId(), dst.buffer.getId(), srcOffset, dstOffset, bytes);
        } else {
            VkVoxel.oneShot(c -> recordCopy(c, this.buffer.getId(), dst.buffer.getId(), srcOffset, dstOffset, bytes));
        }
    }

    private static void recordCopy(VkCommandBuffer cb, long src, long dst, long srcOffset, long dstOffset, long bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(srcOffset).dstOffset(dstOffset).size(bytes);
            vkCmdCopyBuffer(cb, src, dst, region);
        }
    }

    @Override
    public void free() {
        this.free0();
        COUNT--;
        TOTAL_SIZE -= this.size;
        this.buffer.scheduleFree();
    }

    @Override
    public String toString() {
        return "VkVoxelBuffer[" + this.name + ", " + this.size + " bytes]";
    }
}
