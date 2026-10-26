package voxel.client.core;

import voxel.client.RenderStatistics;
import voxel.client.TimingStatistics;
import voxel.client.VoxelClient;
import voxel.client.config.VoxelConfig;
import voxel.client.core.util.GPUTiming;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkFramebuffer;
import voxel.client.core.vk.VkState;
import voxel.client.core.vk.VkTexture;
import voxel.client.core.model.ModelBakerySubsystem;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.hierarchical.AsyncNodeManager;
import voxel.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import voxel.client.core.rendering.hierarchical.NodeCleaner;
import voxel.client.core.rendering.post.FullscreenBlit;
import voxel.client.core.rendering.section.backend.AbstractSectionRenderer;
import voxel.client.core.rendering.util.DepthFramebuffer;
import voxel.client.core.rendering.util.DownloadStream;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.vk.VkVoxel;
import voxel.common.util.TrackedObject;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL11C.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL30C.GL_DEPTH32F_STENCIL8;
import voxel.client.core.vk.shader.AutoBindingShader;

public abstract class AbstractRenderPipeline extends TrackedObject {
    private final BooleanSupplier syncCaptureHasWork;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    protected AbstractSectionRenderer<?,?> sectionRenderer;

    private final FullscreenBlit depthCopy = new FullscreenBlit("voxel:post/fullscreen2.vert", "voxel:post/depth_copy.frag");

    protected final VkBuffer blitUniformBuffer = new VkBuffer(256).zero();

    public final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH32F_STENCIL8);

    protected final boolean deferTranslucency;

    protected AbstractRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier syncCaptureSupplier, boolean deferTranslucency) {
        this.syncCaptureHasWork = syncCaptureSupplier;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
        this.deferTranslucency = deferTranslucency;
    }

    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {}

    public final void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer) {
        if (this.sectionRenderer != null) throw new IllegalStateException();
        this.sectionRenderer = sectionRenderer;
    }

    public void preSetup(Viewport<?> viewport) {
    }

    protected abstract long setup(Viewport<?> viewport, Framebuffer sourceFramebuffer, int srcWidth, int srcHeight);
    protected abstract void postOpaquePreTranslucent(Viewport<?> viewport);

    protected void finish(Viewport<?> viewport, Framebuffer sourceFrameBuffer, int srcWidth, int srcHeight) {
        VkState.setStencilTest(false);
        VkFramebuffer.rebindExternal(sourceFrameBuffer);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void runPipeline(Viewport<?> viewport, Framebuffer sourceFrameBuffer, int srcWidth, int srcHeight) {
        TimingStatistics.E.start();
        long depthTexture = this.setup(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        TimingStatistics.E.stop();

        var rs = ((AbstractSectionRenderer)this.sectionRenderer);
        TimingStatistics.G.start();
        rs.renderOpaque(viewport);
        TimingStatistics.G.stop();
        GPUTiming.INSTANCE.marker("traverse"); // opaque pass ended

        var occlusionDebug = VoxelClient.getOcclusionDebugState();
        if (occlusionDebug==0) {
            this.innerPrimaryWork(viewport, depthTexture);
        }
        if (occlusionDebug<=1) {
            rs.buildDrawCalls(viewport);
        }
        GPUTiming.INSTANCE.marker("temporal"); // traversal + cmdgen ended

        if (VoxelConfig.CONFIG.enableTemporalCatchup) {
            rs.renderTemporal(viewport);
        }
        GPUTiming.INSTANCE.marker("ssao"); // temporal catch-up ended

        this.postOpaquePreTranslucent(viewport);
        GPUTiming.INSTANCE.marker("translucent"); // SSAO / depth copy ended

        if (!this.deferTranslucency) {
            rs.renderTranslucent(viewport);
        }
        GPUTiming.INSTANCE.marker("blit"); // translucent pass ended

        TimingStatistics.I.start();
        this.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        TimingStatistics.I.stop();
        GPUTiming.INSTANCE.marker("end"); // final blit ended
        VkFramebuffer.rebindExternal(sourceFrameBuffer);
    }

    public void bindTranslucentSamplers(AutoBindingShader shader, Viewport<?> viewport) {
    }

    protected void finishTranslucent(Viewport<?> viewport, Framebuffer sourceFrameBuffer, int srcWidth, int srcHeight) {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void runTranslucentPipeline(Viewport<?> viewport, Framebuffer sourceFrameBuffer, int srcWidth, int srcHeight) {
        if (!this.deferTranslucency) {
            return;
        }
        var rs = ((AbstractSectionRenderer) this.sectionRenderer);
        rs.renderTranslucent(viewport);
        this.finishTranslucent(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        VkFramebuffer.rebindExternal(sourceFrameBuffer);
    }

    protected void initDepthStencil(Framebuffer sourceFrameBuffer, VkFramebuffer targetFb, int srcWidth, int srcHeight, int width, int height) {
        this.initDepthStencil(null, sourceFrameBuffer, targetFb, srcWidth, srcHeight, width, height);
    }

    protected void initDepthStencil(Viewport<?> viewport, Framebuffer sourceFrameBuffer, VkFramebuffer targetFb, int srcWidth, int srcHeight, int width, int height) {
        if (targetFb.depthImage() != null) {
            targetFb.depthImage().clearDepthStencil(1.0f, 1);
        }
        VulkanImage srcDepth = sourceFrameBuffer != null ? sourceFrameBuffer.getDepthAttachment() : null;
        if (srcDepth != null && srcDepth.getId() != 0L) {
            if (srcDepth.getCurrentLayout() != org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                var cb = VkVoxel.commandBufferOrNull();
                if (cb != null) {
                    VkVoxel.ensureOutsideRenderPass();
                    try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                        srcDepth.transitionImageLayout(stack, cb, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    }
                }
            }
            targetFb.bindRenderTarget();

            var mc = net.minecraft.client.Minecraft.getInstance();
            boolean cameraDetached = viewport != null && mc.player != null && mc.gameRenderer != null
                    && (mc.getCameraEntity() != mc.player
                    || mc.gameRenderer.getMainCamera().position().distanceToSqr(mc.player.position()) > 16.0);
            float depthScale = 0.0f;
            float depthBias = 0.0f;
            if (cameraDetached) {
                float m32v = viewport.vanillaProjection.m32();
                if (Math.abs(m32v) > 1e-6f) {
                    depthScale = viewport.projection.m32() / m32v;
                    depthBias = depthScale * viewport.vanillaProjection.m22() - viewport.projection.m22();
                }
            }

            this.depthCopy.shader()
                    .image(0, srcDepth)
                    .pushFloat4(0, ((float) width) / srcWidth, ((float) height) / srcHeight, depthScale, depthBias);
            VkState.setColorMask(false, false, false, false);
            VkState.setDepthTest(true);
            VkState.setDepthWrite(true);
            VkState.setDepthFunc(GL_ALWAYS);
            VkState.setStencilTest(true);
            VkState.setStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
            VkState.setStencilFunc(GL_ALWAYS, cameraDetached ? 1 : 0, 0xFF);
            VkState.setStencilMask(0xFF);
            this.depthCopy.blit();
        } else {
            targetFb.bindRenderTarget();
            VkState.setStencilTest(true);
            VkState.setStencilMask(0xFF);
        }

        VkState.setDepthWrite(true);
        VkState.setDepthTest(false);
        VkState.setDepthFunc(GL_LEQUAL);
        VkState.setColorMask(true, true, true, true);

        //Make Voxel terrain render only where there isnt mc terrain
        VkState.setStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        VkState.setStencilFunc(GL_EQUAL, 1, 0xFF);
    }

    protected void transformBlitDepth(FullscreenBlit blitShader, VkTexture srcDepthTex, Framebuffer dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
        if (dstFB == null || dstFB.getDepthAttachment() == null) {
            // Colour-only external target: nothing to blit the voxel depth into.
            return;
        }
        VkState.setStencilTest(false);

        long ptr = UploadStream.INSTANCE.upload(this.blitUniformBuffer, 0, 128);
        new Matrix4f(viewport.MVP).invert().getToAddress(ptr);
        targetTransform.getToAddress(ptr + 64);
        UploadStream.INSTANCE.commit();

        VkFramebuffer.rebindExternal(dstFB);

        blitShader.shader()
                .texture(0, 0, srcDepthTex)
                .ubo(1, this.blitUniformBuffer);

        VkState.setDepthTest(true);
        VkState.setDepthWrite(true);
        VkState.setDepthFunc(GL_LEQUAL);
        blitShader.blit();
        VkState.setStencilTest(false);
        VkState.setDepthTest(false);
    }

    protected void innerPrimaryWork(Viewport<?> viewport, long depthBuffer) {
        viewport.hiZBuffer.buildMipChain(this.fb.getDepthTex(), viewport.width, viewport.height);

        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);

            this.nodeCleaner.tick(this.traversal.getNodeBuffer());

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            VkVoxel.computeBarrier();

            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
        } while (this.syncCaptureHasWork.getAsBoolean());
    }

    @Override
    protected void free0() {
        this.fb.free();
        this.blitUniformBuffer.free();
        if (this.sectionRenderer != null) {
            this.sectionRenderer.free();
        }
        this.depthCopy.delete();
        super.free0();
    }

    public void addDebug(List<String> debug) {
        this.sectionRenderer.addDebug(debug);
        RenderStatistics.addDebug(debug);
    }

    public abstract void setupAndBindOpaque(Viewport<?> viewport);
    public abstract void setupAndBindTranslucent(Viewport<?> viewport);

    public void bindUniforms() {
        this.bindUniforms(-1);
    }

    public void bindUniforms(int index) {
    }

    public String taaFunction(String functionName) {
        return this.taaFunction(-1, functionName);
    }

    //AUDIT-DEAD: always null — the whole TAA chain (taaShift injection,
    // TAA_PATCH define, taaOffset uniform write) is dormant until a real
    // implementation lands.
    public String taaFunction(int uboBindingPoint, String functionName) {
        return null;
    }

    //AUDIT-DEAD: always null — the PATCHED_SHADER shader branches
    // (quads.frag/quad_util.glsl) are unreachable until a resource-pack
    // shader-patch mechanism lands.
    public String patchOpaqueShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //AUDIT-DEAD: same dormant stub as patchOpaqueShader.
    public String patchTranslucentShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    /**
     * LOD render target scale. The far LOD is rendered at this fraction of
     * the window and composited with an upscaling blit; vanilla chunks stay at
     * full resolution. 1.0 disables the scale entirely.
     */
    public float[] getRenderScalingFactor() {
        float scale = Math.clamp(VoxelConfig.CONFIG.lodRenderScale, 0.5f, 1.0f);
        return scale >= 0.999f ? null : new float[]{scale, scale};
    }
}
