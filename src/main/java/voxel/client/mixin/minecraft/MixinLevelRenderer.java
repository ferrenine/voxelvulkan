package voxel.client.mixin.minecraft;

import voxel.client.VoxelClientInstance;
import voxel.client.config.VoxelConfig;
import voxel.client.core.IGetVoxelRenderSystem;
import voxel.client.core.VoxelRenderSystem;
import voxel.client.core.util.BerylUtil;
import voxel.common.Logger;
import voxel.common.world.WorldEngine;
import voxel.commonimpl.VoxelCommon;
import voxel.commonimpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IGetVoxelRenderSystem {
    @Shadow private @Nullable ClientLevel level;
    @Unique private VoxelRenderSystem renderer;

    @Override
    public VoxelRenderSystem getVoxelRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)
    private void reloadVoxelRenderer(CallbackInfo ci) {
        if (!VoxelConfig.CONFIG.isRenderingEnabled()) {
            this.shutdownRenderer();
            return;
        }
        // allChanged() is also called for ordinary vanilla chunk rebuilds and
        // during world load. Recreating the Voxel system there throws away and
        // reallocates two 1GB GPU buffers every time (and caused
        // VK_ERROR_OUT_OF_DEVICE_MEMORY on 8GB cards). Only rebuild when the
        // Beryl-vs-normal pipeline actually changed.
        if (this.renderer != null && voxel$pipelineMatchesConfig()) {
            return;
        }
        this.shutdownRenderer();
        if (this.level != null) {
            this.createRenderer();
        }
    }

    @Unique
    private boolean voxel$pipelineMatchesConfig() {
        boolean wantBeryl = BerylUtil.BERYL_INSTALLED && BerylUtil.SHADER_SUPPORT && BerylUtil.berylShaderEnabled();
        return this.renderer.isBerylPipeline() == wantBeryl;
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxel$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        voxel.client.pregen.WorldPregenerator.getInstance().cancelPregen();
        if (this.level != world) {
            this.shutdownRenderer();
        }
    }

    @Inject(method = "setLevel", at = @At("RETURN"))
    private void voxel$onSetWorldReturn(ClientLevel world, CallbackInfo ci) {
        if (world != null && this.renderer == null) {
            this.createRenderer();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void injectClose(CallbackInfo ci) {
        voxel.client.pregen.WorldPregenerator.getInstance().cancelPregen();
        this.shutdownRenderer();
    }

    @Override
    public void shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxelConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxelConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.level == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        var instance = (VoxelClientInstance)VoxelCommon.getInstance();
        if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = WorldIdentifier.ofEngine(this.level);
        if (world == null) {
            Logger.error("Null world selected");
            return;
        }
        try {
            this.renderer = new VoxelRenderSystem(world, instance.getServiceManager());
        } catch (Throwable t) {
            // A failed (re)allocation used to leave createRenderer() half done
            // and the next reload then filled buffers on a stale command
            // buffer, crashing inside the driver. Keep the game alive with LOD
            // disabled until a later allChanged()/setLevel() retries cleanly.
            this.renderer = null;
            Logger.error("Failed to create the Voxel renderer; LOD stays disabled until the next reload", t);
            return;
        }
        instance.updateDedicatedThreads();
    }
}
