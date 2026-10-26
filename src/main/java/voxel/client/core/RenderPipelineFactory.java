package voxel.client.core;

import voxel.client.core.rendering.hierarchical.AsyncNodeManager;
import voxel.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import voxel.client.core.rendering.hierarchical.NodeCleaner;
import voxel.client.core.util.BerylUtil;
import voxel.common.Logger;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner,
                                                        HierarchicalOcclusionTraverser traversal, BooleanSupplier syncCaptureSupplier) {
        if (BerylUtil.BERYL_INSTALLED && BerylUtil.SHADER_SUPPORT && BerylUtil.berylShaderEnabled()) {
            try {
                Logger.info("Creating Voxel Beryl render pipeline");
                return new BerylVoxelRenderPipeline(nodeManager, nodeCleaner, traversal, syncCaptureSupplier);
            } catch (Exception e) {
                Logger.error("Failed to create Beryl render pipeline", e);
                BerylUtil.disableBerylShaders();
            }
        }
        return new NormalRenderPipeline(nodeManager, nodeCleaner, traversal, syncCaptureSupplier);
    }
}
