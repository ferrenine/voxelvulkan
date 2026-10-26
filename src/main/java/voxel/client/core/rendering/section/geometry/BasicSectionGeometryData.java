package voxel.client.core.rendering.section.geometry;

import voxel.client.core.vk.Capabilities;
import voxel.client.core.vk.VkBuffer;
import voxel.client.vk.VkVoxel;
import voxel.common.Logger;

/**
 * Vulkan port note: Vulkan has no sparse buffer support in this integration, so
 * the geometry buffer is always fully allocated (and not zeroed - the driver
 * hands out uninitialised memory; every location that is read is written by the
 * meshing pass first).
 */
public class BasicSectionGeometryData implements IGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;
    private final VkBuffer sectionMetadataBuffer;
    private final VkBuffer geometryBuffer;
    private final VkBuffer lightBuffer;

    private final int maxSectionCount;
    private int currentSectionCount;

    public BasicSectionGeometryData(int maxSectionCount, long geometryCapacity) {
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new VkBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryCapacity%8)!=0) {
            throw new IllegalStateException();
        }
        // VK_WHOLE_SIZE descriptors are bounded by maxStorageBufferRange. Intel
        // iGPUs report 1 GiB - 4 B, so the historical 1 GiB default is already
        // out of range there; NVIDIA's 0xFFFFFFFF also rejects a 4096 MiB value
        // from the UI. Clamp before allocating instead of failing in the driver.
        long ssboLimit = Capabilities.INSTANCE.ssboMaxSize;
        if (ssboLimit > 0 && geometryCapacity > ssboLimit) {
            long clamped = ssboLimit & ~7L;
            Logger.warn("Geometry capacity " + (geometryCapacity / (1024 * 1024))
                    + "MB exceeds maxStorageBufferRange (" + (ssboLimit / (1024 * 1024))
                    + "MB); clamping to " + (clamped / (1024 * 1024)) + "MB");
            geometryCapacity = clamped;
        }
        long start = System.currentTimeMillis();
        String msg = "Creating " + (geometryCapacity/(1024*1024)) + "MB geometry buffer (+"
                + (geometryCapacity/(1024*1024)) + "MB lightAO, "
                + (2L*geometryCapacity/(1024*1024)) + "MB total)";
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            msg += "; device has " + (Capabilities.INSTANCE.getTotalDedicatedGpuMemory()/(1024*1024)) + "MB total dedicated memory";
        }
        Logger.info(msg);
        Logger.info("if your game crashes/exits here without any other log message, try manually decreasing the geometry capacity");
        VkBuffer geometry = null;
        try {
            geometry = new VkBuffer(geometryCapacity, false).name("VoxelGeometry");
            this.geometryBuffer = geometry;
            this.lightBuffer = new VkBuffer(geometryCapacity, false).name("VoxelLightAO");
        } catch (RuntimeException e) {
            // Don't leak the first 1GB buffer or the metadata buffer when an
            // allocation is refused (8GB GPUs hit this during renderer reloads).
            if (geometry != null) {
                geometry.free();
            }
            this.sectionMetadataBuffer.free();
            throw e;
        }
        long delta = System.currentTimeMillis() - start;
        Logger.info("Successfully allocated the geometry buffer in " + delta + "ms");
    }

    /** No-op: the whole buffer is allocated up front under Vulkan. */
    public void ensureAccessable(int maxElementAccess) {
    }

    public VkBuffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public VkBuffer getLightBuffer() {
        return this.lightBuffer;
    }

    public VkBuffer getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {//In bytes
        return this.geometryBuffer.size();
    }

    @Override
    public void free() {
        this.sectionMetadataBuffer.free();
        VkVoxel.waitIdle();
        this.geometryBuffer.free();
        this.lightBuffer.free();
    }
}
