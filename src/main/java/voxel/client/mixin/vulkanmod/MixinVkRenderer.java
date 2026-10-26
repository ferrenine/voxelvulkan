package voxel.client.mixin.vulkanmod;

import voxel.client.VoxelClient;
import voxel.client.core.IGetVoxelRenderSystem;
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

    @Inject(method = "beginFrame", at = @At("RETURN"))
    private void voxel$afterFrameBegin(CallbackInfo ci) {
        var system = IGetVoxelRenderSystem.getNullable();
        if (system != null) {
            system.onFrameBeginTimestamp();
        }
    }

    /**
     * Tracks which frame epoch has really been submitted. VkVoxel.waitIdle uses
     * this to recognise completed work after vkDeviceWaitIdle without ever
     * treating the frame that is still being recorded as complete.
     */
    @Inject(method = "submitFrame", at = @At("RETURN"))
    private void voxel$onSubmitFrame(CallbackInfo ci) {
        VkVoxel.onFrameSubmitted();
    }

    @Inject(method = "flushCmds", at = @At("RETURN"))
    private void voxel$onFlushCmds(CallbackInfo ci) {
        // flushCmds returns early when no frame is recording.
        if (Renderer.isRecording()) {
            VkVoxel.onFrameSubmitted();
        }
    }

    @Inject(method = "endRenderPass()V", at = @At("HEAD"))
    private void voxel$onEndRenderPass(CallbackInfo ci) {
        BerylUtil.tuneBerylShadowParameters();
    }
}
