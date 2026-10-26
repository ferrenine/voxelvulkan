package voxel.client.core.rendering.hierarchical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import voxel.client.RenderStatistics;
import voxel.client.config.VoxelConfig;
import voxel.client.core.vk.Capabilities;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.building.RenderGenerationService;
import voxel.client.core.rendering.util.DownloadStream;
import voxel.client.core.rendering.util.PrintfDebugUtil;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.vk.VkVoxel;
import voxel.common.Logger;
import voxel.common.util.MemoryBuffer;
import voxel.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import static voxel.client.core.rendering.util.PrintfDebugUtil.PRINTF_processor;

// TODO: swap to persistent gpu threads instead of dispatching MAX_ITERATIONS of compute layers
public class HierarchicalOcclusionTraverser {
    public static final boolean HIERARCHICAL_SHADER_DEBUG = System.getProperty("voxel.hierarchicalShaderDebug", "false").equals("true");

    public static final int MAX_REQUEST_QUEUE_SIZE = 1024;
    public static final int MAX_QUEUE_SIZE = 200_000;


    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER+1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final RenderGenerationService meshGen;

    private final VkBuffer requestBuffer;

    private final VkBuffer nodeBuffer;
    private final VkBuffer uniformBuffer = new VkBuffer(1024).zero();
    private final VkBuffer statisticsBuffer = new VkBuffer(1024).zero();


    private int topNodeCount;
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();//Used to store mapping from TLN to array index
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];//Used to map idx to TLN id
    private final VkBuffer topNodeIds = new VkBuffer(MAX_QUEUE_SIZE*4).zero();
    private final VkBuffer queueMetaBuffer = new VkBuffer(4*4*MAX_ITERATIONS).zero();
    private final VkBuffer scratchQueueA = new VkBuffer(MAX_QUEUE_SIZE*4).zero();
    private final VkBuffer scratchQueueB = new VkBuffer(MAX_QUEUE_SIZE*4).zero();

    private static int BINDING_COUNTER = 1;
    private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
    private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
    //AUDIT-DEAD: allocated a binding slot but no shader consumes it — kept to
    // preserve the BINDING_COUNTER numbering of everything below.
    private static final int NODE_QUEUE_INDEX_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
    private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;
    private static final int STATISTICS_BUFFER_BINDING = BINDING_COUNTER++;

    private final AutoBindingShader traversal = Shader.makeAuto(PRINTF_processor)
            .defineIf("DEBUG", HIERARCHICAL_SHADER_DEBUG)
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", LOCAL_WORK_SIZE_BITS)
            .define("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE)

            .define("HIZ_BINDING", 0)

            .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
            .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
            .define("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
            .define("NODE_DATA_BINDING", NODE_DATA_BINDING)

            .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
            .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
            .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
            .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)

            .define("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)

            .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
            .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)
            .defineIf("USE_SUBGROUPS", Capabilities.INSTANCE.subgroup)

            .add(ShaderType.COMPUTE, "voxel:lod/hierarchical/traversal_dev.comp")
            .compile();


    public HierarchicalOcclusionTraverser(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, RenderGenerationService meshGen) {
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.meshGen = meshGen;
        this.requestBuffer = new VkBuffer(MAX_REQUEST_QUEUE_SIZE*8L+8).zero();
        this.nodeBuffer = new VkBuffer(nodeManager.maxNodeCount*16L).fill(-1);

        this.traversal
                .ubo("SCENE_UNIFORM_BINDING", this.uniformBuffer)
                .ssbo("REQUEST_QUEUE_BINDING", this.requestBuffer)
                .ssbo("NODE_DATA_BINDING", this.nodeBuffer)
                .ssbo("NODE_QUEUE_META_BINDING", this.queueMetaBuffer)
                .ssbo("RENDER_TRACKER_BINDING", this.nodeCleaner.visibilityBuffer)
                .ssboIf("STATISTICS_BUFFER_BINDING", this.statisticsBuffer);

        this.topNode2idxMapping.defaultReturnValue(-1);
        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);
    }

    private void addTLN(int id) {
        int aid = this.topNodeCount++;//Increment buffer
        if (this.topNodeCount > this.topNodeIds.size()/4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }

        //Add the new top level node to the queue
        this.topNodeIds.fillRange(aid * 4L, 4L, id);

        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    private void remTLN(int id) {
        //Remove id
        int idx = this.topNode2idxMapping.remove(id);
        //Decrement count
        this.topNodeCount--;
        if (idx == -1) {
            throw new IllegalStateException();
        }

        //Count has already been decremented so is an exact match
        //If we are at the end of the array we dont need to do anything
        if (idx == this.topNodeCount) {
            return;
        }

        //Move the entry at the end to the current index
        int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
        this.idx2topNodeMapping[idx] = endTLNId;//Set the old to the new
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1)
            throw new IllegalStateException();

        //Move it server side, from end to new idx
        this.topNodeIds.fillRange(idx * 4L, 4L, endTLNId);
    }

    private static void setFrustum(Viewport<?> viewport, long ptr) {
        for (int i = 0; i < 6; i++) {
            var plane = viewport.frustumPlanes[i];
            plane.getToAddress(ptr); ptr += 4*4;
        }
    }

    private void uploadUniform(Viewport<?> viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);

        viewport.MVP.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        //MemoryUtil.memPutFloat(ptr, viewport.width); ptr += 4;
        MemoryUtil.memPutInt(ptr, viewport.hiZBuffer.getPackedLevels()); ptr += 4;

        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        //MemoryUtil.memPutFloat(ptr, viewport.height); ptr += 4;

        // The viewport is already scaled down by lodRenderScale, so scale the
        // pixel threshold too; otherwise a 0.75 render target would silently
        // make every LOD band 1.33x more detailed.
        final float renderScale = Math.clamp(VoxelConfig.CONFIG.lodRenderScale, 0.5f, 1.0f);
        final float subDivision = VoxelConfig.CONFIG.subDivisionSize * renderScale;
        final float screenspaceAreaDecreasingSize = subDivision * subDivision;
        //Screen space size for descending
        MemoryUtil.memPutFloat(ptr, screenspaceAreaDecreasingSize / (viewport.width * viewport.height)); ptr += 4;

        setFrustum(viewport, ptr); ptr += 4*4*6;

        MemoryUtil.memPutInt(ptr, (int) (viewport.getRenderList().size()/4-1)); ptr += 4;

        //VisibilityId
        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId); ptr += 4;

        {
            final double TARGET_COUNT = 4000;//TODO: make this configurable, or at least dynamically computed based on throughput rate of mesh gen
            double iFillness = Math.max(0, (TARGET_COUNT - this.meshGen.getTaskCount()) / TARGET_COUNT);
            iFillness = Math.pow(iFillness, 2);
            final int requestSize = (int) Math.ceil(iFillness * MAX_REQUEST_QUEUE_SIZE);
            MemoryUtil.memPutInt(ptr, Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestSize)));ptr += 4;
        }
    }

    private void bindings(Viewport<?> viewport) {
        this.traversal
                .texture(0, 0, viewport.hiZBuffer.getHizTexture())
                .ssbo(RENDER_QUEUE_BINDING, viewport.getRenderList());
    }

    public void doTraversal(Viewport<?> viewport) {
        this.uploadUniform(viewport);
        //UploadStream.INSTANCE.commit(); //Done inside traversal

        this.bindings(viewport);
        PrintfDebugUtil.bind();

        if (RenderStatistics.enabled) {
            this.statisticsBuffer.zero();
        }

        //Clear the render output counter
        viewport.getRenderList().zeroRange(0, 4);

        //Traverse
        this.traverseInternal();

        this.downloadResetRequestQueue();

        if (RenderStatistics.enabled) {
            DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalTraversalCounts[i] = MemoryUtil.memGetInt(down.address+i*4L);
                }

                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalRenderSections[i] = MemoryUtil.memGetInt(down.address+MAX_ITERATIONS*4L+i*4L);
                }
            });
        }
    }

    private void traverseInternal() {
        int firstDispatchSize = (this.topNodeCount+(1<<LOCAL_WORK_SIZE_BITS)-1)>>LOCAL_WORK_SIZE_BITS;
        {//AUDIT-FIXME: queue meta buffer re-uploaded + cleared every frame as an
            // Intel workaround ("THIS IS BULLSHIT BY INTEL") — needs a proper clear path.
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*MAX_ITERATIONS);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }
            UploadStream.INSTANCE.commit();
        }

        //Execute first iteration
        this.traversal
                .pushUint(0, 0)
                .ssbo(NODE_QUEUE_SOURCE_BINDING, this.topNodeIds)
                .ssbo(NODE_QUEUE_SINK_BINDING, this.scratchQueueB);

        //Dont need to use indirect to dispatch the first iteration
        VkVoxel.computeBarrier();
        this.traversal.dispatch(firstDispatchSize, 1,1);

        //Dispatch max iterations
        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            this.traversal
                    .pushUint(0, iter)
                    .ssbo(NODE_QUEUE_SOURCE_BINDING, (iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB)
                    .ssbo(NODE_QUEUE_SINK_BINDING, (iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA);

            VkVoxel.computeBarrier();

            //Dispatch and barrier
            this.traversal.dispatchIndirect(this.queueMetaBuffer, iter * 4L * 4L);
        }

        VkVoxel.computeBarrier();
    }


    private void downloadResetRequestQueue() {
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        this.requestBuffer.zeroRange(0, 4);
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);ptr += 8;//its 8 since we need to skip the second value (which is empty)
        if (count < 0 || count > 50000) {
            Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
            return;
        }
        if (count > (this.requestBuffer.size()>>3)-1) {
            //This should not break the synchonization between gpu and cpu as in the traversal shader is
            // `if (atomRes < REQUEST_QUEUE_SIZE) {` which forcefully clamps to the request size

            //Logger.warn("Count over max buffer size, clamping, got count: " + count + ".");

            count = (int) ((this.requestBuffer.size()>>3)-1);

            //Write back the clamped count
            MemoryUtil.memPutInt(ptr-8, count);
        }
        //if (count > REQUEST_QUEUE_SIZE) {
        //    Logger.warn("Count larger than 'maxRequestCount', overflow captured. Overflowed by " + (count-REQUEST_QUEUE_SIZE));
        //}
        if (count != 0) {
            this.nodeManager.submitRequestBatch(new MemoryBuffer(count*8L+8).cpyFrom(ptr-8));// the -8 is because we incremented it by 8
        }
    }

    public VkBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public void free() {
        this.traversal.free();
        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.statisticsBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(32);//32 bytes of scratch memory
}
