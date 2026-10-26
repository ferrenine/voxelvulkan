package voxel.client.mixin.beryl;

import voxel.client.core.IGetVoxelRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Beryl only renders the vanilla chunk sections (and entities) into its shadow
 * map. This lets the LOD terrain cast into it as well, so far trees and hills
 * shadow the far terrain like the near ones do.
 */
@Pseudo
@Mixin(targets = "net.beryl.render.ShadowMap", remap = false)
public class MixinBerylShadowMap {

    //After the SOLID and CUTOUT terrain layers, while the shadow render pass is active
    @Inject(method = "renderShadowMap",
            at = @At(value = "INVOKE",
                    target = "Lnet/beryl/render/ShadowMap;renderLayer(Lnet/vulkanmod/render/vertex/TerrainRenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                    ordinal = 1, shift = At.Shift.AFTER),
            require = 0)
    private void voxel$renderLodShadowCasters(CallbackInfo ci) {
        var system = IGetVoxelRenderSystem.getNullable();
        if (system != null) {
            system.renderBerylShadowCasters();
        }
    }
}
