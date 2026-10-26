package voxel.client.core.rendering.section.backend.mdic;


import voxel.client.RenderStatistics;
import voxel.client.VoxelClient;
import voxel.client.core.AbstractRenderPipeline;
import voxel.client.core.vk.Capabilities;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkState;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderLoader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.core.model.ModelStore;
import voxel.client.core.rendering.section.backend.AbstractSectionRenderer;
import voxel.client.core.rendering.section.geometry.BasicSectionGeometryData;
import voxel.client.core.rendering.util.DownloadStream;
import voxel.client.core.rendering.util.LightMapHelper;
import voxel.client.core.rendering.util.SharedIndexBuffer;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.core.util.BerylUtil;
import voxel.client.config.VoxelConfig;
import voxel.client.vk.VkVoxel;
import voxel.common.Logger;
import voxel.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.util.List;

import static org.lwjgl.opengl.GL11C.GL_LEQUAL;

/**
 * Uses MDIC (multi draw indirect count) to render the sections.
 *
 * <p>Vulkan port notes:</p>
 * <ul>
 *   <li>Draw commands are {@code VkDrawIndexedIndirectCommand}s; the GL layout
 *       written by the compute shaders has the same memory layout (5×32 bit),
 *       so the stride stays 20 bytes.</li>
 *   <li>All indices are uint16; the shared index buffer stores the cube index
 *       block at element offset {@code (1&lt;&lt;16)*6*2}, which is exactly what
 *       {@code prep.comp} writes as {@code firstIndex}.</li>
 *   <li>{@code glMemoryBarrier} calls become conservative full barriers.</li>
 *   <li>Vertex data is fetched from SSBOs, so no vertex input state / VAO is
 *       needed on the graphics pipelines.</li>
 * </ul>
 */
public class MDICSectionRenderer extends AbstractSectionRenderer<MDICViewport, BasicSectionGeometryData> {
    public static final Factory<MDICViewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(MDICSectionRenderer.class);

    private static final int CMDGEN_WORKGROUP_SIZE = 64;
    private static final int TRANSLUCENT_OFFSET = 400_000;//in draw calls
    /** Command-region capacities inside MDICViewport.drawCallBuffer. */
    public static final int OPAQUE_CAPACITY = TRANSLUCENT_OFFSET;
    public static final int TRANSLUCENT_CAPACITY = 100_000;
    /** Translucent-section id slots in distanceCountBuffer (one id per section). */
    public static final int TRANSLUCENT_ID_CAPACITY = 100_000;
    private static final int STATISTICS_BUFFER_BINDING = 8;
    /** count, instanceCount, firstIndex, vertexOffset, firstInstance. */
    private static final int INDIRECT_COMMAND_SIZE = 5*4;

    private static boolean maxDrawClampLogged;

    /**
     * vkCmdDrawIndexedIndirectCount requires maxDrawCount <= the device limit.
     * cmdgen already clamps the count it writes, so lowering the read ceiling is
     * safe (it only truncates work this device cannot express).
     */
    private static int clampMaxDrawCount(int requested) {
        long limit = Capabilities.INSTANCE.maxDrawIndirectCount;
        if (limit <= 0L || requested <= limit) {
            return requested;
        }
        if (!maxDrawClampLogged) {
            maxDrawClampLogged = true;
            Logger.warn("Clamping maxDrawCount from " + requested + " to the device limit "
                    + limit + " (maxDrawIndirectCount)");
        }
        return (int) Math.min(Integer.MAX_VALUE, limit);
    }

    private final AutoBindingShader terrainShader;
    private final AutoBindingShader translucentTerrainShader;
    private final AutoBindingShader shadowCasterShader;

    private final AutoBindingShader commandGenShader = Shader.makeAuto()
            .define("CMDGEN_WORKGROUP_SIZE", CMDGEN_WORKGROUP_SIZE)
            .define("TRANSLUCENT_WRITE_BASE", 1024)
            .define("OPAQUE_CAPACITY", OPAQUE_CAPACITY)
            .define("TRANSLUCENT_ID_CAPACITY", TRANSLUCENT_ID_CAPACITY)

            .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)

            .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
            .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)
            // Use hardware SSBO atomicAdd for 100% uniform stability across all GPU architectures
            // .defineIf("USE_SUBGROUPS", Capabilities.INSTANCE.subgroup)

            .add(ShaderType.COMPUTE, "voxel:lod/vk/cmdgen.comp")
            .compile();

    private final AutoBindingShader prepShader = Shader.makeAuto()
            .define("CMDGEN_WORKGROUP_SIZE", CMDGEN_WORKGROUP_SIZE)
            .add(ShaderType.COMPUTE, "voxel:lod/vk/prep.comp")
            .compile();

    private final AutoBindingShader prefixSumShader = Shader.makeAuto()
            // inital3.comp has hard-coded 32/64-lane paths. Any other subgroup
            // size must use the shared-memory scan, or the warp offsets are wrong.
            .add(ShaderType.COMPUTE, useSubgroupPrefixSum()
                    ? "voxel:util/prefixsum/inital3.comp" : "voxel:util/prefixsum/simple.comp")
            .define("IO_BUFFER", 0)
            .compile();

    private static boolean useSubgroupPrefixSum() {
        int size = Capabilities.INSTANCE.subgroupSize;
        return Capabilities.INSTANCE.subgroup && (size == 32 || size == 64);
    }

    private final AutoBindingShader translucentGenShader = Shader.makeAuto()
            .define("CMDGEN_WORKGROUP_SIZE", CMDGEN_WORKGROUP_SIZE)
            .define("TRANSLUCENT_WRITE_BASE", 1024)//The size of the prefix sum array
            .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
            .define("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET)
            .define("TRANSLUCENT_CAPACITY", TRANSLUCENT_CAPACITY)
            .defineIf("USE_SUBGROUPS", Capabilities.INSTANCE.subgroup)
            .add(ShaderType.COMPUTE, "voxel:lod/vk/buildtranslucents.comp")
            .compile();

    private final VkBuffer uniform = new VkBuffer(1024).zero();//TODO move to viewport?
    private static long shadowSampler = 0L;
    private int shadowDebugFlagCounter;
    private boolean shadowDebugEnabled;

    //TODO: needs to be in the viewport, since it contains the compute indirect call/values
    private final VkBuffer distanceCountBuffer = new VkBuffer((1024L + TRANSLUCENT_ID_CAPACITY) * 4L).zero();//TODO move to viewport?

    //Statistics
    private final VkBuffer statisticsBuffer = new VkBuffer(1024).zero();

    private final AbstractRenderPipeline pipeline;
    public MDICSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(modelStore, geometryData);
        this.pipeline = pipeline;
        //The pipeline can be used to transform the renderer in abstract ways

        String vertex = ShaderLoader.parse("voxel:lod/vk/quads3.vert");
        String shadowVertex = vertex;//No TAA jitter in the shadow map
        String taa = pipeline.taaFunction("taaShift");
        if (taa != null) {
            vertex += "\n"+taa;//inject it at the end
        }
        var builder = Shader.makeAuto()
                .defineIf("TAA_PATCH", taa != null)
                .defineIf("DEBUG_RENDER", false)

                //.defineIf("USE_NV_BARRY", Capabilities.INSTANCE.nvBarryCoords)

                .addSource(ShaderType.VERTEX, vertex);

        String frag = ShaderLoader.parse("voxel:lod/vk/quads.frag");

        String opaqueFrag = pipeline.patchOpaqueShader(this, frag);
        opaqueFrag = opaqueFrag==null?frag:opaqueFrag;

        //TODO: find a more robust/nicer way todo this
        this.terrainShader = tryCompilePatchedOrNormal(builder, opaqueFrag, frag);

        String translucentFrag = pipeline.patchTranslucentShader(this, frag);
        translucentFrag = translucentFrag==null?frag:translucentFrag;

        this.translucentTerrainShader = tryCompilePatchedOrNormal(builder.define("TRANSLUCENT").defineIf("ENABLE_WATER_SSR", VoxelConfig.CONFIG.enableWaterSSR), translucentFrag, frag);

        this.shadowCasterShader = tryCompilePatchedOrNormal(Shader.makeAuto()
                .define("SHADOW_PASS")
                .addSource(ShaderType.VERTEX, shadowVertex), frag, frag);
    }

    /** Debug view of the shadow factor: enable by creating "shadowdebug.flag" in the working directory. */
    private boolean checkShadowDebugFlag() {
        if ((this.shadowDebugFlagCounter++ % 60) == 0) {
            this.shadowDebugEnabled = new File("shadowdebug.flag").exists();
        }
        return this.shadowDebugEnabled;
    }

    private void uploadUniformBuffer(MDICViewport viewport) {        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 320);
        long base = ptr;

        var mat = new Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        if (viewport.frameId<0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId&0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;
        // std140 padding where the removed curvature scalar used to live;
        // uBerylLightDir still starts at offset 96.
        ptr += 4;

        voxel.client.core.util.BerylUtil.writeBerylUniforms(ptr);

        Matrix4f lightSpace = BerylUtil.getLightSpaceMatrix(new Matrix4f());
        lightSpace.getToAddress(base + 176);
        Vector3f lightOffset = BerylUtil.getLightSpaceOffset(new Vector3f());
        lightOffset.getToAddress(base + 256);
        MemoryUtil.memPutFloat(base + 240, BerylUtil.getShadowBias());
        MemoryUtil.memPutFloat(base + 244, BerylUtil.getShadowDistortion());
        MemoryUtil.memPutFloat(base + 248, BerylUtil.getShadowTexelSize());
        MemoryUtil.memPutFloat(base + 252, BerylUtil.isShadowMapActive() ? 1.0f : 0.0f);

        // Live face shade: exactly what the near-field chunk builder bakes into
        // its vertex colours (Beryl forces 1.0 while its pipeline is active).
        var level = Minecraft.getInstance().level;
        MemoryUtil.memPutFloat(base + 268, level != null ? level.getShade(Direction.UP, false) : 1.0f);
        MemoryUtil.memPutFloat(base + 272, level != null ? level.getShade(Direction.UP, true) : 1.0f);
        MemoryUtil.memPutFloat(base + 276, level != null ? level.getShade(Direction.DOWN, true) : 0.5f);
        MemoryUtil.memPutFloat(base + 280, level != null ? level.getShade(Direction.NORTH, true) : 0.8f);
        MemoryUtil.memPutFloat(base + 284, level != null ? level.getShade(Direction.EAST, true) : 0.6f);
        // Debug: paint the shadow factor on the terrain (create "shadowdebug.flag" in the working dir)
        MemoryUtil.memPutFloat(base + 288, checkShadowDebugFlag() ? 1.0f : 0.0f);
        // std140 places this vec4 after the scalar above: 292 -> padded to 304.
        BerylUtil.writeBerylFogColor(base + 304);

        UploadStream.INSTANCE.commit();
    }


    private void bindRenderingBuffers(AutoBindingShader shader, MDICViewport viewport) {
        shader.ubo(0, this.uniform);
        shader.ssbo(1, this.geometryManager.getGeometryBuffer());
        shader.ssbo(12, this.geometryManager.getLightBuffer());
        this.modelStore.bind(shader, 3, 4, 6);
        shader.ssbo(5, viewport.positionScratchBuffer);
        shader.image(7, LightMapHelper.lightMapImage());
        shader.texture(8, 0, viewport.depthBoundingBuffer.getDepthTex());
        if (shadowSampler == 0L) {
            shadowSampler = SamplerManager.getSampler(false, false, 0);
        }
        VulkanImage shadowMap = BerylUtil.getShadowMapImage();
        VulkanImage shadowFallback = viewport.depthBoundingBuffer.getDepthTex().image().image;
        shader.imageSampled(11, shadowMap != null ? shadowMap : shadowFallback, shadowSampler);

        shader.indexBuffer(SharedIndexBuffer.INSTANCE.id(), Shader.INDEX_TYPE_UINT32);
    }

    private void renderTerrain(MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount) {
        VkState.setCullFace(false);
        VkState.setBlend(false);
        VkState.setDepthTest(true);

        this.pipeline.setupAndBindOpaque(viewport);
        this.bindRenderingBuffers(this.terrainShader, viewport);

        boolean wireframe = VoxelClient.getOcclusionDebugState()==3;
        if (wireframe) {
            VkState.setPolygonMode(true);
        }
        this.terrainShader.drawIndexedIndirectCount(viewport.drawCallBuffer, indirectOffset,
                viewport.drawCountCallBuffer, drawCountOffset, clampMaxDrawCount(maxDrawCount), INDIRECT_COMMAND_SIZE);
        if (wireframe) {
            VkState.setPolygonMode(false);
        }

        VkState.setCullFace(true);
    }

    @Override
    public void renderOpaque(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        this.uploadUniformBuffer(viewport);

        this.renderTerrain(viewport, 0, 4*3, Math.min((int)(this.geometryManager.getSectionCount()*4.4+128), OPAQUE_CAPACITY));
    }

    @Override
    public void renderTranslucent(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        VkState.setBlend(true);
        VkState.setBlendFuncSeparate(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA,
                org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);

        VkState.setCullFace(false);
        VkState.setDepthTest(true);

        this.pipeline.setupAndBindTranslucent(viewport);
        this.bindRenderingBuffers(this.translucentTerrainShader, viewport);
        this.pipeline.bindTranslucentSamplers(this.translucentTerrainShader, viewport);
        this.translucentTerrainShader.drawIndexedIndirectCount(viewport.drawCallBuffer, TRANSLUCENT_OFFSET*5L*4,
                viewport.drawCountCallBuffer, 4*4,
                clampMaxDrawCount(Math.min(this.geometryManager.getSectionCount(), TRANSLUCENT_CAPACITY)),
                INDIRECT_COMMAND_SIZE);

        VkState.setCullFace(true);
        VkState.setBlend(false);
    }

    @Override
    public void buildDrawCalls(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        {//Dispatch prep
            this.prepShader.ubo(0, this.uniform);
            this.prepShader.ssbo(1, viewport.drawCountCallBuffer);
            this.prepShader.ssbo(2, viewport.getRenderList());
            VkVoxel.computeBarrier();
            this.prepShader.dispatch(1,1,1);
            VkVoxel.computeBarrier();
        }

        {//1-Pass Compute Hi-Z Occlusion & Command Generation
            this.distanceCountBuffer.zeroRange(0, 1024*4);
            this.commandGenShader.ubo(0, this.uniform);
            this.commandGenShader.ssbo(1, viewport.drawCallBuffer);
            this.commandGenShader.ssbo(2, viewport.drawCountCallBuffer);
            this.commandGenShader.ssbo(3, this.geometryManager.getMetadataBuffer());
            this.commandGenShader.ssbo(4, viewport.visibilityBuffer);
            this.commandGenShader.ssbo(5, viewport.indirectLookupBuffer);
            this.commandGenShader.ssbo(6, viewport.positionScratchBuffer);
            this.commandGenShader.ssbo(7, this.distanceCountBuffer);
            this.commandGenShader.texture(9, 0, viewport.hiZBuffer.getHizTexture());

            if (RenderStatistics.enabled) {
                this.statisticsBuffer.zeroRange(0, 1024);
                this.commandGenShader.ssbo(STATISTICS_BUFFER_BINDING, this.statisticsBuffer);
            }

            VkVoxel.computeBarrier();
            this.commandGenShader.dispatchIndirect(viewport.drawCountCallBuffer, 0);
            VkVoxel.computeBarrier();

            if (RenderStatistics.enabled) {
                DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                    final int LAYERS = WorldEngine.MAX_LOD_LAYER+1;
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.visibleSections[i] = MemoryUtil.memGetInt(down.address+i*4L);
                    }

                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.quadCount[i] = MemoryUtil.memGetInt(down.address+LAYERS*4L+i*4L);
                    }
                });
            }
        }

        {//Do translucency sorting
            this.prefixSumShader.ssbo(0, this.distanceCountBuffer);
            this.prefixSumShader.dispatch(1,1,1);
            VkVoxel.computeBarrier();

            this.translucentGenShader.ubo(0, this.uniform);
            this.translucentGenShader.ssbo(1, viewport.drawCallBuffer);
            this.translucentGenShader.ssbo(2, viewport.drawCountCallBuffer);
            this.translucentGenShader.ssbo(3, this.geometryManager.getMetadataBuffer());
            this.translucentGenShader.ssbo(4, viewport.indirectLookupBuffer);
            this.translucentGenShader.ssbo(5, this.distanceCountBuffer);

            this.translucentGenShader.dispatchIndirect(viewport.drawCountCallBuffer, 0);
            VkVoxel.computeBarrier();
        }

    }

    @Override
    public void renderShadowCasters(MDICViewport viewport, Matrix4fc lightSpace,
                                    double cameraX, double cameraY, double cameraZ,
                                    float minCasterDistance) {
        if (this.geometryManager.getSectionCount() == 0) return;

        //This frame's camera, same base section convention as Viewport
        int bx = Mth.floor(cameraX) >> 5;
        int by = Mth.floor(cameraY) >> 5;
        int bz = Mth.floor(cameraZ) >> 5;
        float sx = (float) (cameraX - (bx << 5));
        float sy = (float) (cameraY - (by << 5));
        float sz = (float) (cameraZ - (bz << 5));
        Matrix4f mvp = new Matrix4f(lightSpace).translate(-sx, -sy, -sz);
        //Light-space units per block (the rows of the orthographic light matrix have length 1/halfExtent)
        float lightPerBlock = (float) Math.sqrt(lightSpace.m00() * lightSpace.m00()
                + lightSpace.m10() * lightSpace.m10() + lightSpace.m20() * lightSpace.m20());

        VkState.setCullFace(false);
        VkState.setBlend(false);
        VkState.setDepthTest(true);
        VkState.setDepthWrite(true);
        VkState.setDepthFunc(GL_LEQUAL);

        var shader = this.shadowCasterShader;
        shader.ubo(0, this.uniform);
        shader.ssbo(1, this.geometryManager.getGeometryBuffer());
        shader.ssbo(2, this.geometryManager.getMetadataBuffer());
        shader.ssbo(12, this.geometryManager.getLightBuffer());
        this.modelStore.bind(shader, 3, 4, 6);
        shader.ssbo(5, viewport.positionScratchBuffer);
        shader.image(7, LightMapHelper.lightMapImage());
        shader.texture(8, 0, viewport.depthBoundingBuffer.getDepthTex());
        if (shadowSampler == 0L) {
            shadowSampler = SamplerManager.getSampler(false, false, 0);
        }
        //Never the shadow map itself: it is the attachment being rendered
        shader.imageSampled(11, viewport.depthBoundingBuffer.getDepthTex().image().image, shadowSampler);
        shader.indexBuffer(SharedIndexBuffer.INSTANCE.id(), Shader.INDEX_TYPE_UINT32);

        shader.pushMat4(0, mvp)
                .pushUint(64, bx).pushUint(68, by).pushUint(72, bz).pushUint(76, 0)
                .pushFloat4(80, sx, sy, sz, 0.0f)
                .pushFloat4(96, BerylUtil.getShadowDistortion(), lightPerBlock,
                        minCasterDistance, 0.0f);

        //The opaque list this frame's main pass is about to draw (built last frame)
        shader.drawIndexedIndirectCount(viewport.drawCallBuffer, 0,
                viewport.drawCountCallBuffer, 4*3,
                clampMaxDrawCount(Math.min((int)(this.geometryManager.getSectionCount()*4.4+128), OPAQUE_CAPACITY)),
                INDIRECT_COMMAND_SIZE);

        //Beryl keeps drawing (entities) in this pass with VulkanMod's cached pipeline state
        VkVoxel.invalidateExternalPipelineCache();
    }

    @Override
    public void addDebug(List<String> lines) {
        super.addDebug(lines);
        //lines.add("SC/GS: " + this.geometryManager.getSectionCount() + "/" + (this.geometryManager.getGeometryUsed()/(1024*1024)));//section count/geometry size (MB)
    }

    @Override
    public MDICViewport createViewport() {
        return new MDICViewport(this.geometryManager.getMaxSectionCount());
    }

    @Override
    public void free() {
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.translucentTerrainShader.free();
        this.terrainShader.free();
        this.shadowCasterShader.free();
        this.commandGenShader.free();
        this.prepShader.free();
        this.translucentGenShader.free();
        this.prefixSumShader.free();
        this.statisticsBuffer.free();
    }
}
