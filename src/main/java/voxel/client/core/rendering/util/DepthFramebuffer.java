package voxel.client.core.rendering.util;

import voxel.client.core.vk.VkFramebuffer;
import voxel.client.core.vk.VkTexture;

import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT;

/**
 * A depth render target. Vulkan port: the depth texture is a {@code VkVoxelImage}
 * (nearest/clamp sampler state is baked in at creation), rendering happens
 * through the framebuffer's render pass, and clearing is an explicit image clear.
 */
public class DepthFramebuffer {
    private final int depthType;
    private VkTexture depthBuffer;
    public final VkFramebuffer framebuffer = new VkFramebuffer();

    public DepthFramebuffer() {
        this(GL_DEPTH_COMPONENT24);
    }

    public DepthFramebuffer(int depthType) {
        this.depthType = depthType;
    }

    public boolean resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (this.depthBuffer == null || this.depthBuffer.getWidth() != width || this.depthBuffer.getHeight() != height) {
            if (this.depthBuffer != null) {
                this.depthBuffer.free();
            }
            this.depthBuffer = new VkTexture().store(this.depthType, 1, width, height);
            this.framebuffer.bind(this.getDepthAttachmentType(), this.depthBuffer).verify();
            return true;
        }
        return false;
    }

    public int getDepthAttachmentType() {
        return VkTexture.isDepthStencilFormat(this.depthType) ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
    }

    public void clear() {
        this.clear(1.0f);
    }

    public void clear(float depth) {
        if (this.depthBuffer != null) {
            this.depthBuffer.image().clearDepth(depth);
        }
    }

    public VkTexture getDepthTex() {
        return this.depthBuffer;
    }

    public void free() {
        this.framebuffer.free();
        if (this.depthBuffer != null) {
            this.depthBuffer.free();
        }
    }

    /** Binds this depth target as the active render target. */
    public void bind() {
        this.framebuffer.bindRenderTarget();
    }

    public int getFormat() {
        return this.depthType;
    }
}
