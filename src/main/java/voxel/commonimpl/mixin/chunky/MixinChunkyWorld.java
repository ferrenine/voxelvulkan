package voxel.commonimpl.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import voxel.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * Chunky (Fabric) integration for Voxel.
 * Intercepts chunks pre-generated via /chunky and automatically ingests them
 * into the 3D Voxel LOD database in real time.
 *
 * Pseudo + require = 0 so the mod still loads when Chunky is not installed
 * or when its internals differ from what we target.
 */
@Pseudo
@Mixin(targets = "org.popcraft.chunky.platform.FabricWorld", remap = false)
public class MixinChunkyWorld {
    @WrapOperation(
            method = "getChunkAtAsync",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
                    remap = true
            ),
            require = 0
    )
    private CompletableFuture<ChunkResult<ChunkAccess>> voxel$captureGeneratedChunk(
            ServerChunkCache instance,
            int x,
            int z,
            ChunkStatus chunkStatus,
            boolean create,
            Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original
    ) {
        var future = original.call(instance, x, z, chunkStatus, create);
        return future.thenApply(res -> {
            if (res != null) {
                res.ifSuccess(chunk -> {
                    if (chunk != null) {
                        VoxelIngestService.tryAutoIngestChunkAccess(chunk, instance.getLevel());
                    }
                });
            }
            return res;
        });
    }
}
