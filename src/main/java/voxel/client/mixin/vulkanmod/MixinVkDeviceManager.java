package voxel.client.mixin.vulkanmod;

import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;

/**
 * Enables the device features the LOD renderer needs but VulkanMod does not
 * request by default:
 *
 * <ul>
 *   <li>{@code shaderInt64} – 64-bit integer arithmetic in node/geometry shaders;</li>
 *   <li>{@code drawIndirectCount} – MDIC-style indirect draws with GPU-provided counts;</li>
 *   <li>storage-image/atomic fragment access used by the composite passes.</li>
 * </ul>
 *
 * <p>The mixin rewrites the {@link VkDeviceCreateInfo} just before
 * {@code vkCreateDevice} runs, so the features are part of the physical device
 * creation.</p>
 */
@Mixin(DeviceManager.class)
public class MixinVkDeviceManager {

    @ModifyArg(
            method = "createLogicalDevice",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK10;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"),
            index = 1)
    private static VkDeviceCreateInfo voxel$enableVoxelFeatures(VkDeviceCreateInfo createInfo) {
        var device = DeviceManager.device;
        if (device == null) {
            return createInfo;
        }

        VkPhysicalDeviceFeatures features = createInfo.pEnabledFeatures();
        if (features != null) {
            VkPhysicalDeviceFeatures available = device.availableFeatures.features();
            if (available.shaderInt64()) {
                features.shaderInt64(true);
            }
            if (available.shaderInt16()) {
                features.shaderInt16(true);
            }
            if (available.fragmentStoresAndAtomics()) {
                features.fragmentStoresAndAtomics(true);
            }
            if (available.vertexPipelineStoresAndAtomics()) {
                features.vertexPipelineStoresAndAtomics(true);
            }
            if (available.shaderStorageImageArrayDynamicIndexing()) {
                features.shaderStorageImageArrayDynamicIndexing(true);
            }
            if (available.shaderSampledImageArrayDynamicIndexing()) {
                features.shaderSampledImageArrayDynamicIndexing(true);
            }
            if (available.fillModeNonSolid()) {
                features.fillModeNonSolid(true);
            }
            if (available.multiDrawIndirect()) {
                features.multiDrawIndirect(true);
            }
            if (available.drawIndirectFirstInstance()) {
                features.drawIndirectFirstInstance(true);
            }
        }

        // drawIndirectCount lives in the Vulkan 1.2 features struct. VulkanMod's
        // create-info chain only carries the 1.1 struct, so query availability
        // ourselves and wire the struct into the chain (or reuse an existing one).
        MemoryStack stack = MemoryStack.stackGet();
        VkPhysicalDeviceVulkan11Features available11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
        VkPhysicalDeviceVulkan12Features available12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType$Default()
                .pNext(available11.address());
        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType$Default()
                .pNext(available12.address());
        vkGetPhysicalDeviceFeatures2(DeviceManager.physicalDevice, features2);
        boolean drawIndirectCount = available12.drawIndirectCount();
        boolean shaderDrawParameters = available11.shaderDrawParameters();

        long pNext = createInfo.pNext();
        long vk11Address = 0L;
        long vk12Address = 0L;
        long cursor = pNext;
        while (cursor != 0L) {
            int sType = MemoryUtil.memGetInt(cursor);
            if (sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES) {
                vk11Address = cursor;
            } else if (sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES) {
                vk12Address = cursor;
            }
            cursor = MemoryUtil.memGetLong(cursor + 8L);
        }

        if (vk11Address != 0L && shaderDrawParameters) {
            VkPhysicalDeviceVulkan11Features.create(vk11Address).shaderDrawParameters(true);
        }

        if (vk12Address == 0L) {
            VkPhysicalDeviceVulkan12Features vk12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType$Default()
                    .pNext(pNext);
            if (drawIndirectCount) {
                vk12.drawIndirectCount(true);
            }
            createInfo.pNext(vk12);
        } else if (drawIndirectCount) {
            VkPhysicalDeviceVulkan12Features.create(vk12Address).drawIndirectCount(true);
        }

        return createInfo;
    }
}
