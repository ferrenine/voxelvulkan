package voxel.client.mixin.beryl;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.util.ResettableQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Beryl starts the shadow-section flood fill at the light position
 * ({@code camera + lightDir * shadowRenderDistance * 16}, {@code y + 64}).
 * With a small vanilla render distance that point is outside the loaded chunk
 * graph, so the 5x5 seed grid finds no sections and Beryl draws no vanilla
 * chunks into its shadow map. The LOD pass only covers the sections it renders
 * itself, so a tree inside the vanilla render distance has no caster at all.
 *
 * <p>Seed the flood fill at the camera's own section instead. The light-space
 * frustum still limits which sections are drawn; the adjacency walk still
 * reaches every loaded section inside it.</p>
 */
@Pseudo
@Mixin(targets = "net.beryl.render.ShadowMapSectionGraph", remap = false)
public class MixinBerylShadowMapSectionGraph {

    @Shadow private SectionGrid sectionGrid;
    @Shadow private ResettableQueue<RenderSection> sectionQueue;
    @Shadow private short lastFrame;

    @Shadow
    private static void initFirstNode(RenderSection renderSection, short frame) {
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("HEAD"), cancellable = true)
    private void voxel$startShadowGraphAtCamera(CallbackInfo ci) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        int cx = Mth.floor(camera.x) >> 4;
        int cy = Mth.floor(camera.y) >> 4;
        int cz = Mth.floor(camera.z) >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                RenderSection section = this.sectionGrid.getSectionAtBlockPos(
                        (cx + dx) << 4, cy << 4, (cz + dz) << 4);
                if (section != null) {
                    initFirstNode(section, this.lastFrame);
                    this.sectionQueue.add(section);
                }
            }
        }
        ci.cancel();
    }
}
