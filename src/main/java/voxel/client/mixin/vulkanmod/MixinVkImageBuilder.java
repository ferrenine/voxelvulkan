package voxel.client.mixin.vulkanmod;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Extends {@link VulkanImage.Builder}'s {@code formatSize(int)} table with the
 * integer and depth formats used by Voxel ({@code VK_FORMAT_R32_UINT},
 * {@code VK_FORMAT_R8_UINT}, {@code VK_FORMAT_D32_SFLOAT_S8_UINT}, etc.).
 */
@Mixin(VulkanImage.Builder.class)
public class MixinVkImageBuilder {

    @Inject(method = "formatSize", at = @At("HEAD"), cancellable = true)
    private static void voxel$formatSize(int format, CallbackInfoReturnable<Integer> cir) {
        switch (format) {
            case VK_FORMAT_R8_UINT, VK_FORMAT_R8_SINT -> cir.setReturnValue(1);
            case VK_FORMAT_R16_UINT, VK_FORMAT_R16_SINT -> cir.setReturnValue(2);
            case VK_FORMAT_R32_UINT, VK_FORMAT_R32_SINT, VK_FORMAT_X8_D24_UNORM_PACK32 -> cir.setReturnValue(4);
            case VK_FORMAT_D32_SFLOAT_S8_UINT -> cir.setReturnValue(8);
        }
    }
}
