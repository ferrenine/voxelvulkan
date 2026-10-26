package voxel.client.mixin.beryl;

import net.beryl.render.RenderingPipeline;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Works around a Beryl 0.2.1-alpha startup/resize crash.
 *
 * <p>Beryl initializes its HDR framebuffers from the VulkanMod swapchain size.
 * While the window is minimized (or during the first frame after joining from a
 * minimized window) that size is 0x0. VulkanMod then fails the image creation
 * with {@code VK_ERROR_INITIALIZATION_FAILED}; Beryl does not guard its
 * {@code initResources} path, so the client crashes.</p>
 *
 * <p>Clamp the two framebuffer dimensions to at least one texel so Beryl can
 * finish initialization. When the swapchain becomes valid again, detect the
 * size mismatch at the start of {@code beginRender} and let Beryl recreate the
 * framebuffers at the real resolution. This also covers screen-mode changes
 * that recreate the swapchain without calling Beryl's own resize hook.</p>
 */
@Pseudo
@Mixin(targets = "net.beryl.render.RenderingPipeline", remap = false)
public abstract class MixinBerylRenderingPipeline {

    @Shadow
    private static boolean init;

    @Shadow
    private static Framebuffer hdrFramebuffer;

    @ModifyVariable(method = "createFramebuffers", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private static int voxel$clampFramebufferWidth(int width) {
        return Math.max(1, width);
    }

    @ModifyVariable(method = "createFramebuffers", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private static int voxel$clampFramebufferHeight(int height) {
        return Math.max(1, height);
    }

    @Inject(method = "beginRender", at = @At("HEAD"), require = 0)
    private static void voxel$resizeForRestoredSwapchain(CallbackInfo ci) {
        if (!init) {
            return;
        }
        Renderer renderer = Renderer.getInstance();
        if (renderer == null) {
            return;
        }
        SwapChain swapChain = renderer.getSwapChain();
        if (swapChain == null) {
            return;
        }
        int width = swapChain.getWidth();
        int height = swapChain.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (hdrFramebuffer == null
                || hdrFramebuffer.getWidth() != width
                || hdrFramebuffer.getHeight() != height) {
            RenderingPipeline.resizeFramebuffers(width, height);
        }
    }
}
