package voxel.client.vk;

import voxel.common.Logger;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Central glue between Voxel and VulkanMod's Vulkan renderer.
 *
 * <p>LING records all of its GPU work into VulkanMod's main frame command buffer
 * (obtained via {@link #commandBufferOrNull()}). Work that must happen outside of
 * frame recording (resource initialisation, one-off fills/uploads) is submitted
 * through {@link #oneShot(Consumer)} on the graphics queue and waited on.</p>
 */
public final class VkVoxel {

    /** uint64 max, used for vkWaitForFences. */
    public static final long UINT64_MAX = ~0L;

    /**
     * Upper bound of VulkanMod's swapchain frame queue (it can grow from 2 to 5
     * when the swapchain is rebuilt). Descriptor rings are allocated for this
     * many slots so a resize cannot move currentFrame past the pool.
     */
    public static final int MAX_FRAME_SLOTS = 5;

    private static volatile boolean drawIndirectCountSupported;
    private static volatile boolean hostQueryResetSupported;

    /** Recorded by the DeviceManager mixin before the logical device is created. */
    public static void setDrawIndirectCountSupported(boolean supported) {
        drawIndirectCountSupported = supported;
    }

    public static boolean isDrawIndirectCountSupported() {
        return drawIndirectCountSupported;
    }

    public static void setHostQueryResetSupported(boolean supported) {
        hostQueryResetSupported = supported;
    }

    public static boolean isHostQueryResetSupported() {
        return hostQueryResetSupported;
    }

    /**
     * Monotonic frame counter, incremented by the renderer hook on every frame
     * begin. Used to implement frame-based completion polling (VulkanMod waits
     * each frame's fence {@code framesNum} frames later, so any command recorded
     * during frame {@code N} is guaranteed complete once the counter has moved
     * at least {@code framesNum} frames past {@code N}).
     */
    private static long frameEpoch;
    /**
     * Highest frame epoch whose command buffer has actually been submitted to
     * the graphics queue (updated from Renderer.submitFrame / flushCmds).
     */
    private static long submittedEpoch;
    /**
     * Highest epoch known to be complete on the GPU. Only advanced by a real
     * {@code vkDeviceWaitIdle}; the frame counter is never jumped ahead to fake
     * completion for work that is still being recorded.
     */
    private static volatile long completedEpoch;

    private VkVoxel() {
    }

    /** Called from the frame hook (Renderer.beginFrame). */
    public static void onFrameBegin() {
        frameEpoch++;
    }

    /** Called after VulkanMod submitted the current frame's command buffer. */
    public static void onFrameSubmitted() {
        if (frameEpoch > submittedEpoch) {
            submittedEpoch = frameEpoch;
        }
    }

    public static long frameEpoch() {
        return frameEpoch;
    }

    /** Epochs up to and including this value are known complete on the GPU. */
    public static long completedEpoch() {
        return completedEpoch;
    }

    public static VkDevice device() {
        return Vulkan.getVkDevice();
    }

    public static long allocator() {
        return Vulkan.getAllocator();
    }

    public static int framesNum() {
        return Renderer.getFramesNum();
    }

    public static int currentFrame() {
        return Renderer.getCurrentFrame();
    }

    /**
     * The main command buffer of the frame currently being recorded, or {@code null}
     * when we are outside of frame recording (e.g. during renderer construction).
     *
     * <p>{@link Renderer#getCommandBuffer()} keeps returning the last submitted
     * buffer after the frame ends. Using that stale handle (as the renderer
     * reload in {@code LevelRenderer.allChanged()} did) makes
     * {@code vkCmdFillBuffer} crash inside the NVIDIA driver with
     * EXCEPTION_ACCESS_VIOLATION, and the lost fill leaves buffers such as the
     * traversal request queue with garbage counts.</p>
     */
    public static VkCommandBuffer commandBufferOrNull() {
        try {
            if (!Renderer.isRecording()) {
                return null;
            }
            return Renderer.getCommandBuffer();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Records GPU commands on a one-shot command buffer submitted to the graphics
     * queue and blocks until the submission has completed. Use only outside of the
     * hot frame path.
     */
    public static void oneShot(Consumer<VkCommandBuffer> recorder) {
        Queue queue = DeviceManager.getGraphicsQueue();
        CommandPool.CommandBuffer cb = queue.beginCommands();
        recorder.accept(cb.getHandle());
        queue.submitCommands(cb);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int res = vkWaitForFences(device(), cb.getFence(), true, UINT64_MAX);
            if (res != VK_SUCCESS) {
                throw new IllegalStateException("vkWaitForFences failed during one-shot submission: " + res);
            }
        }
        cb.reset();
    }

    /**
     * Destroys an image view after the frames that may still reference it have
     * retired. The underlying {@code VulkanImage} is released through
     * VulkanMod's deferred free queue, so destroying the view immediately (as
     * the old GL-style wrapper did) can leave a descriptor set/command buffer
     * pointing at a dead view.
     */
    public static void destroyImageViewDeferred(long view) {
        if (view == VK_NULL_HANDLE) {
            return;
        }
        MemoryManager memoryManager = null;
        try {
            memoryManager = MemoryManager.getInstance();
        } catch (Throwable ignored) {
        }
        if (memoryManager != null) {
            memoryManager.addFrameOp(() -> destroyImageViewNow(view));
        } else {
            destroyImageViewNow(view);
        }
    }

    private static void destroyImageViewNow(long view) {
        try {
            VkDevice device = device();
            if (device != null) {
                vkDestroyImageView(device, view, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final java.lang.reflect.Field F_BOUND_PIPELINE_HANDLE;
    static {
        java.lang.reflect.Field f = null;
        try {
            f = Renderer.class.getDeclaredField("boundPipelineHandle");
            f.setAccessible(true);
        } catch (Throwable ignored) {
        }
        F_BOUND_PIPELINE_HANDLE = f;
    }

    /** Invalidates VulkanMod's cached bound pipeline handle after custom vkCmdBindPipeline calls. */
    public static void invalidateExternalPipelineCache() {
        if (F_BOUND_PIPELINE_HANDLE == null) {
            return;
        }
        try {
            Renderer renderer = Renderer.getInstance();
            if (renderer != null) {
                F_BOUND_PIPELINE_HANDLE.setLong(renderer, 0L);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Ends the active dynamic rendering pass if one is currently open on the main command buffer. */
    public static void ensureOutsideRenderPass() {
        try {
            Renderer renderer = Renderer.getInstance();
            if (renderer != null && Renderer.isRecording() && renderer.getBoundRenderPass() != null) {
                renderer.endRenderPass();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Waits for all submitted GPU work to complete (maps the old {@code glFinish}). */
    public static void waitIdle() {
        try {
            VkDevice device = device();
            if (device != null) {
                int res = vkDeviceWaitIdle(device);
                if (res != VK_SUCCESS) {
                    Logger.warn("vkDeviceWaitIdle failed: " + res);
                    return;
                }
            }
        } catch (Throwable t) {
            // Do not claim any completion when the wait itself failed.
            Logger.warn("vkDeviceWaitIdle failed: " + t);
            return;
        }
        long submitted = submittedEpoch;
        if (submitted > completedEpoch) {
            completedEpoch = submitted;
        }
    }

    // ------------------------------------------------------------------
    // Synchronisation helpers
    // ------------------------------------------------------------------

    /** Global memory barrier between two pipeline stages. */
    public static void memoryBarrier(VkCommandBuffer cb, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, barrier, null, null);
        }
    }

    /** Buffer memory barrier for a (possibly partial) buffer range. */
    public static void bufferBarrier(VkCommandBuffer cb, long buffer, long offset, long size,
                                     int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer)
                    .offset(offset)
                    .size(size <= 0 ? VK_WHOLE_SIZE : size);
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, barrier, null);
        }
    }

    /** Image layout transition / memory barrier. */
    public static void imageBarrier(VkCommandBuffer cb, long image, int oldLayout, int newLayout, int aspect,
                                    int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            barrier.subresourceRange()
                    .aspectMask(aspect)
                    .baseMipLevel(0)
                    .levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0)
                    .layerCount(1);
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    /**
     * Conservative full memory barrier; the ported code used
     * {@code glMemoryBarrier} with various bit masks and GL's implicit
     * synchronisation hid a lot, so funnel those calls through here.
     */
    public static void fullBarrier(VkCommandBuffer cb) {
        memoryBarrier(cb,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                        | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT
                        | VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT
                        | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                        | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
    }

    /** {@link #fullBarrier(VkCommandBuffer)} on the current frame command buffer. */
    public static void fullBarrier() {
        VkCommandBuffer cb = commandBufferOrNull();
        if (cb != null) {
            ensureOutsideRenderPass();
            fullBarrier(cb);
        }
    }

    /**
     * Lightweight barrier for compute/transfer/indirect/vertex stages (does not
     * stall rasterization/fragment stages like {@code ALL_COMMANDS_BIT}).
     */
    public static void computeBarrier() {
        VkCommandBuffer cb = commandBufferOrNull();
        if (cb != null) {
            ensureOutsideRenderPass();
            memoryBarrier(cb,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT
                            | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
                            | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                            | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                            | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT
                            | VK_ACCESS_TRANSFER_READ_BIT);
        }
    }
}
