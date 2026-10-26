package voxel.client.core.util;

import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import voxel.client.config.VoxelConfig;
import voxel.client.vk.VkVoxel;
import voxel.common.Logger;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkResetQueryPool;

/**
 * Vulkan timestamp-query GPU profiler.
 *
 * <p>Markers are recorded on the current frame command buffer with a label.
 * An interval is the time between two consecutive markers and is reported
 * under the label of the first marker. Labels are stored per slot, so a pass
 * that is skipped on some frames does not shift the rest of the report.</p>
 *
 * <p>Every {@value #READ_INTERVAL_FRAMES} frames we try to read the newest
 * completed slot without stalling; the GPU usually trails the CPU by a few
 * frames, so scan back a few epochs. This is a debug path: the
 * {@code BOTTOM_OF_PIPE} timestamp can serialise passes on some GPUs, so turn
 * {@code gpu_timing} off when measuring maximum FPS.</p>
 */
public class GPUTiming {
    // A slot is reused every SLOTS frames. Keep it comfortably above the
    // number of frames in flight so an unread result is not reset first.
    private static final int SLOTS = 8;
    private static final int MAX_MARKERS = 10;
    private static final int READ_INTERVAL_FRAMES = 15;
    private static final int READ_LOOKBACK = 6;

    // Declared after the constants: the instance initializer uses them.
    public static GPUTiming INSTANCE = new GPUTiming();

    private long queryPool;
    private float timestampPeriod;
    private boolean disabled;
    private int currentEpoch = -1;
    private int markerIndex;
    private int lastReadEpoch = -1;
    private final int[] slotEpoch = new int[SLOTS];
    private final int[] slotMarkerCount = new int[SLOTS];
    private final String[][] slotLabels = new String[SLOTS][MAX_MARKERS];
    private final float[] timings = new float[MAX_MARKERS];
    private final String[] lastLabels = new String[MAX_MARKERS];
    private int lastIntervalCount;

    public void marker() {
        this.marker("frame");
    }

    public void marker(String label) {
        if (!VoxelConfig.CONFIG.gpuTiming || this.disabled || !this.ensureInit()) {
            return;
        }
        int epoch = (int) VkVoxel.frameEpoch();
        VkCommandBuffer cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        int slot = Math.floorMod(epoch, SLOTS);
        if (epoch != this.currentEpoch) {
            this.currentEpoch = epoch;
            this.markerIndex = 0;
            // The slot was last used SLOTS frames ago; its fence has long
            // completed. Host query reset is enabled by VulkanMod.
            vkResetQueryPool(VkVoxel.device(), this.queryPool, slot * MAX_MARKERS, MAX_MARKERS);
            this.slotEpoch[slot] = epoch;
            this.slotMarkerCount[slot] = 0;
            Arrays.fill(this.slotLabels[slot], null);
        }
        if (this.markerIndex >= MAX_MARKERS) {
            return;
        }
        this.slotLabels[slot][this.markerIndex] = label;
        int query = slot * MAX_MARKERS + this.markerIndex;
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, this.queryPool, query);
        this.slotMarkerCount[slot] = this.markerIndex + 1;
        this.markerIndex++;
    }

    private boolean ensureInit() {
        if (this.queryPool != 0L) {
            return true;
        }
        VkDevice device = VkVoxel.device();
        if (device == null || DeviceManager.deviceProperties == null) {
            return false;
        }
        VkPhysicalDeviceLimits limits = DeviceManager.deviceProperties.limits();
        if (limits.timestampPeriod() <= 0.0f || !limits.timestampComputeAndGraphics()) {
            this.disabled = true;
            Logger.warn("GPU timestamps are not supported by this device");
            return false;
        }
        if (!VkVoxel.isHostQueryResetSupported()) {
            // marker() resets a slot with the host-side vkResetQueryPool, which
            // requires the Vulkan 1.2 hostQueryReset feature. Without it the
            // call is invalid, so keep the profiler off instead of spamming
            // validation errors.
            this.disabled = true;
            Logger.warn("GPU timings disabled: hostQueryReset is not available");
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(SLOTS * MAX_MARKERS);
            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateQueryPool(device, info, null, pPool);
            if (result != VK_SUCCESS) {
                this.disabled = true;
                Logger.warn("vkCreateQueryPool failed for GPU timings: " + result);
                return false;
            }
            this.queryPool = pPool.get(0);
            this.timestampPeriod = limits.timestampPeriod();
            vkResetQueryPool(device, this.queryPool, 0, SLOTS * MAX_MARKERS);
        }
        return true;
    }

    /**
     * Attempts to read the newest completed frame slot without stalling.
     */
    public void tick() {
        if (!VoxelConfig.CONFIG.gpuTiming || this.disabled || this.queryPool == 0L) {
            return;
        }
        int epoch = (int) VkVoxel.frameEpoch();
        if (epoch <= 0 || epoch - this.lastReadEpoch < READ_INTERVAL_FRAMES) {
            return;
        }
        this.lastReadEpoch = epoch;
        VkDevice device = VkVoxel.device();
        if (device == null) {
            return;
        }
        for (int age = 1; age <= READ_LOOKBACK; age++) {
            int readEpoch = epoch - age;
            if (readEpoch <= 0) {
                return;
            }
            int readSlot = Math.floorMod(readEpoch, SLOTS);
            if (this.slotEpoch[readSlot] != readEpoch) {
                continue;
            }
            int count = this.slotMarkerCount[readSlot];
            if (count < 2) {
                continue;
            }
            // 8-byte timestamp + 4-byte availability, padded to 16 bytes/query.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = stack.malloc(count * 16);
                int result = vkGetQueryPoolResults(device, this.queryPool,
                        readSlot * MAX_MARKERS, count, data, 16L,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if (result != VK_SUCCESS) {
                    continue; // VK_NOT_READY: try an older slot
                }
                long address = MemoryUtil.memAddress(data);
                boolean available = true;
                for (int i = 0; i < count; i++) {
                    if (MemoryUtil.memGetInt(address + i * 16L + 8L) == 0) {
                        available = false;
                        break;
                    }
                }
                if (!available) {
                    continue;
                }
                int intervals = Math.min(count - 1, MAX_MARKERS);
                for (int i = 1; i < count; i++) {
                    long previous = MemoryUtil.memGetLong(address + (i - 1) * 16L);
                    long current = MemoryUtil.memGetLong(address + i * 16L);
                    this.timings[i - 1] = (float) ((double) (current - previous) * this.timestampPeriod / 1_000_000.0);
                    String label = this.slotLabels[readSlot][i - 1];
                    this.lastLabels[i - 1] = label != null ? label : "marker";
                }
                this.lastIntervalCount = intervals;
                this.slotMarkerCount[readSlot] = 0;
                return;
            }
        }
    }

    public String getDebug() {
        if (!VoxelConfig.CONFIG.gpuTiming || this.disabled || this.lastIntervalCount <= 0) {
            return "GpuTime: []";
        }
        StringBuilder str = new StringBuilder("GpuTime: [");
        for (int i = 0; i < this.lastIntervalCount; i++) {
            if (i > 0) {
                str.append(',');
            }
            String label = this.lastLabels[i] != null ? this.lastLabels[i] : "marker";
            str.append(label).append('=').append(String.format("%.2f", this.timings[i]));
        }
        str.append(']');
        return str.toString();
    }

    public void free() {
        if (this.queryPool != 0L && VkVoxel.device() != null) {
            VkVoxel.waitIdle();
            vkDestroyQueryPool(VkVoxel.device(), this.queryPool, null);
        }
        this.queryPool = 0L;
        this.timestampPeriod = 0.0f;
        this.currentEpoch = -1;
        this.markerIndex = 0;
        this.lastReadEpoch = -1;
        this.lastIntervalCount = 0;
        Arrays.fill(this.timings, 0.0f);
        Arrays.fill(this.lastLabels, null);
        for (int i = 0; i < SLOTS; i++) {
            this.slotEpoch[i] = 0;
            this.slotMarkerCount[i] = 0;
            Arrays.fill(this.slotLabels[i], null);
        }
    }

    public interface TimingDataConsumer {
        void accept(int[] metadata, long[] timings);
    }
}
