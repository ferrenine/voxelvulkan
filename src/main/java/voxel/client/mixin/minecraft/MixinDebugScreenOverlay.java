package voxel.client.mixin.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import voxel.client.config.VoxelConfig;
import voxel.client.core.IGetVoxelRenderSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends Voxel's diagnostics to the F3 screen.
 *
 * <p>1.21.11 builds the two debug columns into fresh {@code ArrayList}s and calls
 * {@code renderLines(GuiGraphics, List, boolean)} for each; the {@code true} call
 * is the left column (it receives the "To edit: press [F3+F6]" hint). Appending at
 * HEAD there is safe because the list is mutable and built per frame.</p>
 */
@Mixin(DebugScreenOverlay.class)
public class MixinDebugScreenOverlay {

    @Inject(method = "renderLines", at = @At("HEAD"))
    private void voxel$appendLodInfo(GuiGraphics graphics, List<String> lines, boolean leftSide, CallbackInfo ci) {
        if (!leftSide || !VoxelConfig.CONFIG.renderStatistics) {
            return;
        }
        var system = IGetVoxelRenderSystem.getNullable();
        if (system == null) {
            return;
        }
        try {
            List<String> voxelLines = new ArrayList<>();
            system.addDebugInfo(voxelLines);

            // Keep the LOD block inside the left column. Vanilla's own lines can
            // already fill a small screen, so cap the optional lines instead of
            // drawing them past the bottom edge. Always keep the first three
            // (pipeline/config, frame time, memory) as the useful minimum.
            int guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            int available = Math.max(3, (guiHeight / 9) - lines.size() - 2);
            for (int i = 0; i < voxelLines.size() && i < available; i++) {
                lines.add(voxelLines.get(i));
            }
        } catch (Throwable ignored) {
            // The debug overlay must never be able to take the game down.
        }
    }
}
