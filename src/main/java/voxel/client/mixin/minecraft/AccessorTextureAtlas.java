package voxel.client.mixin.minecraft;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to expose the private mip level count field in TextureAtlas.
 * Required for Voxel to match texture sampler LOD settings with the block atlas.
 *
 * 1.21.11 port note: the field was renamed mipLevel -> mipLevelCount
 * (the public maxMipLevel field still holds mipLevelCount - 1).
 */
@Mixin(TextureAtlas.class)
public interface AccessorTextureAtlas {
    @Accessor("mipLevelCount")
    int voxel$getMipLevel();
}
