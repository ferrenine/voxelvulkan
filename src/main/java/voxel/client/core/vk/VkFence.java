package voxel.client.core.vk;

import voxel.client.vk.VkVoxel;
import voxel.common.util.TrackedObject;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;

import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkGetFenceStatus;

/**
 * Vulkan replacement for the GL sync object.
 *
 * <p>Completion is frame-based: VulkanMod waits each frame's fence a fixed
 * number of frames later, so any command recorded during frame {@code N} is
 * complete once the frame counter has advanced by {@code framesNum}. This
 * matches the polling usage in the upload/download streams ("has the work
 * enqueued in earlier frames finished?").</p>
 *
 * <p>If the frame hook is not active yet (frame epoch still zero), the fence
 * falls back to a one-shot submission fence.</p>
 */
public class VkFence extends TrackedObject {

    private static final long framesToWait = 2;

    private final long epochAtCreation;
    private final CommandPool.CommandBuffer fallbackCommandBuffer;

    private boolean signaled;

    public VkFence() {
        long epoch = VkVoxel.frameEpoch();
        if (epoch > 0) {
            this.epochAtCreation = epoch;
            this.fallbackCommandBuffer = null;
        } else {
            this.epochAtCreation = 0;
            var queue = DeviceManager.getGraphicsQueue();
            this.fallbackCommandBuffer = queue.beginCommands();
            queue.submitCommands(this.fallbackCommandBuffer);
        }
    }

    public boolean signaled() {
        if (this.signaled) {
            return true;
        }
        if (this.fallbackCommandBuffer != null) {
            int res = vkGetFenceStatus(VkVoxel.device(), this.fallbackCommandBuffer.getFence());
            if (res == VK_SUCCESS) {
                this.signaled = true;
                this.fallbackCommandBuffer.reset();
            } else if (res != VK_NOT_READY) {
                throw new IllegalStateException("Polling fence failed: " + res);
            }
            return this.signaled;
        }
        long framesToWait = Math.max(VkFence.framesToWait, VkVoxel.framesNum());
        if (VkVoxel.frameEpoch() - this.epochAtCreation >= framesToWait) {
            this.signaled = true;
        }
        return this.signaled;
    }

    @Override
    public void free() {
        this.free0();
        this.signaled = true;
    }
}
