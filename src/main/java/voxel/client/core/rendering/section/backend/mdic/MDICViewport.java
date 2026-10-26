package voxel.client.core.rendering.section.backend.mdic;

import voxel.client.core.vk.VkBuffer;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;

public class MDICViewport extends Viewport<MDICViewport> {
    public final VkBuffer drawCountCallBuffer = new VkBuffer(1024).zero();
    public final VkBuffer drawCallBuffer = new VkBuffer(5L*4L*(MDICSectionRenderer.OPAQUE_CAPACITY
            + MDICSectionRenderer.TRANSLUCENT_CAPACITY)).zero();//opaque + translucent
    public final VkBuffer cutoutDrawCallBuffer =
            new VkBuffer(5L*4L*MDICSectionRenderer.CUTOUT_CAPACITY).zero();
    public final VkBuffer positionScratchBuffer  = new VkBuffer(8*400000).zero();//400k positions
    public final VkBuffer indirectLookupBuffer = new VkBuffer(HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE *4+4);//In theory, this could be global/not unique to the viewport
    public final VkBuffer visibilityBuffer;

    public MDICViewport(int maxSectionCount) {
        this.visibilityBuffer = new VkBuffer(maxSectionCount*4L);
    }

    @Override
    protected void delete0() {
        super.delete0();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.cutoutDrawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public VkBuffer getRenderList() {
        return this.indirectLookupBuffer;
    }
}
