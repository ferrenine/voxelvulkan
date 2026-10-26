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
    public final boolean isMesa;
    public final boolean canQueryGpuMemory;
    public final long totalDedicatedMemory;//Bytes, dedicated memory
    public final long totalDynamicMemory;//Bytes, total allocation memory - dedicated memory
    public final boolean compute;
    public final boolean indirectParameters;
    public final boolean isIntel;
    public final boolean subgroup;
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
        // VK_KHR_draw_indirect_count is enabled through our DeviceManager mixin;
        // without it the LOD renderer cannot submit its MDIC-style draws at all.
        this.indirectParameters = true;
        this.repFragTest = false;
        this.meshShaders = false;
        this.sparseBuffer = false;
        this.nvBarryCoords = false;
        //AUDIT-FIXME: hardcoded false — VoxelClient still checks it; real AMD
        // depth-sampler detection was dropped in the Vulkan port.
        this.hasBrokenDepthSampler = false;
        this.INT64_t = device != null && device.availableFeatures.features().shaderInt64();

        long ssboMax = 0;
        long dedicated = -1;
        long dynamic = -1;
        boolean subgroupSupported = false;

        if (device != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceSubgroupProperties subgroupProps = VkPhysicalDeviceSubgroupProperties.calloc(stack)
                        .sType$Default();
                VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
                        .sType$Default()
                        .pNext(subgroupProps.address());
                vkGetPhysicalDeviceProperties2(DeviceManager.physicalDevice, props);

                ssboMax = props.properties().limits().maxStorageBufferRange();
                //Shaders call subgroupAdd/subgroupExclusiveAdd — ARITHMETIC
                // support is required (BASIC alone was under-gating before).
                subgroupSupported = (subgroupProps.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0
                        && (subgroupProps.supportedOperations() & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0;

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
        this.canQueryGpuMemory = true;
        this.totalDedicatedMemory = dedicated;
        this.totalDynamicMemory = dynamic;
        this.subgroup = subgroupSupported;
    }

    public static void init() {
    }

    /**
     * Best-effort free dedicated memory. VK_EXT_memory_budget is not enabled by
     * VulkanMod, so this reports the total dedicated heap size.
     */
    public long getFreeDedicatedGpuMemory() {
        return this.totalDedicatedMemory;
    }
}
