package voxel.client.core;

import voxel.client.RenderStatistics;
import voxel.client.TimingStatistics;
import voxel.client.VoxelClient;
import voxel.client.config.VoxelConfig;
import voxel.client.core.vk.Capabilities;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkFramebuffer;
import voxel.client.core.vk.VkState;
import voxel.client.core.vk.VkTexture;
import voxel.client.core.model.ModelBakerySubsystem;
import voxel.client.core.rendering.RenderDistanceTracker;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.ViewportSelector;
import voxel.client.core.rendering.bounding.BoundRenderer;
import voxel.client.core.rendering.bounding.ChunkBoundStore;
import voxel.client.core.rendering.building.RenderGenerationService;
import voxel.client.core.rendering.hierarchical.AsyncNodeManager;
import voxel.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import voxel.client.core.rendering.hierarchical.NodeCleaner;
import voxel.client.core.rendering.section.IUsesMeshlets;
import voxel.client.core.rendering.section.backend.AbstractSectionRenderer;
import voxel.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import voxel.client.core.rendering.section.geometry.BasicSectionGeometryData;
import voxel.client.core.rendering.section.geometry.IGeometryData;
import voxel.client.core.rendering.util.DownloadStream;
import voxel.client.core.rendering.util.PrintfDebugUtil;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.core.util.GPUTiming;
import voxel.client.core.util.BerylUtil;
import voxel.client.vk.VkVoxel;
import voxel.common.Logger;
import voxel.common.thread.ServiceManager;
import voxel.common.world.WorldEngine;
import voxel.commonimpl.VoxelCommon;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.List;

public class VoxelRenderSystem {
    private long lastStatsDump;
    private final WorldEngine worldIn;

    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    private final RenderDistanceTracker renderDistanceTracker;
    public final ChunkBoundStore boundStore;
    private final BoundRenderer boundRenderer;

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;
    private final AbstractSectionRenderer<?, ?> sectionRenderer;

    //LOD casters in Beryl's shadow pass
    private boolean shadowCastersFailed;

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        return MDICSectionRenderer.FACTORY;
    }

    public VoxelRenderSystem(WorldEngine world, ServiceManager sm) {
        world.acquireRef();
        System.gc();

        if (Minecraft.getInstance().options.getEffectiveRenderDistance()<3) {
            Logger.warn("Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more");
        }

        try {
            VkVoxel.waitIdle();

            this.worldIn = world;

            //Wire renderStatistics before any shader compiles below —
            // HAS_STATISTICS/STATISTICS_BUFFER_BINDING are baked in as defines.
            RenderStatistics.enabled = VoxelConfig.CONFIG.renderStatistics;

            long geometryCapacity = getGeometryBufferSize();
            var backendFactory = getRenderBackendFactory();

            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

                this.geometryData = new BasicSectionGeometryData(1 << 20, geometryCapacity);

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.nodeManager, this.nodeCleaner, this.traversal, this::syncCaptureHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);
            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.sectionRenderer = sectionRenderer;
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                var level = (net.minecraft.world.level.Level)Minecraft.getInstance().level;
                int minSec = level.getMinSectionY() >> 5;
                int maxSec = (level.getMaxSectionY() - 1) >> 5;

                if (VoxelCommon.IS_MINE_IN_ABYSS) {
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(20,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxelConfig.CONFIG.sectionRenderDistance);
            }

            this.boundStore = new ChunkBoundStore();
            this.boundRenderer = new BoundRenderer(this.pipeline);

            // Start the async worker only after every allocation/shader compile
            // succeeded, so a failed reload cannot leave a thread running
            // against a world that is about to be released.
            this.nodeManager.start();

            Logger.info("Voxel render system created with " + geometryCapacity + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();
            throw e;
        }
    }

    public Viewport<?> setupViewport(Matrix4fc projectionMatrix, Matrix4fc modelViewMatrix, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null || Minecraft.getInstance().level == null || Minecraft.getInstance().player == null) {
            return null;
        }

        if (VoxelCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;
            cameraY += (16+(256-32-sector*30))*16;
        }

        var projection = computeProjectionMat(projectionMatrix);

        Framebuffer boundFb = Renderer.getInstance().getBoundFramebuffer();
        int width = boundFb != null ? boundFb.getWidth() : Minecraft.getInstance().getWindow().getWidth();
        int height = boundFb != null ? boundFb.getHeight() : Minecraft.getInstance().getWindow().getHeight();

        {
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }

        viewport
                .setVanillaProjection(projectionMatrix)
                .setProjection(projection)
                .setModelView(new Matrix4f(modelViewMatrix))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .update();

        if (VoxelClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }

        return viewport;
    }

    public void renderOpaque(Viewport<?> viewport) {
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0 || BerylUtil.berylShadowActive()) {
            return;
        }

        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker("opaque");
        TimingStatistics.main.start();

        Framebuffer boundFB = VkFramebuffer.captureExternalFramebuffer();
        if (boundFB == null) {
            boundFB = Renderer.getInstance().getSwapChain();
        }
        int srcWidth = boundFB.getWidth();
        int srcHeight = boundFB.getHeight();

        Renderer.setViewport(0, 0, viewport.width, viewport.height);

        this.pipeline.preSetup(viewport);

        if (!VoxelClient.disableVanillaChunkRender()) {
            this.boundRenderer.render(viewport, this.boundStore);
        } else {
            viewport.depthBoundingBuffer.clear(0);
        }

        this.pipeline.runPipeline(viewport, boundFB, srcWidth, srcHeight);
        GPUTiming.INSTANCE.marker("rebind");
        GPUTiming.INSTANCE.tick();

        TimingStatistics.main.stop();

        VkFramebuffer.rebindExternal(boundFB);
        Renderer.setViewport(0, 0, srcWidth, srcHeight);
        VkState.setDepthTest(true);
        BerylUtil.clearBerylSamplers();
    }

    /**
     * Inside Beryl's shadow render pass, after its terrain layers: the LOD terrain casts into the map.
     *
     * <p>Always casts the LOD geometry. The old section mask assumed that every compiled vanilla
     * section had already been drawn into Beryl's map, but the mask is section-level: it also
     * covers air blocks, merged (level &gt; 0) cells whose shell lies over vanilla air, and
     * sections whose mesh no longer matches the LOD data. In those places the vanilla pass has no
     * caster and the mask still suppressed the LOD one. Casting all LOD quads fills the holes;
     * duplicate casters over vanilla geometry write the same depth, so they have no visual cost.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void renderBerylShadowCasters() {
        if (this.shadowCastersFailed || VoxelCommon.IS_MINE_IN_ABYSS
                || !VoxelConfig.CONFIG.enableDistantShaderShadows
                || !BerylUtil.isShadowMapActive() || VkFramebuffer.current() != null) {
            return;
        }
        Viewport<?> viewport = this.viewportSelector.getDefaultViewport();
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
            return;
        }
        try {
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
            Matrix4f lightSpace = BerylUtil.getLightSpaceMatrix(new Matrix4f());
            GPUTiming.INSTANCE.marker("shadow");
            ((AbstractSectionRenderer) this.sectionRenderer).renderShadowCasters(viewport, lightSpace,
                    camera.x, camera.y, camera.z);
            GPUTiming.INSTANCE.marker("postShadow");
        } catch (Throwable t) {
            this.shadowCastersFailed = true;
            Logger.error("LOD shadow casters disabled", t);
        }
    }

    public void renderTranslucent(Viewport<?> viewport) {
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0 || BerylUtil.berylShadowActive()) {
            return;
        }

        TimingStatistics.main.start();
        Framebuffer boundFB = VkFramebuffer.captureExternalFramebuffer();
        if (boundFB == null) {
            boundFB = Renderer.getInstance().getSwapChain();
        }
        int srcWidth = boundFB.getWidth();
        int srcHeight = boundFB.getHeight();

        Renderer.setViewport(0, 0, viewport.width, viewport.height);
        this.pipeline.runTranslucentPipeline(viewport, boundFB, srcWidth, srcHeight);

        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();
        voxel.client.network.ClientLodStreamManager.tick();

        {
            UploadStream.INSTANCE.tick();

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxelClient.isSyncCaptureActive());
            TimingStatistics.H.start();
            do { this.modelService.tick(200_000); } while (VoxelClient.isSyncCaptureActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        VkFramebuffer.rebindExternal(boundFB);
        Renderer.setViewport(0, 0, srcWidth, srcHeight);
        VkState.setDepthTest(true);
        BerylUtil.clearBerylSamplers();

        TimingStatistics.all.stop();

        //Stats dump must run AFTER all.stop() — update() commits every
        // sampler and throws on one that is still open. Wrapped in
        // try/catch: diagnostics must never break the render frame.
        if (RenderStatistics.enabled) {
            long now = System.currentTimeMillis();
            if (now - this.lastStatsDump > 2000) {
                this.lastStatsDump = now;
                try {
                    var lines = new java.util.ArrayList<String>();
                    this.addDebugInfo(lines);
                    Logger.info("[RenderStats] " + String.join(" | ", lines));
                } catch (Throwable t) {
                    Logger.error("RenderStats dump failed", t);
                }
            }
        }
    }

    //AUDIT-DEAD: never called — intentionally so. Its floor is 28 px, the
    // exact value that makes the traversal walk down to LOD 0 everywhere. On a
    // GPU that holds 65+ FPS it would drive subDivisionSize back to 28 and
    // undo VoxelConfig.validate(); only enable it with the floor raised to the
    // config default (64).
    private void autoBalanceSubDivSize() {
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxelConfig.CONFIG.subDivisionSize = Math.min(VoxelConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxelConfig.CONFIG.subDivisionSize = Math.max(VoxelConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28);
        }
    }

    private static Matrix4f computeProjectionMat(Matrix4fc base) {
        var mc = Minecraft.getInstance();
        float near = mc.gameRenderer.getRenderDistance() <= 32.0f ? 8.0f : 16.0f;
        boolean cameraDetached = mc.player == null
                || mc.getCameraEntity() != mc.player
                || mc.gameRenderer.getMainCamera().position().distanceToSqr(mc.player.position()) > 16.0;
        if (VoxelClient.disableVanillaChunkRender() || cameraDetached) {
            // Keep near >= 1.5f (and >= 4.0f when Zoomify is zooming in) to preserve high
            // far-field depth precision while still allowing close-up Freecam inspection.
            near = Math.abs(base.m11()) > 2.5f ? 4.0f : 1.5f;
        }
        float far = 16.0f * 3000.0f;

        // Preserve base projection's exact dynamic FOV (including Zoomify / spyglass / view bobbing)
        // and use Vulkan's [0, 1] depth range (zZeroToOne = true).
        return new Matrix4f(base)
                .m22(far / (near - far))
                .m32((far * near) / (near - far));
    }

    private boolean syncCaptureHasWork() {
        if (!VoxelClient.isSyncCaptureActive()) {
            return false;
        }
        UploadStream.INSTANCE.tick();
        this.modelService.tick(100_000_000);
        VkVoxel.waitIdle();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount()!=0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistanceTracker.setRenderDistance(renderDistance);
    }

    public Viewport<?> getViewport() {
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("SDS: " + VoxelConfig.CONFIG.subDivisionSize
                + " RS: " + VoxelConfig.CONFIG.lodRenderScale
                + " TC: " + VoxelConfig.CONFIG.enableTemporalCatchup);
        debug.add("Buf/Tex [#/Mb]: [" + VkBuffer.getCount() + "/" + (VkBuffer.getTotalSize()/1_000_000) + "],[" + VkTexture.getCount() + "/" + (VkTexture.getEstimatedTotalSize()/1_000_000)+"]");
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxel frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: setup=" + TimingStatistics.E.pVal() + ", traverse=" + TimingStatistics.F.pVal() + ", opaque=" + TimingStatistics.G.pVal() + ", model=" + TimingStatistics.H.pVal() + ", blit=" + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    /**
     * Makes sure the GPU is not reading/recording any LOD resource before it gets destroyed.
     *
     * <p>VulkanMod records a frame into a command buffer and submits it from a later
     * {@code Renderer.beginFrame()/endFrame()} pair. Destroying our Vulkan images/buffers while
     * such a frame is still recorded (world quit happens in the middle of a frame, and the saving
     * screen then runs a nested tick that ends that frame) makes the next {@code vkQueueSubmit}
     * crash inside the driver ({@code nvoglv64.dll}, EXCEPTION_ACCESS_VIOLATION). Submitting the
     * current recording and waiting for the device to go idle closes that window.</p>
     */
    private static void flushFrameAndWaitIdleForTeardown() {
        try {
            Renderer renderer = Renderer.getInstance();
            if (renderer != null) {
                renderer.flushCmds();
            }
        } catch (Throwable t) {
            Logger.warn("Voxel: could not flush the pending frame before teardown: " + t);
        }
        try {
            VkVoxel.waitIdle();
        } catch (Throwable t) {
            Logger.warn("Voxel: vkDeviceWaitIdle before teardown failed: " + t);
        }
    }

    public void shutdown() {
        flushFrameAndWaitIdleForTeardown();
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();

            this.geometryData.free();
            this.boundRenderer.free();
            this.boundStore.free();

            this.viewportSelector.free();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}

        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        // Renderer reloads (Beryl shader-pipeline toggle / options apply) tear
        // this system down and immediately build a new one. VkVoxelBuffer.free()
        // only queues the VMA destruction for a later frame, so without draining
        // the queue the new renderer tries to allocate two fresh 1GB buffers
        // while the old two are still alive -> VK_ERROR_OUT_OF_DEVICE_MEMORY.
        try {
            VkVoxel.waitIdle();
            MemoryManager memoryManager = MemoryManager.getInstance();
            if (memoryManager != null) {
                memoryManager.freeAllBuffers();
            }
        } catch (Throwable t) {
            Logger.warn("Voxel: could not drain deferred GPU frees during teardown: " + t);
        }

        GPUTiming.INSTANCE.free();

        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    private static long getGeometryBufferSize() {
        if (VoxelConfig.CONFIG.geometryBufferSizeMB > 0) {
            return VoxelConfig.CONFIG.geometryBufferSizeMB * 1024L * 1024L;
        }
        var override = System.getProperty("voxel.geometryBufferSizeOverrideMB", "");
        if (!override.isEmpty()) {
            return Long.parseLong(override) * 1024L * 1024L;
        }

        long targetMB = 1024L;
        if (Capabilities.INSTANCE.canQueryGpuMemory && Capabilities.INSTANCE.totalDedicatedMemory > 0) {
            long totalVramMB = Capabilities.INSTANCE.totalDedicatedMemory / (1024 * 1024);
            if (totalVramMB <= 4096) {
                targetMB = 512L;
            } else if (totalVramMB <= 6144) {
                targetMB = 768L;
            } else if (totalVramMB <= 8192) {
                targetMB = 1024L;
            } else {
                targetMB = 1536L;
            }
        }
        return targetMB * 1024L * 1024L;
    }

    /** True when this system currently draws through Beryl's shader pipeline. */
    public boolean isBerylPipeline() {
        return this.pipeline instanceof BerylVoxelRenderPipeline;
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }

    public AbstractRenderPipeline getPipeline() {
        return this.pipeline;
    }
}
