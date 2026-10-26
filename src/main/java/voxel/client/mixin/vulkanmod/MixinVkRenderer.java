package voxel.client.mixin.vulkanmod;

import voxel.client.VoxelClient;
import voxel.client.core.util.BerylUtil;
import voxel.client.vk.VkVoxel;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks VulkanMod's {@link Renderer}:
 * <ul>
 *   <li>initialises {@link VoxelClient} once the Vulkan renderer is ready;</li>
 *   <li>advances LING's frame epoch every time a new frame begins;</li>
 *   <li>tunes Beryl shadow-map parameters right after {@code updateLight} finishes.</li>
 * </ul>
 */
@Mixin(Renderer.class)
public class MixinVkRenderer {

    @Inject(method = "initRenderer", at = @At("RETURN"))
    private static void voxel$onInitRenderer(CallbackInfo ci) {
        VoxelClient.initVoxelClient();
    }

    @Inject(method = "beginFrame", at = @At("HEAD"))
    private void voxel$onFrameBegin(CallbackInfo ci) {
        VkVoxel.onFrameBegin();
    }

    @Inject(method = "endRenderPass()V", at = @At("HEAD"))
    private void voxel$onEndRenderPass(CallbackInfo ci) {
        BerylUtil.tuneBerylShadowParameters();
    }
}
