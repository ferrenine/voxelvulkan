package voxel.client.core.rendering.util;


import voxel.client.core.vk.VkFence;
import voxel.client.core.vk.VkPersistentMappedBuffer;
import voxel.common.Logger;
import voxel.common.util.AllocationArena;
import net.vulkanmod.vulkan.Vulkan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

//Special download stream which allows access to the download buffer directly
public class RawDownloadStream {
    //NOTE: after the callback returns the pointer is no longer valid for client use
    public interface IDownloadCompletedCallback {
        void accept(long ptr);
    }

    private record DownloadFragment(int allocation, IDownloadCompletedCallback callback) {
    }

    private record DownloadFrame(VkFence fence, DownloadFragment[] fragments) {
    }

    private final VkPersistentMappedBuffer downloadBuffer;
    private final AllocationArena allocationArena = new AllocationArena();
    private final ArrayList<DownloadFragment> frameFragments = new ArrayList<>();
    private final Deque<DownloadFrame> frames = new ArrayDeque<>();

    public RawDownloadStream(int size) {
        this.downloadBuffer = new VkPersistentMappedBuffer(size, 0).name("RawDownloadStream");
        this.allocationArena.setLimit(size);
    }

    public int download(int size, IDownloadCompletedCallback callback) {
        int allocation = (int) this.allocationArena.alloc(size);
        if (allocation == AllocationArena.SIZE_LIMIT) {
            Logger.warn("Raw download stream full, preemptively committing, this could cause bad things to happen");
            //Hit the download limit, attempt to free
            voxel.client.vk.VkVoxel.waitIdle();
            this.tick();
            allocation = (int) this.allocationArena.alloc(size);
            if (allocation == AllocationArena.SIZE_LIMIT) {
                throw new IllegalStateException("Unable free enough memory for raw download stream");
            }
        }
        this.frameFragments.add(new DownloadFragment(allocation, callback));
        return allocation;
    }

    //Creates a new "frame" for previously allocated downloads and enqueues a fence
    // also invalidates all previous download pointers from this instance
    public void submit() {
        if (!this.frameFragments.isEmpty()) {
            var fragments = this.frameFragments.toArray(new DownloadFragment[0]);
            this.frameFragments.clear();
            this.frames.add(new DownloadFrame(new VkFence(), fragments));
        }
    }

    public void tick() {
        this.submit();

        while (!this.frames.isEmpty()) {
            //If the first element is not signaled, none of the others will be signaled so break
            if (!this.frames.peek().fence.signaled()) {
                break;
            }
            var frame = this.frames.poll();
            for (var fragment : frame.fragments) {
                long addr = this.downloadBuffer.addr() + fragment.allocation;
                fragment.callback.accept(addr);
                this.allocationArena.free(fragment.allocation);
            }
            frame.fence.free();
        }
    }

    public long getBufferId() {
        return this.downloadBuffer.id;
    }

    public long baseAddress() {
        return this.downloadBuffer.addr();
    }

    public void free() {
        voxel.client.vk.VkVoxel.waitIdle();
        this.tick();
        voxel.client.vk.VkVoxel.waitIdle();
        this.tick();
        // Frames recorded after the last submission cannot be waited on (this
        // stream is being destroyed). Drop their callbacks but release their
        // staging memory so a failed construction/shutdown cannot leak it.
        int discarded = 0;
        while (!this.frames.isEmpty()) {
            var frame = this.frames.poll();
            for (var fragment : frame.fragments) {
                this.allocationArena.free(fragment.allocation);
            }
            frame.fence.free();
            discarded++;
        }
        if (discarded != 0) {
            Logger.warn("Raw download stream discarded " + discarded + " unsubmitted frame(s) during shutdown");
        }
        this.downloadBuffer.free();
    }
}
