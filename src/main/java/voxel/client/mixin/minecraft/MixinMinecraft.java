package voxel.client.mixin.minecraft;

import voxel.client.VoxelClientInstance;
import voxel.commonimpl.VoxelCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void voxel$injectWorldClose(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && voxel.client.config.VoxelConfig.CONFIG.ingestEnabled) {
            var chunkSource = mc.level.getChunkSource();
            if (chunkSource != null) {
                int px = (mc.player != null) ? (mc.player.getBlockX() >> 4) : 0;
                int pz = (mc.player != null) ? (mc.player.getBlockZ() >> 4) : 0;
                int rd = mc.options.getEffectiveRenderDistance() + 2;
                for (int cx = px - rd; cx <= px + rd; cx++) {
                    for (int cz = pz - rd; cz <= pz + rd; cz++) {
                        var chunk = chunkSource.getChunk(cx, cz, false);
                        if (chunk != null) {
                            voxel.common.world.service.VoxelIngestService.tryAutoIngestChunk(chunk);
                        }
                    }
                }
            }
        }
        if (mc.levelRenderer != null) {
            ((voxel.client.core.IGetVoxelRenderSystem) mc.levelRenderer).shutdownRenderer();
        }
        if (VoxelCommon.isAvailable() && VoxelClientInstance.isInGame) {
            VoxelCommon.shutdownInstance();
            VoxelClientInstance.isInGame = false;
        }
    }
}
