package voxel.client.mixin.vulkanmod;

import net.minecraft.client.gui.GuiGraphics;
import net.vulkanmod.config.gui.VOptionScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import voxel.client.compat.VoxelSettingsSidebar;

/**
 * Makes VulkanMod's left settings sidebar scrollable.
 *
 * <p>VulkanMod stacks every mod icon and page button from {@code y = 4} down
 * without a scrollbar. With several mods installed, or at a large GUI scale,
 * the lower entries (including Voxel's pages) end up below the window and
 * cannot be reached because the screen never consumes the mouse wheel outside
 * the option list.</p>
 *
 * <p>This mixin only connects VulkanMod's lifecycle to
 * {@link VoxelSettingsSidebar}: relayout after a page rebuild, and draw the
 * scrollbar after VulkanMod's own widgets. The wheel itself is caught on the
 * inherited screen handler by
 * {@code voxel.client.mixin.minecraft.MixinContainerEventHandler}.</p>
 */
@Mixin(VOptionScreen.class)
public abstract class MixinVOptionScreen {

    @Inject(method = "buildPage", at = @At("RETURN"))
    private void voxel$layoutSidebar(CallbackInfo ci) {
        VoxelSettingsSidebar.layout((VOptionScreen) (Object) this, true);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void voxel$renderSidebarScrollbar(GuiGraphics graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo ci) {
        VoxelSettingsSidebar.renderScrollbar((VOptionScreen) (Object) this);
    }
}
