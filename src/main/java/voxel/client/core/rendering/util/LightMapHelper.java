package voxel.client.core.rendering.util;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import voxel.client.mixin.minecraft.AccessorLightTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Access to the vanilla lightmap texture through VulkanMod's Vulkan image.
 *
 * <p>The old GL path bound the lightmap's GL texture id to a texture unit; under
 * Vulkan the lightmap is a descriptor binding, so LOD shaders take the image
 * from {@link #lightMapImage()} when their descriptor sets are written.</p>
 */
public class LightMapHelper {

    public static VulkanImage lightMapImage() {
        LightTexture lightTexture = Minecraft.getInstance().gameRenderer.lightTexture();
        GpuTexture gpuTexture = ((AccessorLightTexture) lightTexture).voxel$getLightTexture();
        if (gpuTexture instanceof VkGpuTexture vkTex) {
            return vkTex.getVulkanImage();
        }
        if (gpuTexture instanceof GlTexture glTex) {
            VkGpuTexture vk = VkGpuTexture.fromGlTexture(glTex);
            if (vk != null) {
                return vk.getVulkanImage();
            }
        }
        throw new IllegalStateException("Lightmap texture is not backed by VulkanMod (got "
                + (gpuTexture == null ? "null" : gpuTexture.getClass().getName()) + ")");
    }

    /** Image view of the lightmap, for descriptor writes. */
    public static long lightMapView() {
        return lightMapImage().getImageView();
    }

    /** Sampler of the lightmap image. */
    public static long lightMapSampler() {
        return lightMapImage().getSampler();
    }
}
