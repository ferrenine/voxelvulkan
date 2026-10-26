package voxel.client.core.vk;

import voxel.client.vk.VkVoxel;
import voxel.client.vk.VkVoxelImage;
import voxel.common.util.TrackedObject;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageViewUsageCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_DEPTH32F_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_R32F;
import static org.lwjgl.opengl.GL30C.GL_R32UI;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RGBA16F;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan replacement for the original GL texture wrapper.
 *
 * <p>{@code store(format, levels, width, height)} creates a {@link VkVoxelImage}
 * with the mapped Vulkan format. The old GL format/mip/level getters are kept so
 * the renderer code still reads the same values while it is being ported.</p>
 */
public class VkTexture extends TrackedObject {

    /** Vulkan image handle (0 until {@link #store} was called). */
    public long id;

    private final int type;
    private int format;
    private int width;
    private int height;
    private int levels;
    private boolean hasAllocated;

    private VkVoxelImage image;
    private long customView = VK_NULL_HANDLE;
    private long srgbView = VK_NULL_HANDLE;
    private boolean ownsImage = true;

    private static int COUNT;
    private static long ESTIMATED_TOTAL_SIZE;
    /** Estimated bytes this texture contributed to ESTIMATED_TOTAL_SIZE. */
    private long estimatedSize;

    public VkTexture() {
        this(GL_TEXTURE_2D);
    }

    public VkTexture(int type) {
        this.type = type;
        COUNT++;
    }

    public VkTexture store(int format, int levels, int width, int height) {
        this.format = format;
        this.levels = levels;
        this.width = width;
        this.height = height;

        int vkFormat = toVulkanFormat(format);
        if (isDepthFormat(format)) {
            this.image = VkVoxelImage.depth("voxel-tex", width, height, vkFormat);
            if (isDepthStencilFormat(format)) {
                this.customView = VulkanImage.createImageView(this.image.handle(), vkFormat,
                        VK_IMAGE_ASPECT_DEPTH_BIT, 1, Math.max(1, levels));
            }
        } else if (levels > 1) {
            this.image = VkVoxelImage.mipmapped("voxel-tex", width, height, vkFormat, levels);
        } else {
            this.image = new VkVoxelImage("voxel-tex", width, height, vkFormat);
        }
        this.id = this.image.handle();
        this.ownsImage = true;
        this.hasAllocated = true;
        if (this.estimatedSize != 0L) {
            ESTIMATED_TOTAL_SIZE -= this.estimatedSize;
        }
        long estimated = 0L;
        int mipWidth = width;
        int mipHeight = height;
        for (int level = 0; level < Math.max(1, levels); level++) {
            estimated += (long) mipWidth * mipHeight * 4L;
            mipWidth = Math.max(1, mipWidth >> 1);
            mipHeight = Math.max(1, mipHeight >> 1);
        }
        this.estimatedSize = estimated;
        ESTIMATED_TOTAL_SIZE += estimated;
        return this;
    }

    /**
     * Creates a view of this texture. For combined depth-stencil textures
     * ({@code GL_DEPTH24_STENCIL8} / {@code GL_DEPTH32F_STENCIL8}), this creates a stencil-aspect
     * {@code VkImageView} so stencil can be sampled via {@code usampler2D}.
     */
    public VkTexture createView() {
        this.assertAllocated();
        VkTexture view = new VkTexture(this.type);
        view.format = this.format;
        view.width = this.width;
        view.height = this.height;
        view.levels = this.levels;
        view.image = this.image;
        view.id = this.image.handle();
        view.ownsImage = false;
        if (isDepthStencilFormat(this.format)) {
            view.customView = VulkanImage.createImageView(this.image.handle(), this.image.format(),
                    VK_IMAGE_ASPECT_STENCIL_BIT, 1, Math.max(1, this.levels));
        }
        view.hasAllocated = true;
        return view;
    }

    // ------------------------------------------------------------------
    // Vulkan accessors
    // ------------------------------------------------------------------

    public VkVoxelImage image() {
        this.assertAllocated();
        return this.image;
    }

    public long view() {
        this.assertAllocated();
        return this.customView != VK_NULL_HANDLE ? this.customView : this.image.view();
    }

    public long levelView(int level) {
        this.assertAllocated();
        return this.image.levelView(level);
    }

    /**
     * Sample-only {@code R8G8B8A8_SRGB} view of an RGBA8 texture (hardware sRGB
     * decode, all mips). Only valid when the image was created with
     * {@code MUTABLE_FORMAT}, which Beryl's VulkanImageMixin adds to every
     * VulkanMod image.
     */
    public long srgbView() {
        this.assertAllocated();
        if (this.srgbView == VK_NULL_HANDLE) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // The image also carries STORAGE usage, which sRGB formats do not support.
                VkImageViewUsageCreateInfo usage = VkImageViewUsageCreateInfo.calloc(stack)
                        .sType$Default()
                        .usage(VK_IMAGE_USAGE_SAMPLED_BIT);
                VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .pNext(usage.address())
                        .image(this.image.handle())
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(VK_FORMAT_R8G8B8A8_SRGB);
                info.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(Math.max(1, this.levels))
                        .baseArrayLayer(0)
                        .layerCount(1);
                LongBuffer pView = stack.mallocLong(1);
                int result = vkCreateImageView(VkVoxel.device(), info, null, pView);
                if (result != VK_SUCCESS) {
                    throw new IllegalStateException("Failed to create sRGB image view: " + result);
                }
                this.srgbView = pView.get(0);
            }
        }
        return this.srgbView;
    }

    public long handle() {
        this.assertAllocated();
        return this.id;
    }

    /** Default aspect mask of this texture's format. */
    public int aspectMask() {
        return aspectOf(this.format);
    }

    private static int aspectOf(int glFormat) {
        return isDepthFormat(glFormat) ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
    }

    // ------------------------------------------------------------------
    // Format mapping
    // ------------------------------------------------------------------

    public static boolean isDepthFormat(int glFormat) {
        return isDepthStencilFormat(glFormat) || glFormat == GL_DEPTH_COMPONENT24;
    }

    public static boolean isDepthStencilFormat(int glFormat) {
        return glFormat == GL_DEPTH24_STENCIL8 || glFormat == GL_DEPTH32F_STENCIL8;
    }

    public static int toVulkanFormat(int glFormat) {
        if (glFormat == GL_RGBA8) {
            return VK_FORMAT_R8G8B8A8_UNORM;
        }
        if (glFormat == GL_R32UI) {
            return VK_FORMAT_R32_UINT;
        }
        if (glFormat == GL_R32F) {
            return VK_FORMAT_R32_SFLOAT;
        }
        if (glFormat == GL_R8UI) {
            return VK_FORMAT_R8_UINT;
        }
        if (glFormat == GL_RGBA16F) {
            return VK_FORMAT_R16G16B16A16_SFLOAT;
        }
        if (glFormat == GL_DEPTH24_STENCIL8) {
            return VK_FORMAT_D24_UNORM_S8_UINT;
        }
        if (glFormat == GL_DEPTH32F_STENCIL8) {
            return VK_FORMAT_D32_SFLOAT_S8_UINT;
        }
        if (glFormat == GL_DEPTH_COMPONENT24) {
            return VK_FORMAT_D32_SFLOAT;
        }
        throw new IllegalArgumentException("Unsupported GL texture format for Vulkan: " + glFormat);
    }

    // ------------------------------------------------------------------
    // Bookkeeping
    // ------------------------------------------------------------------

    @Override
    public void free() {
        this.free0();
        if (this.customView != VK_NULL_HANDLE) {
            VkVoxel.destroyImageViewDeferred(this.customView);
            this.customView = VK_NULL_HANDLE;
        }
        if (this.srgbView != VK_NULL_HANDLE) {
            VkVoxel.destroyImageViewDeferred(this.srgbView);
            this.srgbView = VK_NULL_HANDLE;
        }
        if (this.image != null && this.ownsImage) {
            this.image.free();
        }
        this.image = null;
        COUNT--;
        if (this.estimatedSize != 0L) {
            ESTIMATED_TOTAL_SIZE -= this.estimatedSize;
            this.estimatedSize = 0L;
        }
    }

    public VkTexture name(String name) {
        return this;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getLevels() {
        return this.levels;
    }

    public int getFormat() {
        return this.format;
    }

    public void assertAllocated() {
        if (!this.hasAllocated) {
            throw new IllegalStateException("Texture has not been allocated with store()");
        }
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getEstimatedTotalSize() {
        return ESTIMATED_TOTAL_SIZE;
    }
}
