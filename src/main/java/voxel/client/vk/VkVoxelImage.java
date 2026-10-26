package voxel.client.vk;

import voxel.common.util.TrackedObject;
import voxel.common.Logger;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A Vulkan image (texture / render target / storage image) owned by Voxel.
 *
 * <p>Wraps VulkanMod's {@link VulkanImage}. Unlike the old {@code VkTexture}, this
 * covers storage images used by compute pipelines (culling buffers, HiZ, ...) as
 * well as sampled and depth images.</p>
 */
public class VkVoxelImage extends TrackedObject {

    // Formats used across the LOD renderer.
    public static final int R32_UINT = VK_FORMAT_R32_UINT;
    public static final int R32_FLOAT = VK_FORMAT_R32_SFLOAT;
    public static final int RGBA8 = VK_FORMAT_R8G8B8A8_UNORM;
    public static final int RGBA16F = VK_FORMAT_R16G16B16A16_SFLOAT;
    public static final int DEPTH32F = VK_FORMAT_D32_SFLOAT;
    public static final int DEPTH24_STENCIL8 = VK_FORMAT_D24_UNORM_S8_UINT;

    private static final int DATA_USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
            | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_STORAGE_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private static final int DEPTH_USAGE = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
            | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private static final int COLOR_TARGET_USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
            | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    public final VulkanImage image;
    private final String name;

    private static int COUNT;

    public VkVoxelImage(String name, int width, int height, int vkFormat) {
        this(name, width, height, vkFormat, 1, false);
    }

    /** Creates a mipmapped data image (e.g. HiZ chains). */
    public static VkVoxelImage mipmapped(String name, int width, int height, int vkFormat, int mipLevels) {
        return new VkVoxelImage(name, width, height, vkFormat, mipLevels, false);
    }

    private VkVoxelImage(String name, int width, int height, int vkFormat, int mipLevels, boolean depth) {
        this(name, width, height, vkFormat, mipLevels, depth ? DEPTH_USAGE : DATA_USAGE);
    }

    private VkVoxelImage(String name, int width, int height, int vkFormat, int mipLevels, int usage) {
        this.name = name;
        this.image = VulkanImage.builder(width, height)
                .setName(name)
                .setFormat(vkFormat)
                .setMipLevels(Math.max(1, mipLevels))
                .setUsage(usage)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
        COUNT++;
    }

    /** Creates a depth image usable as a depth attachment and as a sampled image. */
    public static VkVoxelImage depth(String name, int width, int height, int vkFormat) {
        return new VkVoxelImage(name, width, height, vkFormat, 1, true);
    }

    /** Creates a colour image usable as a colour attachment. */
    public static VkVoxelImage colorTarget(String name, int width, int height, int vkFormat) {
        return new VkVoxelImage(name, width, height, vkFormat, 1, COLOR_TARGET_USAGE);
    }

    public String name() {
        return this.name;
    }

    public long handle() {
        return this.image.getId();
    }

    public long view() {
        return this.image.getImageView();
    }

    public long levelView(int level) {
        return this.image.getLevelImageView(level);
    }

    public int width() {
        return this.image.width;
    }

    public int height() {
        return this.image.height;
    }

    public int format() {
        return this.image.format;
    }

    public int aspect() {
        return this.image.aspect;
    }

    public static int getCount() {
        return COUNT;
    }

    /**
     * Transitions this image to {@code newLayout} on the given command buffer,
     * using conservative synchronisation masks. Callers that need tighter
     * barriers should use {@link VkVoxel#imageBarrier}.
     */
    public void transition(int newLayout) {
        int old = this.image.getCurrentLayout();
        if (old == newLayout) {
            return;
        }
        var cb = VkVoxel.commandBufferOrNull();
        if (cb != null) {
            VkVoxel.ensureOutsideRenderPass();
            barrier(cb, old, newLayout);
        } else {
            VkVoxel.oneShot(c -> barrier(c, old, newLayout));
        }
        this.image.setCurrentLayout(newLayout);
    }

    /** Transitions this image to {@code newLayout} directly on {@code cb} (caller must be outside a render pass). */
    public void transitionOn(org.lwjgl.vulkan.VkCommandBuffer cb, int newLayout) {
        int old = this.image.getCurrentLayout();
        if (old == newLayout) {
            return;
        }
        barrier(cb, old, newLayout);
        this.image.setCurrentLayout(newLayout);
    }

    private void barrier(org.lwjgl.vulkan.VkCommandBuffer cb, int old, int newLayout) {
        VkVoxel.imageBarrier(cb, this.image.getId(), old, newLayout, this.image.aspect,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    }

    /**
     * Uploads pixel data into a mip level of this image through VulkanMod's
     * asynchronous staging path. Must not be used while a render pass is active.
     *
     * @param mipLevel mip level to write
     * @param xOffset  destination x offset in pixels
     * @param yOffset  destination y offset in pixels
     * @param width    width of the pixel block in pixels
     * @param height   height of the pixel block in pixels
     * @param data     tightly packed pixel data (row length == width)
     */
    public void upload(int mipLevel, int xOffset, int yOffset, int width, int height, java.nio.ByteBuffer data) {
        this.image.uploadSubTextureAsync(mipLevel, 0, width, height, xOffset, yOffset, 0, 0, width, data);
    }

    /** {@link #upload(int, int, int, int, int, java.nio.ByteBuffer)} from a native address. */
    public void upload(int mipLevel, int xOffset, int yOffset, int width, int height, long address) {
        this.image.uploadSubTextureAsync(mipLevel, 0, width, height, xOffset, yOffset, 0, 0, width, address);
    }

    /**
     * Clears the depth aspect of this image to {@code depth}. Recorded into the
     * frame's command buffer (or a one-shot submission outside frame recording).
     */
    public void clearDepth(float depth) {
        int aspect = this.image.aspect & VK_IMAGE_ASPECT_DEPTH_BIT;
        if (aspect == 0) {
            aspect = VK_IMAGE_ASPECT_DEPTH_BIT;
        }
        final int clearAspect = aspect;
        var cb = VkVoxel.commandBufferOrNull();
        if (cb != null) {
            VkVoxel.ensureOutsideRenderPass();
            this.recordClear(cb, depth, 0, clearAspect);
        } else {
            VkVoxel.oneShot(c -> this.recordClear(c, depth, 0, clearAspect));
        }
    }

    /** Clears both depth and stencil (or whichever aspects this image owns). */
    public void clearDepthStencil(float depth, int stencil) {
        int aspect = this.image.aspect & (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT);
        if (aspect == 0) {
            aspect = VK_IMAGE_ASPECT_DEPTH_BIT;
        }
        final int clearAspect = aspect;
        var cb = VkVoxel.commandBufferOrNull();
        if (cb != null) {
            VkVoxel.ensureOutsideRenderPass();
            this.recordClear(cb, depth, stencil, clearAspect);
        } else {
            VkVoxel.oneShot(c -> this.recordClear(c, depth, stencil, clearAspect));
        }
    }

    /** Clears a colour/integer image to all zeros. */
    public void clearColorZero() {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb != null) {
            VkVoxel.ensureOutsideRenderPass();
            this.recordClearColorZero(cb);
        } else {
            VkVoxel.oneShot(this::recordClearColorZero);
        }
    }

    private void recordClearColorZero(org.lwjgl.vulkan.VkCommandBuffer cb) {
        int old = this.image.getCurrentLayout();
        long handle = this.image.getId();
        VkVoxel.imageBarrier(cb, handle, old, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue value = VkClearColorValue.calloc(stack);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(Math.max(1, this.image.mipLevels))
                    .baseArrayLayer(0)
                    .layerCount(1);
            vkCmdClearColorImage(cb, handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, range);
        }
        int restore = old == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : old;
        VkVoxel.imageBarrier(cb, handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, restore, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        this.image.setCurrentLayout(restore);
    }

    private void recordClear(org.lwjgl.vulkan.VkCommandBuffer cb, float depth, int stencil, int aspect) {
        int old = this.image.getCurrentLayout();
        long handle = this.image.getId();
        VkVoxel.imageBarrier(cb, handle, old, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, aspect,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearDepthStencilValue value = VkClearDepthStencilValue.calloc(stack)
                    .depth(depth)
                    .stencil(stencil);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(aspect)
                    .baseMipLevel(0)
                    .levelCount(Math.max(1, this.image.mipLevels))
                    .baseArrayLayer(0)
                    .layerCount(1);
            vkCmdClearDepthStencilImage(cb, handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, range);
        }
        int restore = old == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : old;
        VkVoxel.imageBarrier(cb, handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, restore, aspect,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        this.image.setCurrentLayout(restore);
    }

    @Override
    public void free() {
        this.free0();
        COUNT--;
        try {
            this.image.free();
        } catch (Throwable t) {
            Logger.error("Failed to free VkVoxelImage " + this.name, t);
        }
    }

    @Override
    public String toString() {
        return "VkVoxelImage[" + this.name + ", " + this.image.width + "x" + this.image.height + "]";
    }
}
