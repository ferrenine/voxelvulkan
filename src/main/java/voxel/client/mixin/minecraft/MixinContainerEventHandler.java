package voxel.client.mixin.minecraft;

import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.vulkanmod.config.gui.VOptionScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import voxel.client.compat.VoxelSettingsSidebar;

/**
 * Routes wheel input over VulkanMod's settings sidebar to Voxel's scroll
 * handler before the normal child delegation runs.
 *
 * <p>{@code VOptionScreen} inherits this default handler from
 * {@code ContainerEventHandler}; overriding it by adding a method to the
 * VulkanMod class would not be remapped by Fabric and would therefore never be
 * called at runtime. Injecting at the declaring interface is stable across
 * mappings and keeps every other screen on the vanilla path.</p>
 */
@Mixin(ContainerEventHandler.class)
public interface MixinContainerEventHandler {

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void voxel$scrollSettingsSidebar(double mouseX, double mouseY,
                                             double horizontal, double vertical,
                                             CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (self instanceof VOptionScreen screen
                && VoxelSettingsSidebar.scroll(screen, mouseX, mouseY, vertical)) {
            cir.setReturnValue(true);
        }
    }
}
