package voxel.client.core.vk;

import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;

import java.util.Locale;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_ARITHMETIC_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_BASIC_BIT;

/**
 * Vulkan replacement for the original OpenGL capability probe.
 *
 * <p>Most GL-only features are reported absent; device limits and memory data
 * are read from the physical device.</p>
 */
public class Capabilities {

    public static final Capabilities INSTANCE = new Capabilities();

    public final boolean repFragTest;
    public final boolean meshShaders;
    public final boolean INT64_t;
    public final long ssboMaxSize;
    /** Descriptor buffer-offset alignment required by the device (>= 16 in practice). */
    public final long minStorageBufferOffsetAlignment;
    /** Descriptor uniform-buffer-offset alignment required by the device. */
    public final long minUniformBufferOffsetAlignment;
    public final boolean isMesa;
    public final boolean canQueryGpuMemory;
    public final long totalDedicatedMemory;//Bytes, dedicated memory
    public final long totalDynamicMemory;//Bytes, total allocation memory - dedicated memory
    public final boolean compute;
    public final boolean indirectParameters;
    /** Device limit for vkCmdDrawIndexedIndirectCount's maxDrawCount (unsigned). */
    public final long maxDrawIndirectCount;
    public final boolean isIntel;
    public final boolean subgroup;
    /** Subgroup size reported by the device (0 when unknown). */
    public final int subgroupSize;
    public final boolean sparseBuffer;
    public final boolean isNvidia;
    public final boolean isAmd;
    public final boolean nvBarryCoords;
    public final boolean hasBrokenDepthSampler;

    public Capabilities() {
        var device = DeviceManager.device;
        String name = device != null && device.deviceName != null
                ? device.deviceName.toLowerCase(Locale.ROOT) : "";

        this.isNvidia = name.contains("nvidia") || name.contains("geforce")
                || name.contains("rtx") || name.contains("gtx");
        this.isAmd = name.contains("amd") || name.contains("radeon");
        this.isIntel = name.contains("intel");
        this.isMesa = false;

        this.compute = true;
        // Reported by the DeviceManager mixin from the physical-device query:
        // without drawIndirectCount the MDIC renderer must stay disabled.
        this.indirectParameters = voxel.client.vk.VkVoxel.isDrawIndirectCountSupported();
        this.repFragTest = false;
        this.meshShaders = false;
        this.sparseBuffer = false;
        this.nvBarryCoords = false;
        //AUDIT-FIXME: hardcoded false — VoxelClient still checks it; real AMD
        // depth-sampler detection was dropped in the Vulkan port.
        this.hasBrokenDepthSampler = false;
        this.INT64_t = device != null && device.availableFeatures.features().shaderInt64();

        long ssboMax = 0;
        long storageAlign = 16;
        long uniformAlign = 64;
        long maxDrawIndirect = 0;
        long dedicated = -1;
        long dynamic = -1;
        boolean subgroupSupported = false;
        int subgroupSize = 0;

        if (device != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceSubgroupProperties subgroupProps = VkPhysicalDeviceSubgroupProperties.calloc(stack)
                        .sType$Default();
                VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
                        .sType$Default()
                        .pNext(subgroupProps.address());
                vkGetPhysicalDeviceProperties2(DeviceManager.physicalDevice, props);

                // LWJGL exposes Vulkan uint32_t as a signed Java int. On devices
                // whose maxStorageBufferRange is 0xFFFFFFFF (NVIDIA) a plain
                // widening assignment produces -1, which would turn every
                // "capacity <= limit" check into a false positive.
                ssboMax = Integer.toUnsignedLong(props.properties().limits().maxStorageBufferRange());
                storageAlign = Math.max(16L, props.properties().limits().minStorageBufferOffsetAlignment());
                uniformAlign = Math.max(16L, props.properties().limits().minUniformBufferOffsetAlignment());
                maxDrawIndirect = Integer.toUnsignedLong(props.properties().limits().maxDrawIndirectCount());
                //Shaders call subgroupAdd/subgroupExclusiveAdd — ARITHMETIC
                // support is required (BASIC alone was under-gating before).
                subgroupSupported = (subgroupProps.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0
                        && (subgroupProps.supportedOperations() & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0;
                subgroupSize = subgroupProps.subgroupSize();

                VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
                vkGetPhysicalDeviceMemoryProperties(DeviceManager.physicalDevice, memoryProperties);
                long deviceLocal = 0;
                long total = 0;
                for (int i = 0; i < memoryProperties.memoryHeapCount(); i++) {
                    var heap = memoryProperties.memoryHeaps(i);
                    total += heap.size();
                    if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                        deviceLocal += heap.size();
                    }
                }
                dedicated = deviceLocal;
                dynamic = total - deviceLocal;
            }
        }

        this.ssboMaxSize = ssboMax;
        this.minStorageBufferOffsetAlignment = storageAlign;
        this.minUniformBufferOffsetAlignment = uniformAlign;
        this.maxDrawIndirectCount = maxDrawIndirect;
        this.canQueryGpuMemory = true;
        this.totalDedicatedMemory = dedicated;
        this.totalDynamicMemory = dynamic;
        this.subgroup = subgroupSupported;
        this.subgroupSize = subgroupSize;
    }

    public static void init() {
    }

    /**
     * Total dedicated memory reported by the device heaps. VK_EXT_memory_budget
     * is not enabled by VulkanMod, so this is NOT the free/available amount;
     * do not use it as a budget headroom check.
     */
    public long getTotalDedicatedGpuMemory() {
        return this.totalDedicatedMemory;
    }
}
