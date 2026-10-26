package voxel.client.mixin.minecraft;

import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to expose the private texture field in LightTexture.
 * Required for Voxel to bind the light texture directly.
 *
 * 1.21.11 port note: LightTexture no longer stores a DynamicTexture with a GL texture id -
 * it now owns a GpuTexture/GpuTextureView pair (the view is publicly exposed via
 * LightTexture#getTextureView()). Callers that used voxel$getLightTexture().getId() must be
 * reworked against the GpuDevice/Vulkan path.
 */
@Mixin(LightTexture.class)
public interface AccessorLightTexture {
    @Accessor("texture")
    GpuTexture voxel$getLightTexture();
}
