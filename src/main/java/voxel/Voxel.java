package voxel;

import voxel.common.Logger;
import voxel.network.VoxelNetworking;
import net.fabricmc.api.ModInitializer;

/**
 * Main mod class for Voxel on Fabric.
 *
 * Handles network registration. The renderer is bootstrapped from the
 * VulkanMod integration layer (see voxel.client.vk) and client
 * initialization happens in {@link voxel.client.VoxelClient}.
 */
public class Voxel implements ModInitializer {

    @Override
    public void onInitialize() {
        Logger.info("Initializing Voxel (Next-Gen Voxel LOD Engine, Fabric + Vulkan)...");
        VoxelNetworking.register();
    }
}
