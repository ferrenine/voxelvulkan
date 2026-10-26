package voxel.client.mixin.minecraft;

import voxel.client.ICheekyClientChunkCache;
import voxel.client.config.VoxelConfig;
import voxel.common.world.service.VoxelIngestService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
    @Unique
    private static final boolean BOBBY_INSTALLED = FabricLoader.getInstance().isModLoaded("bobby");

    @Shadow volatile ClientChunkCache.Storage storage;

    @Override
    public LevelChunk voxel$cheekyGetChunk(int x, int z) {
        LevelChunk chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk != null && chunk.getPos().x == x && chunk.getPos().z == z) {
            return chunk;
        }
        return null;
    }

    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void voxel$ingestDisplacedOnPacket(int x, int z, FriendlyByteBuf buf,
                                              Map<Heightmap.Types, long[]> heightmaps,
                                              Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
                                              CallbackInfoReturnable<LevelChunk> cir) {
        if (VoxelConfig.CONFIG.ingestEnabled) {
            LevelChunk existing = this.storage.getChunk(this.storage.getIndex(x, z));
            if (existing != null) {
                VoxelIngestService.tryAutoIngestChunk(existing);
            }
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void voxel$ingestOnPacket(int x, int z, FriendlyByteBuf buf,
                                     Map<Heightmap.Types, long[]> heightmaps,
                                     Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
                                     CallbackInfoReturnable<LevelChunk> cir) {
        if (VoxelConfig.CONFIG.ingestEnabled) {
            LevelChunk chunk = cir.getReturnValue();
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }

    @Inject(method = "drop", at = @At("HEAD"))
    public void voxel$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxelConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
            var chunk = this.voxel$cheekyGetChunk(pos.x, pos.z);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }
}
