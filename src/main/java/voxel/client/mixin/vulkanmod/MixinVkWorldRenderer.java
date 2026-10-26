package voxel.client.mixin.vulkanmod;

import voxel.client.VoxelClient;
import voxel.client.core.IGetVoxelRenderSystem;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.util.BerylUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks VulkanMod's {@link WorldRenderer} to drive Voxel' LOD pass
 * right after the cutout terrain layer finishes rendering.
 */
@Mixin(WorldRenderer.class)
public class MixinVkWorldRenderer {

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void voxel$onAllChanged(CallbackInfo ci) {
        var system = IGetVoxelRenderSystem.getNullable();
        if (system != null) {
            system.boundStore.reset();
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxel$onSetLevel(ClientLevel level, CallbackInfo ci) {
        var system = IGetVoxelRenderSystem.getNullable();
        if (system != null) {
            system.boundStore.reset();
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void voxel$beforeRenderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ,
                                                Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        if (BerylUtil.berylShadowActive()) {
            return;
        }
        var system = IGetVoxelRenderSystem.getNullable();
        if (system != null) {
            var fogData = net.vulkanmod.vulkan.VRenderSystem.getFogData();
            if (fogData != null) {
                float far = voxel.client.config.VoxelConfig.CONFIG.sectionRenderDistance * 32.0f * 16.0f;
                fogData.renderDistanceStart = far * 0.8f;
                fogData.renderDistanceEnd = far;
            }
        }
        if (VoxelClient.disableVanillaChunkRender()) {
            if (renderType == TerrainRenderType.CUTOUT && system != null) {
                Viewport<?> viewport = system.setupViewport(projection, modelView, camX, camY, camZ);
                system.renderOpaque(viewport);
            }
            ci.cancel();
        }
    }

    @ModifyVariable(method = "setupRenderer", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private boolean voxel$enableSpectatorCullInFreecam(boolean spectator) {
        if (spectator) {
            return true;
        }
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameRenderer != null) {
            if (mc.getCameraEntity() != mc.player
                    || mc.gameRenderer.getMainCamera().position().distanceToSqr(mc.player.position()) > 4.0) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void voxel$afterRenderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ,
                                               Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        if (BerylUtil.berylShadowActive()) {
            return;
        }
        if (renderType == TerrainRenderType.CUTOUT) {
            var system = IGetVoxelRenderSystem.getNullable();
            if (system != null) {
                Viewport<?> viewport = system.setupViewport(projection, modelView, camX, camY, camZ);
                system.renderOpaque(viewport);
            }
        } else if (renderType == TerrainRenderType.TRANSLUCENT) {
            var system = IGetVoxelRenderSystem.getNullable();
            if (system != null) {
                system.renderTranslucent(system.getViewport());
            }
        }
    }
}
