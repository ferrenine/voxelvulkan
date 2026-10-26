package voxel.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkFence;
import voxel.client.core.vk.VkPersistentMappedBuffer;
import voxel.client.vk.VkVoxel;
import voxel.common.Logger;
import voxel.common.util.AllocationArena;
import voxel.common.util.MemoryBuffer;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayDeque;
import java.util.Deque;

import static voxel.common.util.AllocationArena.SIZE_LIMIT;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Staging-buffer upload stream. CPU writes go into a persistently mapped
 * host-visible buffer; {@link #commit()} records {@code vkCmdCopyBuffer}
 * transfers into the frame's command buffer (or a one-shot submission when
 * called outside of frame recording).
 */
public class UploadStream {
    private final AllocationArena allocationArena = new AllocationArena();
    private final VkPersistentMappedBuffer uploadBuffer;

    private final Deque<UploadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    private final Deque<UploadData> uploadList = new ArrayDeque<>();

    public UploadStream(long size) {
        this.uploadBuffer = new VkPersistentMappedBuffer(size, 0).name("UploadStream");
        this.allocationArena.setLimit(size);
    }

    private long caddr = -1;
    private long offset = 0;

    public void upload(VkBuffer buffer, long destOffset, MemoryBuffer data) {//Note: does not free data, nor does it commit
        data.cpyTo(this.upload(buffer, destOffset, data.size));
    }

    public long uploadTo(VkBuffer buffer) {
        return this.upload(buffer, 0, buffer.size());
    }

    public long upload(VkBuffer buffer, long destOffset, long size) {
        long addr = this.rawUploadAddress((int) size);

        this.uploadList.add(new UploadData(buffer, addr, destOffset, size));

        return this.uploadBuffer.addr() + addr;
    }

    public long rawUpload(int size) {
        return this.uploadBuffer.addr() + this.rawUploadAddress(size);
    }

    public long rawUploadAddress(int size) {
        if (size < 0) {
            throw new IllegalStateException("Negative size");
        }

        if (size > this.uploadBuffer.size()) {
            throw new IllegalArgumentException();
        }
        // Raw upload addresses are used directly as VkDescriptorBufferInfo.offset
        // (ssboRaw in NodeCleaner / AsyncNodeManager), so they must satisfy the
        // device's minStorageBufferOffsetAlignment: 16 B on NVIDIA, 64 B on Intel.
        // Rounding every allocation keeps the arena's block boundaries aligned by
        // induction, including the tail-growth and free-gap reuse paths.
        final int alignment = allocationAlignment();
        long alignedSize = ((long) size + (alignment - 1)) & ~(long) (alignment - 1);
        if (alignedSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Upload allocation too large: " + size);
        }
        size = (int) alignedSize;

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            // (Persistently mapped host memory is coherent; no explicit flush needed.)
            this.caddr = this.allocationArena.alloc((int) size);//TODO: replace with allocFromLargest
            if (this.caddr == SIZE_LIMIT) {
                //Note! we dont commit here, we only try to flush existing memory copies, we dont commit
                // since commit is an explicit op saying we are done any to push upload everything
                //We dont commit since we dont want to invalidate existing upload pointers
                Logger.error("Upload stream full, preemptively committing, this could cause bad things to happen");
                // One real drain is enough: waitIdle retires every batch whose
                // frame was actually submitted, and tick() frees all of them.
                // Retrying in a loop cannot make progress inside a single
                // recording (nothing is submitted while we spin).
                VkVoxel.waitIdle();
                this.tick(false);
                this.caddr = this.allocationArena.alloc((int) size);
                if (this.caddr == SIZE_LIMIT) {
                    throw new IllegalStateException("Could not allocate " + size
                            + " bytes in the upload stream even after waiting for the GPU; the"
                            + " frame is using more staging memory than the " + this.uploadBuffer.size()
                            + "-byte buffer");
                }
            }
            this.thisFrameAllocations.add(this.caddr);
            this.offset = size;
            addr = this.caddr;
        } else {//Could expand the allocation so just update it
            addr = this.caddr + this.offset;
            this.offset += size;
        }

        if (this.caddr + size > this.uploadBuffer.size()) {
            throw new IllegalStateException();
        }
        if ((addr & (alignment - 1)) != 0) {
            throw new IllegalStateException("Upload allocation " + addr + " is not " + alignment + "-byte aligned");
        }

        return addr;
    }

    /** Descriptor buffer-offset alignment of the active device. */
    private static int allocationAlignment() {
        var props = DeviceManager.deviceProperties;
        if (props == null) {
            return 16;
        }
        return (int) Math.max(16L, props.limits().minStorageBufferOffsetAlignment());
    }

    public void commit() {
        if (this.uploadList.isEmpty()) {
            this.caddr = -1;
            this.offset = 0;
            return;
        }

        var cb = VkVoxel.commandBufferOrNull();
        if (cb != null) {
            VkVoxel.ensureOutsideRenderPass();
            this.recordCopies(cb);
        } else {
            VkVoxel.oneShot(this::recordCopies);
        }

        this.uploadList.clear();

        this.caddr = -1;
        this.offset = 0;
    }

    private void recordCopies(VkCommandBuffer cb) {
        // WAR: earlier shader/draw/transfer reads of the destination ranges must
        // complete before the copies overwrite them. Without this, a frame-N copy
        // can race a frame-N-1 draw (or an earlier command in this buffer).
        VkVoxel.memoryBarrier(cb,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                        | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT
                        | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_UNIFORM_READ_BIT
                        | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT
                        | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT
                        | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            for (var entry : this.uploadList) {
                region.get(0).srcOffset(entry.uploadOffset).dstOffset(entry.targetOffset).size(entry.size);
                vkCmdCopyBuffer(cb, this.uploadBuffer.id, entry.target.id, region);
            }
        }
        // Make the copies visible to every consumer stage that can read an
        // uploaded buffer: vertex/fragment/compute, vertex input (index/vertex),
        // indirect draws and later transfers.
        VkVoxel.memoryBarrier(cb,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                        | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_UNIFORM_READ_BIT
                        | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT
                        | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
    }

    public void tick() {
        this.tick(true);
    }

    private void tick(boolean commit) {
        if (commit) {
            this.commit();
        }

        if (!this.thisFrameAllocations.isEmpty()) {
            this.frames.add(new UploadFrame(new VkFence(), new LongArrayList(this.thisFrameAllocations)));
            this.thisFrameAllocations.clear();
        }

        while (!this.frames.isEmpty()) {
            //Since the ordering of frames is the ordering of the gl commands if we encounter an unsignaled fence
            // all the other fences should also be unsignaled
            if (!this.frames.peek().fence.signaled()) {
                break;
            }
            //Release all the allocations from the frame
            var frame = this.frames.pop();
            frame.allocations.forEach(this.allocationArena::free);
            frame.fence.free();
        }
    }

    public long getBaseAddress() {
        return this.uploadBuffer.addr();
    }

    public long getRawBufferId() {
        return this.uploadBuffer.id;
    }

    private record UploadFrame(VkFence fence, LongArrayList allocations) {
    }

    private record UploadData(VkBuffer target, long uploadOffset, long targetOffset, long size) {
    }

    //A upload instance instead of passing one around by reference
    // MUST ONLY BE USED ON THE RENDER THREAD
    public static final UploadStream INSTANCE = new UploadStream(1 << 26);//64 mb upload buffer

}
