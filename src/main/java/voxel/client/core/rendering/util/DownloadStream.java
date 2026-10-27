package voxel.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkFence;
import voxel.client.core.vk.VkPersistentMappedBuffer;
import voxel.client.vk.VkVoxel;
import voxel.common.Logger;
import voxel.common.util.AllocationArena;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.PerformanceCounters;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.function.Consumer;

import static voxel.common.util.AllocationArena.SIZE_LIMIT;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Download stream: device buffers are copied into a persistently mapped
 * host-visible buffer; callbacks fire once the frame that recorded the copy is
 * known to have completed (frame-based fences).
 */
public class DownloadStream {
    public interface DownloadResultConsumer {
        void consume(long ptr, long size);
    }

    private final AllocationArena allocationArena = new AllocationArena();
    private final VkPersistentMappedBuffer downloadBuffer;

    private final Deque<DownloadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    private final Deque<DownloadData> downloadList = new ArrayDeque<>();
    private final ArrayList<DownloadData> thisFrameDownloadList = new ArrayList<>();

    public DownloadStream(long size) {
        this.downloadBuffer = new VkPersistentMappedBuffer(size, 0).name("DownloadStream");
        this.allocationArena.setLimit(size);
    }

    private long caddr = -1;
    private long offset = 0;

    //Pulls the entire buffer from the gpu
    public void download(VkBuffer buffer, DownloadResultConsumer resultConsumer) {
        this.download(buffer, 0, buffer.size(), resultConsumer);
    }

    public void download(VkBuffer buffer, Consumer<MemoryBuffer> resultConsumer) {
        this.download(buffer, 0, buffer.size(), resultConsumer);
    }

    public void download(VkBuffer buffer, long downloadOffset, long size, Consumer<MemoryBuffer> consumer) {
        this.download(buffer, downloadOffset, size, (ptr, size2) -> {
            consumer.accept(MemoryBuffer.createUntrackedUnfreeableRawFrom(ptr, size));
        });
    }

    public void download(VkBuffer buffer, long downloadOffset, long size, DownloadResultConsumer resultConsumer) {
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException();
        }
        if (size <= 0) {
            throw new IllegalArgumentException();
        }
        if (downloadOffset + size > buffer.size()) {
            throw new IllegalArgumentException();
        }
        PerformanceCounters.recordDownload(size);

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            this.caddr = this.allocationArena.alloc((int) size);//TODO: replace with allocFromLargest
            if (this.caddr == SIZE_LIMIT) {
                PerformanceCounters.recordDownloadOverflow();
                Logger.warn("Download stream full, preemptively committing, this could cause bad things to happen");
                this.commit();
                // One real drain is enough: all previously submitted download
                // batches are complete after waitIdle. The batch recorded in the
                // frame that is still recording must not be retired here.
                VkVoxel.waitIdle();
                this.tick();
                this.caddr = this.allocationArena.alloc((int) size);
                if (this.caddr == SIZE_LIMIT) {
                    throw new IllegalStateException("Could not allocate " + size
                            + " bytes in the download stream even after waiting for the GPU; the"
                            + " frame is using more readback memory than the " + this.downloadBuffer.size()
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

        if (this.caddr + size > this.downloadBuffer.size()) {
            throw new IllegalStateException();
        }

        this.downloadList.add(new DownloadData(buffer, addr, downloadOffset, size, resultConsumer));

        //TODO: maybe not auto-commit
        this.commit();
    }


    public void commit() {
        if (this.downloadList.isEmpty()) {
            return;
        }
        PerformanceCounters.recordDownloadCommit(this.downloadList.size());

        var cb = VkVoxel.commandBufferOrNull();
        if (cb != null) {
            VkVoxel.ensureOutsideRenderPass();
            this.recordCopies(cb);
        } else {
            VkVoxel.oneShot(this::recordCopies);
        }

        this.thisFrameDownloadList.addAll(this.downloadList);
        this.downloadList.clear();

        this.caddr = -1;
        this.offset = 0;
    }

    private void recordCopies(VkCommandBuffer cb) {
        // Make earlier GPU writes to the source buffers visible to the copies.
        VkVoxel.memoryBarrier(cb,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_MEMORY_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            for (var entry : this.downloadList) {
                region.get(0).srcOffset(entry.targetOffset).dstOffset(entry.downloadStreamOffset).size(entry.size);
                vkCmdCopyBuffer(cb, entry.target.id, this.downloadBuffer.id, region);
            }
        }
        // The source buffers are often cleared/rewritten right after the download
        // (e.g. the traversal request counter). Order the transfer reads before
        // any later transfer/shader write, otherwise the fill can overtake the
        // copy and the host reads stale data.
        VkVoxel.memoryBarrier(cb,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                        | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT
                        | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        // Host reads happen later; the destination is host-coherent so no further
        // barrier is required.
    }

    public void tick() {
        this.commit();
        if (!this.thisFrameAllocations.isEmpty()) {
            this.frames.add(new DownloadFrame(new VkFence(), new LongArrayList(this.thisFrameAllocations), new ArrayList<>(this.thisFrameDownloadList)));
            this.thisFrameAllocations.clear();
            this.thisFrameDownloadList.clear();
        }

        while (!this.frames.isEmpty()) {
            //Since the ordering of frames is the ordering of the gl commands if we encounter an unsignaled fence
            // all the other fences should also be unsignaled
            if (!this.frames.peek().fence.signaled()) {
                break;
            }

            //Release all the allocations from the frame
            var frame = this.frames.pop();

            //Apply all the callbacks
            for (var data : frame.data) {
                data.resultConsumer.consume(this.downloadBuffer.addr() + data.downloadStreamOffset, data.size);
            }

            frame.allocations.forEach(this.allocationArena::free);
            frame.fence.free();
        }
    }

    //Synchonize force flushes everything
    public void waitDiscard() {
        VkVoxel.waitIdle();
        while (!this.frames.isEmpty()) {
            var frame = this.frames.pop();
            frame.allocations.forEach(this.allocationArena::free);
            frame.fence.free();
        }
    }

    public void flushWaitClear() {
        VkVoxel.waitIdle();
        this.tick();
        VkVoxel.waitIdle();
        this.tick();
        if (!this.frames.isEmpty()) {
            throw new IllegalStateException();
        }
    }

    private record DownloadFrame(VkFence fence, LongArrayList allocations, ArrayList<DownloadData> data) {
    }

    private record DownloadData(VkBuffer target, long downloadStreamOffset, long targetOffset, long size,
                                DownloadResultConsumer resultConsumer) {
    }


    // Global download stream
    public static final DownloadStream INSTANCE = new DownloadStream(1 << 25);//32 mb download buffer
}
