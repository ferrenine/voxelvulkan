package voxel.client.core.vk;

import voxel.client.vk.VkVoxelBuffer;
import voxel.common.util.TrackedObject;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB;

/**
 * Vulkan-backed replacement for the original OpenGL buffer wrapper
 * ({@code glNamedBufferStorage} / {@code glClearNamedBuffer[Sub]Data}).
 *
 * <p>The class name is kept so that the renderer packages keep compiling while
 * the port moves them over to Vulkan; the data effectively lives in a
 * {@link VkVoxelBuffer} (device-local storage buffer).</p>
 *
 * <p>Unlike GL, Vulkan cannot "sparse-allocate" on demand. Buffers created with
 * the sparse flag are allocated their full size up-front and skip the initial
 * zeroing, matching the observable semantics of an unbound sparse buffer.</p>
 */
public class VkBuffer extends TrackedObject {

    /** Underlying Vulkan buffer. */
    public final VkVoxelBuffer buffer;
    /** VkBuffer handle. */
    public final long id;

    private final long size;
    private final int flags;

    private static int COUNT;
    private static long TOTAL_SIZE;

    public VkBuffer(long size) {
        this(size, 0);
    }

    public VkBuffer(long size, boolean zero) {
        this(size, 0, zero);
    }

    public VkBuffer(long size, int flags) {
        this(size, flags, true);
    }

    public VkBuffer(long size, int flags, boolean zero) {
        this.flags = flags;
        this.size = size;
        this.buffer = new VkVoxelBuffer("voxel", size);
        this.id = this.buffer.handle();
        if (!this.isSparse() && zero) {
            this.buffer.zero();
        }

        COUNT++;
        TOTAL_SIZE += size;
    }

    @Override
    public void free() {
        this.free0();
        this.buffer.free();

        COUNT--;
        TOTAL_SIZE -= this.size;
    }

    public boolean isSparse() {
        return (this.flags & GL_SPARSE_STORAGE_BIT_ARB) != 0;
    }

    public long size() {
        return this.size;
    }

    public VkBuffer zero() {
        this.buffer.zero();
        return this;
    }

    public VkBuffer zeroRange(long offset, long size) {
        this.buffer.zeroRange(offset, size);
        return this;
    }

    public VkBuffer fill(int data) {
        this.buffer.fill(data);
        return this;
    }

    public VkBuffer fillRange(long offset, long size, int data) {
        this.buffer.fillRange(offset, size, data);
        return this;
    }

    public void copyTo(VkBuffer dst, long srcOffset, long dstOffset, long size) {
        this.buffer.copyTo(dst.buffer, srcOffset, dstOffset, size);
    }

    // ------------------------------------------------------------------
    // Vulkan-specific helpers
    // ------------------------------------------------------------------

    public long handle() {
        return this.id;
    }

    /** Uploads bytes to a specific buffer offset (staging path). */
    public void upload(long offset, ByteBuffer data) {
        this.buffer.uploadAt(offset, data);
    }

    /** Blocking readback used by debug/readback paths. */
    public void readBack(long offset, ByteBuffer dst, int bytes) {
        this.buffer.readBack(offset, dst, bytes);
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getTotalSize() {
        return TOTAL_SIZE;
    }

    public VkBuffer name(String name) {
        return this;
    }
}
