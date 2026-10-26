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
 * <p>Clamp the two framebuffer dimensions to a small but valid size so Beryl
 * can finish initialization while the swapchain is minimized or between
 * screen modes. A 1x1 clamp is not enough: Beryl's Bloom contains five mip
 * levels and computes each as {@code width >> (level + 1)}, so 1x1 produces a
 * 0x0 Bloom framebuffer and Vulkan fails with
 * {@code VK_ERROR_INITIALIZATION_FAILED}. Use 32x32, which leaves one texel
 * at the deepest mip. When the swapchain becomes valid again, detect the
 * size mismatch at the start of {@code beginRender} and let Beryl recreate
 * the framebuffers at the real resolution.</p>
 */
@Pseudo
@Mixin(targets = "net.beryl.render.RenderingPipeline", remap = false)
public abstract class MixinBerylRenderingPipeline {

    /** 1 << Bloom's mip count (5), so every mip edge is at least one texel. */
    private static final int MIN_FRAMEBUFFER_SIZE = 32;

    @Shadow
    private static boolean init;

    @Shadow
    private static Framebuffer hdrFramebuffer;

    @ModifyVariable(method = "createFramebuffers", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private static int voxel$clampFramebufferWidth(int width) {
        return Math.max(MIN_FRAMEBUFFER_SIZE, width);
    }

    @ModifyVariable(method = "createFramebuffers", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private static int voxel$clampFramebufferHeight(int height) {
        return Math.max(MIN_FRAMEBUFFER_SIZE, height);
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
        // Match the clamped size createFramebuffers would use, otherwise a
        // swapchain smaller than the minimum would resize every frame.
        int targetWidth = Math.max(MIN_FRAMEBUFFER_SIZE, width);
        int targetHeight = Math.max(MIN_FRAMEBUFFER_SIZE, height);
        if (hdrFramebuffer == null
                || hdrFramebuffer.getWidth() != targetWidth
                || hdrFramebuffer.getHeight() != targetHeight) {
            RenderingPipeline.resizeFramebuffers(targetWidth, targetHeight);
        }
    }
}
