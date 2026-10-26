package voxel.client.mixin.minecraft;

import voxel.client.config.VoxelConfig;
import voxel.common.world.service.VoxelIngestService;
import voxel.commonimpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {

    @Unique
    private int bottomSectionY;

    @Shadow @Final public LevelRenderer levelRenderer;

    @Shadow public abstract ClientChunkCache getChunkSource();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxel$getBottom(
            ClientPacketListener networkHandler,
            ClientLevel.ClientLevelData properties,
            ResourceKey<Level> registryRef,
            Holder<DimensionType> dimensionType,
            int loadDistance,
            int simulationDistance,
            LevelRenderer worldRenderer,
            boolean debugWorld,
            long biomeZoomSeed,
            int seaLevel,
            CallbackInfo cir) {
        this.bottomSectionY = ((Level)(Object)this).getMinY() >> 4;
    }

    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void voxel$injectIngestOnStateChange(BlockPos pos, BlockState old, BlockState updated, CallbackInfo cir) {
        if (old == updated) return;
        if (!updated.isAir()) return;
        if (!VoxelConfig.CONFIG.ingestEnabled) return;

        var self = (Level)(Object)this;
        var wi = WorldIdentifier.of(self);
        if (wi == null) {
            return;
        }

        int x = pos.getX()&15;
        int y = pos.getY()&15;
        int z = pos.getZ()&15;
        if (x == 0 || x==15 || y==0 || y==15 || z==0||z==15) {
            var csp = SectionPos.of(pos);

            var section = self.getChunk(pos).getSection(csp.y()-this.bottomSectionY);
            var lp = self.getLightEngine();

            var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
            var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);

            VoxelIngestService.rawIngest(wi, section, csp.x(), csp.y(), csp.z(), blp==null?null:blp.copy(), slp==null?null:slp.copy());
        }
    }
}
