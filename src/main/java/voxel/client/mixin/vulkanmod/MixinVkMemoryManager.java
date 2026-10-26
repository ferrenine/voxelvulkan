package voxel.client.mixin.vulkanmod;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.util.vma.Vma;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod 0.6.8 maps mappable VMA allocations in {@code Buffer.createBuffer}
 * but its deferred destroy path calls {@code vmaDestroyBuffer} without a
 * matching {@code vmaUnmapMemory}. Both free entry points are patched here so
 * mapped host-visible buffers (upload/download streams, mapped geometry, and
 * device-mappable allocations) release their VMA mapping exactly once, before
 * the buffer is destroyed.
 *
 * <p>Pinned to VulkanMod 0.6.8+1.21.11: this targets the private static
 * {@code buffers} field and both {@code freeBuffer} overloads. When upgrading
 * VulkanMod, re-check them with {@code javap -p
 * net.vulkanmod.vulkan.memory.MemoryManager} before changing the dependency;
 * a mismatch fails mixin application at startup rather than silently leaking.</p>
 */
@Mixin(MemoryManager.class)
public class MixinVkMemoryManager {

    @Shadow
    @Final
    private static Long2ReferenceOpenHashMap<Buffer> buffers;

    @Inject(method = "freeBuffer(JJ)V", at = @At("HEAD"))
    private static void voxel$unmapBeforeDestroy(long id, long allocation, CallbackInfo ci) {
        voxel$unmap(id, allocation);
    }

    @Inject(method = "freeBuffer(Lnet/vulkanmod/vulkan/memory/buffer/Buffer$BufferInfo;)V", at = @At("HEAD"))
    private static void voxel$unmapBeforeDestroyInfo(Buffer.BufferInfo info, CallbackInfo ci) {
        voxel$unmap(info.id(), info.allocation());
    }

    private static void voxel$unmap(long id, long allocation) {
        Buffer buffer = buffers.get(id);
        // dataPtr is only non-zero for mappable memory types (Buffer.createBuffer
        // calls vmaMapMemory). A resized buffer keeps the same type, so an old
        // BufferInfo's allocation was mapped as well.
        if (buffer == null || buffer.getDataPtr() == 0L || allocation == 0L) {
            return;
        }
        Vma.vmaUnmapMemory(Vulkan.getAllocator(), allocation);
    }
}
