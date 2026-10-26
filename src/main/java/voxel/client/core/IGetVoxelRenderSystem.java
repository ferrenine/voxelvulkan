package voxel.client.core;

import net.minecraft.client.Minecraft;

public interface IGetVoxelRenderSystem {
    VoxelRenderSystem getVoxelRenderSystem();
    void shutdownRenderer();
    void createRenderer();

    static VoxelRenderSystem getNullable() {
        var lr = (IGetVoxelRenderSystem)Minecraft.getInstance().levelRenderer;
        if (lr == null) return null;
        return lr.getVoxelRenderSystem();
    }
}
