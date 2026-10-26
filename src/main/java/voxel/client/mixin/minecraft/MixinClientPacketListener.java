package voxel.client.mixin.minecraft;

import voxel.client.VoxelClientInstance;
import voxel.client.config.VoxelConfig;
import voxel.commonimpl.VoxelCommon;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;commonPlayerSpawnInfo()Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"))
    private void voxel$init(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (VoxelCommon.isAvailable() && !VoxelClientInstance.isInGame) {
            VoxelClientInstance.isInGame = true;
            if (VoxelConfig.CONFIG.enabled) {
                if (VoxelCommon.getInstance() != null) {
                    VoxelCommon.shutdownInstance();
                }
                VoxelCommon.createInstance();
            }
            voxel.client.network.ClientLodStreamManager.reset();
        }
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void voxel$onRespawn(net.minecraft.network.protocol.game.ClientboundRespawnPacket packet, CallbackInfo ci) {
        voxel.client.network.ClientLodStreamManager.reset();
    }

    @Inject(method = "enableChunkLight", at = @At("TAIL"))
    private void voxel$ingestOnLightReady(net.minecraft.world.level.chunk.LevelChunk chunk, int x, int z, CallbackInfo ci) {
        if (VoxelConfig.CONFIG.ingestEnabled && chunk != null) {
            voxel.common.world.service.VoxelIngestService.tryAutoIngestChunk(chunk);
        }
    }
}
