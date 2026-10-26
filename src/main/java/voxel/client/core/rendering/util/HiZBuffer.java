package voxel.client.core.rendering.util;

import voxel.client.core.vk.VkTexture;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.vk.VkVoxel;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.opengl.GL30C.GL_R32F;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Hierarchical depth (HiZ) buffer used by the occlusion traversal.
 *
 * <p>Generates the conservative max-depth mip pyramid on the GPU via compute
 * shaders ({@code hiz_copy.comp} for mip 0 using {@code textureGather}, and
 * {@code hiz_downsample.comp} for mips 1..N-1).</p>
 */
public class HiZBuffer {
    private final AutoBindingShader hizCopy = Shader.makeAuto()
            .add(ShaderType.COMPUTE, "voxel:hiz/hiz_copy.comp")
            .compile()
            .name("HiZ Copy Mip0");

    private final AutoBindingShader hizDownsample = Shader.makeAuto()
            .add(ShaderType.COMPUTE, "voxel:hiz/hiz_downsample.comp")
            .compile()
            .name("HiZ Downsample");

    private VkTexture texture;
    private int levels;
    private int width;
    private int height;

    public HiZBuffer() {
    }

    public HiZBuffer(int ignoredType) {
    }

    private void alloc(int width, int height) {
        this.levels = Math.max(1, (int) Math.ceil(Math.log(Math.max(width, height)) / Math.log(2)));
        this.texture = new VkTexture().store(GL_R32F, this.levels, width, height).name("HiZ");
        this.width = width;
        this.height = height;
    }

    public void buildMipChain(VkTexture srcDepthTex, int width, int height) {
        int targetW = Math.max(1, Integer.highestOneBit(width));
        int targetH = Math.max(1, Integer.highestOneBit(height));
        if (this.texture == null || this.width != targetW || this.height != targetH) {
            if (this.texture != null) {
                this.texture.free();
                this.texture = null;
            }
            this.alloc(targetW, targetH);
        }
        if (srcDepthTex == null) {
            return;
        }
        VkCommandBuffer cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }

        VkVoxel.ensureOutsideRenderPass();
        srcDepthTex.image().transitionOn(cb, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.texture.image().transitionOn(cb, VK_IMAGE_LAYOUT_GENERAL);

        // Mip 0: gather 2x2 from srcDepthTex into mip 0
        this.hizCopy
                .texture(0, 0, srcDepthTex)
                .storageImageLevel(1, this.texture, 0)
                .texture(2, 0, srcDepthTex)
                .pushFloat2(0, 1.0f / this.width, 1.0f / this.height)
                .pushUint(8, this.width)
                .pushUint(12, this.height)
                .pushFloat2(16, 0.0f, 0.0f)
                .dispatch((this.width + 15) / 16, (this.height + 15) / 16, 1);

        int mipW = this.width;
        int mipH = this.height;
        for (int level = 1; level < this.levels; level++) {
            mipW = Math.max(1, mipW >> 1);
            mipH = Math.max(1, mipH >> 1);

            VkVoxel.memoryBarrier(cb,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

            this.hizDownsample
                    .storageImageLevel(0, this.texture, level - 1)
                    .storageImageLevel(1, this.texture, level)
                    .pushUint(0, mipW)
                    .pushUint(4, mipH)
                    .dispatch((mipW + 15) / 16, (mipH + 15) / 16, 1);
        }

        this.texture.image().transitionOn(cb, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    public void copyTopMip(VkTexture srcDepthTex, VulkanImage vanillaDepth, float depthScale, float depthBias) {
        if (this.texture == null || srcDepthTex == null) {
            return;
        }
        VkCommandBuffer cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        VkVoxel.ensureOutsideRenderPass();
        srcDepthTex.image().transitionOn(cb, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.texture.image().transitionOn(cb, VK_IMAGE_LAYOUT_GENERAL);

        boolean hasVanillaDepth = vanillaDepth != null && vanillaDepth.getId() != 0L;

        this.hizCopy
                .texture(0, 0, srcDepthTex)
                .storageImageLevel(1, this.texture, 0);
        if (hasVanillaDepth) {
            if (vanillaDepth.getCurrentLayout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    vanillaDepth.transitionImageLayout(stack, cb, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
            }
            this.hizCopy.image(2, vanillaDepth)
                    .pushFloat2(16, depthScale, depthBias);
        } else {
            this.hizCopy.texture(2, 0, srcDepthTex)
                    .pushFloat2(16, 0.0f, 0.0f);
        }
        this.hizCopy
                .pushFloat2(0, 1.0f / this.width, 1.0f / this.height)
                .pushUint(8, this.width)
                .pushUint(12, this.height)
                .dispatch((this.width + 15) / 16, (this.height + 15) / 16, 1);

        VkVoxel.computeBarrier();
        this.texture.image().transitionOn(cb, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    public void free() {
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
        this.hizCopy.free();
        this.hizDownsample.free();
    }

    /** Vulkan image handle of the HiZ texture (0 when not allocated). */
    public long getHizTextureId() {
        return this.texture != null ? this.texture.id : 0L;
    }

    public VkTexture getHizTexture() {
        return this.texture;
    }

    /** The HiZ texture as a Vulkan image (null when not allocated). */
    public VulkanImage hizImage() {
        return this.texture != null ? this.texture.image().image : null;
    }

    //AUDIT-NOTE: name is misleading — packs w<<16|h, NOT mip levels
    // (screenspace.glsl decodes it symmetrically).
    public int getPackedLevels() {
        return (this.width << 16) | this.height;
    }
}
