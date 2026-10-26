package voxel.client.core;

import voxel.client.core.rendering.hierarchical.AsyncNodeManager;
import voxel.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import voxel.client.core.rendering.hierarchical.NodeCleaner;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Beryl-compatible render pipeline (replaces the old Iris G-buffer pipeline).
 *
 * <p>When Beryl's shader pipeline is active, Beryl binds its HDR framebuffer
 * ({@code hdrFramebuffer}) during the terrain stage before running SSR, bloom
 * and tonemapping in {@code endRender()}. Because {@link NormalRenderPipeline}
 * captures the active external framebuffer and composites colour + depth into
 * it, inheriting from {@link NormalRenderPipeline} integrates directly with
 * Beryl's HDR pass.</p>
 */
public class BerylVoxelRenderPipeline extends NormalRenderPipeline {

    public BerylVoxelRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner,
                                  HierarchicalOcclusionTraverser traversal, BooleanSupplier syncCaptureSupplier) {
        super(nodeManager, nodeCleaner, traversal, syncCaptureSupplier);
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName() + " (Beryl)");
        super.addDebug(debug);
    }
}
