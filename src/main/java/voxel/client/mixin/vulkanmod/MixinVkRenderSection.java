package voxel.client.mixin.vulkanmod;

import voxel.client.config.VoxelConfig;
import voxel.client.core.IGetVoxelRenderSystem;
import voxel.common.world.service.VoxelIngestService;
import voxel.commonimpl.VoxelCommon;
import voxel.commonimpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.vulkanmod.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks compiled non-empty vanilla chunk sections in VulkanMod so
 * {@code ChunkBoundStore} masks out loaded vanilla sections from LOD rendering.
 */
@Mixin(RenderSection.class)
public abstract class MixinVkRenderSection {

    @Shadow public int xOffset;
    @Shadow public int yOffset;
    @Shadow public int zOffset;

    @Shadow public abstract boolean isCompiled();

    @Unique
    private boolean voxel$tracked = false;

    @Unique
    private long voxel$sectionPos() {
        int x = this.xOffset >> 4;
        int y = this.yOffset >> 4;
        int z = this.zOffset >> 4;
        if (VoxelCommon.IS_MINE_IN_ABYSS) {
            int sector = (x + 512) >> 10;
            x -= sector << 10;
            y += 16 + (256 - 32 - sector * 30);
        }
        return SectionPos.asLong(x, y, z);
    }

    @Unique
    private void voxel$ingestSection() {
        if (!VoxelConfig.CONFIG.ingestEnabled) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int cx = this.xOffset >> 4;
        int cy = this.yOffset >> 4;
        int cz = this.zOffset >> 4;
        var chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        int secIdx = cy - level.getMinSectionY();
        var sections = chunk.getSections();
        if (secIdx < 0 || secIdx >= sections.length) {
            return;
        }
        var section = sections[secIdx];
        if (section == null) {
            return;
        }
        var lp = level.getLightEngine();
        var csp = SectionPos.of(cx, cy, cz);
        // Never overwrite good LOD data with a pre-light snapshot. A section that
        // has no light data yet is ingested later by enableChunkLight; baking it
        // here stores zero sky light and the renderer renders it as full daylight.
        if (!section.hasOnlyAir()
                && lp.getDebugSectionType(LightLayer.SKY, csp) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA
                && lp.getDebugSectionType(LightLayer.BLOCK, csp) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA) {
            return;
        }
        var bl = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
        var sl = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);
        VoxelIngestService.rawIngest(WorldIdentifier.of(level), section, cx, cy, cz,
                bl == null ? null : bl.copy(), sl == null ? null : sl.copy());
    }

    @Inject(method = "setOrigin", at = @At("HEAD"))
    private void voxel$onSetOrigin(int x, int y, int z, CallbackInfo ci) {
        boolean wasTracked = this.voxel$tracked;
        if (wasTracked) {
            this.voxel$tracked = false;
            this.voxel$ingestSection();
        }
        var system = IGetVoxelRenderSystem.getNullable();
        if (system != null) {
            long pos = this.voxel$sectionPos();
            if (wasTracked) {
                system.boundStore.removeSection(pos);
            }
            // Empty-but-compiled sections must leave the coverage set too.
            system.boundStore.markCompiled(pos, false);
        }
    }

    @Inject(method = "setCompletelyEmpty", at = @At("HEAD"))
    private void voxel$onSetCompletelyEmpty(boolean empty, CallbackInfo ci) {
        boolean compiled = this.isCompiled();
        boolean shouldTrack = compiled && !empty;
        var system = IGetVoxelRenderSystem.getNullable();
        long pos = this.voxel$sectionPos();
        if (system != null) {
            // Empty sections still count as covered: vanilla has no geometry
            // there, so a LOD0 section over them is redundant as well.
            system.boundStore.markCompiled(pos, compiled);
        }
        if (shouldTrack == this.voxel$tracked) {
            return;
        }
        this.voxel$tracked = shouldTrack;
        if (shouldTrack) {
            this.voxel$ingestSection();
        }
        if (system != null) {
            if (shouldTrack) {
                system.boundStore.addSection(pos);
            } else {
                system.boundStore.removeSection(pos);
            }
        }
    }
}
