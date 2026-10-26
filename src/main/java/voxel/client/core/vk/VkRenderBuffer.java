package voxel.client.core.vk;

import voxel.client.vk.VkVoxelImage;
import voxel.common.util.TrackedObject;

/**
 * Vulkan replacement for the original GL renderbuffer. Backed by a depth
 * {@link VkVoxelImage}.
 */
public class VkRenderBuffer extends TrackedObject {

    /** Vulkan image handle. */
    public long id;

    private final int format;
    private final int width;
    private final int height;
    private final VkVoxelImage image;

    public VkRenderBuffer(int format, int width, int height) {
        this.format = format;
        this.width = width;
        this.height = height;
        this.image = VkVoxelImage.depth("voxel-rb", width, height, VkTexture.toVulkanFormat(format));
        this.id = this.image.handle();
    }

    public VkVoxelImage image() {
        return this.image;
    }

    public long view() {
        return this.image.view();
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getFormat() {
        return this.format;
    }

    @Override
    public void free() {
        this.free0();
        this.image.free();
    }
}
