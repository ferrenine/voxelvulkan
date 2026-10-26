package voxel.client.core.rendering.hierarchical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.core.rendering.util.DownloadStream;
import voxel.client.core.rendering.util.PrintfDebugUtil;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.vk.VkVoxel;
import org.lwjgl.system.MemoryUtil;

//Uses compute shaders to compute the last 256 rendered section (64x64 workgroup size maybe)
// done via warp level sort, then workgroup sort (shared memory), (/w sorting network)
// then use bubble sort (/w fast path going to middle or 2 subdivisions deep) the bubble it up
// can do incremental sorting pass aswell, so only scan and sort a rolling sector of sections
// (over a few frames to not cause lag, maybe)


//TODO : USE THIS IN HierarchicalOcclusionTraverser instead of other shit
public class NodeCleaner {
    //TODO: use batch_visibility_set to clear visibility data when nodes are removed!! (TODO: nodeManager will need to forward info to this)


    private static final int SORTING_WORKER_SIZE = 64;
    private static final int WORK_PER_THREAD = 8;
    static final int OUTPUT_COUNT = 256;


    private final AutoBindingShader sorter = Shader.makeAuto(PrintfDebugUtil.PRINTF_processor)
            .define("WORK_SIZE", SORTING_WORKER_SIZE)
            .define("ELEMS_PER_THREAD", WORK_PER_THREAD)
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("VISIBILITY_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .define("NODE_DATA_BINDING", 3)
            .add(ShaderType.COMPUTE, "voxel:lod/hierarchical/cleaner/sort_visibility.comp")
            .compile();

    private final AutoBindingShader resultTransformer = Shader.makeAuto()
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("MIN_ID_BUFFER_BINDING", 0)
            .define("NODE_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .define("VISIBILITY_BUFFER_BINDING", 3)
            .add(ShaderType.COMPUTE, "voxel:lod/hierarchical/cleaner/result_transformer.comp")
            .compile();

    private final AutoBindingShader batchClear = Shader.makeAuto()
            .define("VISIBILITY_BUFFER_BINDING", 0)
            .define("LIST_BUFFER_BINDING", 1)
            .add(ShaderType.COMPUTE, "voxel:lod/hierarchical/cleaner/batch_visibility_set.comp")
            .compile();


    final VkBuffer visibilityBuffer;
    private final VkBuffer outputBuffer = new VkBuffer(OUTPUT_COUNT*4+OUTPUT_COUNT*8);//Scratch + output

    private final AsyncNodeManager nodeManager;
    int visibilityId = 0;


    public NodeCleaner(AsyncNodeManager nodeManager) {
        this.nodeManager = nodeManager;
        this.visibilityBuffer = new VkBuffer(nodeManager.maxNodeCount*4L).zero();
        this.visibilityBuffer.fill(-1);

        this.batchClear
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer);

        this.sorter
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer)
                .ssbo("OUTPUT_BUFFER_BINDING", this.outputBuffer);

        /*
        this.nodeManager.setClear(new NodeManager.ICleaner() {
            @Override
            public void alloc(int id) {
                NodeCleaner.this.allocIds.add(id);
                NodeCleaner.this.freeIds.remove(id);
            }

            @Override
            public void move(int from, int to) {
                NodeCleaner.this.allocIds.remove(to);
                glCopyNamedBufferSubData(NodeCleaner.this.visibilityBuffer.id, NodeCleaner.this.visibilityBuffer.id, 4L*from, 4L*to, 4);
            }

            @Override
            public void free(int id) {
                NodeCleaner.this.freeIds.add(id);
                NodeCleaner.this.allocIds.remove(id);
            }
        });
         */
    }


    public void tick(VkBuffer nodeDataBuffer) {
        this.visibilityId++;
        if (this.shouldCleanGeometry()) {
            // Empty output slots are the one-past-the-end id, never an id that
            // NodeStore.allocate() can hand out (the old maxNodeCount-2 sentinel
            // was a real allocatable node, so padding could evict live geometry).
            this.outputBuffer.fill(this.nodeManager.maxNodeCount);//TODO: maybe dont set to zero??

            this.sorter.ssbo(3, nodeDataBuffer);
            this.sorter.pushUint(0, this.nodeManager.maxNodeCount);
            VkVoxel.computeBarrier();
            this.sorter.dispatch((this.nodeManager.getCurrentMaxNodeId() + (SORTING_WORKER_SIZE*WORK_PER_THREAD) - 1) / (SORTING_WORKER_SIZE*WORK_PER_THREAD), 1, 1);

            this.resultTransformer
                    .ssbo(0, this.outputBuffer, 0, 4 * OUTPUT_COUNT)
                    .ssbo(1, nodeDataBuffer)
                    .ssbo(2, this.outputBuffer, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT)
                    .ssbo(3, this.visibilityBuffer)
                    .pushUint(0, this.visibilityId)
                    .pushUint(4, this.nodeManager.maxNodeCount);

            VkVoxel.computeBarrier();
            this.resultTransformer.dispatch(1, 1, 1);
            VkVoxel.computeBarrier();

            DownloadStream.INSTANCE.download(this.outputBuffer, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT,
                    buffer -> this.nodeManager.submitRemoveBatch(buffer.copy())//Copy into buffer and emit to node manager
            );
        }
    }

    private boolean shouldCleanGeometry() {
        //(An alternative "used >75% of buffer" heuristic was removed as dead
        // code — see git history.)
        long remaining = this.nodeManager.getGeometryCapacity() - this.nodeManager.getUsedGeometryCapacity();
        return remaining < 256_000_000;//If less than 256 mb free memory
    }

    public void updateIds(IntOpenHashSet collection) {
        if (!collection.isEmpty()) {
            int count = collection.size();
            long addr = UploadStream.INSTANCE.rawUploadAddress(count * 4 + 16);//TODO ensure alignment, create method todo alignment things
            addr = (addr+15)&~15L;//Align to 16 bytes

            long ptr = UploadStream.INSTANCE.getBaseAddress() + addr;
            var iter = collection.iterator();
            while (iter.hasNext()) {
                MemoryUtil.memPutInt(ptr, iter.nextInt()); ptr+=4;
            }
            UploadStream.INSTANCE.commit();

            this.batchClear
                    .ssboRaw(1, UploadStream.INSTANCE.getRawBufferId(), addr, count*4L)
                    .pushUint(0, count)
                    .pushUint(4, this.visibilityId);
            VkVoxel.computeBarrier();
            this.batchClear.dispatch((count+127)/128, 1, 1);
            VkVoxel.computeBarrier();
        }
    }

    public void free() {
        this.sorter.free();
        this.visibilityBuffer.free();
        this.outputBuffer.free();
        this.batchClear.free();
        this.resultTransformer.free();
    }
}
