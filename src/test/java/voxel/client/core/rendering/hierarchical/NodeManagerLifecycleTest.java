package voxel.client.core.rendering.hierarchical;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import voxel.client.core.rendering.ISectionWatcher;
import voxel.client.core.rendering.building.BuiltSection;
import voxel.client.core.rendering.section.geometry.IGeometryManager;
import voxel.common.world.WorldEngine;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GPU-free lifecycle tests for the node state machine.
 *
 * <p>The original bug: a top-level section that is empty is stored as a leaf
 * with child existence 0. When it later becomes non-empty there is no parent
 * request to create the child nodes, so the leaf keeps a non-zero child mask
 * with a null child pointer and never expands. The tests drive the same
 * callbacks the renderer uses and assert the node is a proper inner node once
 * a child arrives.</p>
 */
class NodeManagerLifecycleTest {

    private static final long TOP_POS =
            WorldEngine.getWorldSectionId(WorldEngine.MAX_LOD_LAYER, 0, 0, 0);

    private TestWatcher watcher;
    private TestGeometryManager geometry;
    private NodeManager manager;

    @BeforeEach
    void setUp() {
        this.watcher = new TestWatcher();
        this.geometry = new TestGeometryManager();
        this.manager = new NodeManager(1 << 12, this.geometry, this.watcher);
    }

    @Test
    void emptyTopLevelNodeExpandsWhenAChildAppears() {
        this.manager.insertTopLevelNode(TOP_POS);
        this.manager.processGeometryResult(BuiltSection.empty(TOP_POS));
        this.manager.verifyIntegrity();
        assertEquals(NodeManager.NODE_TYPE_LEAF, this.manager.nodeTypeAt(TOP_POS));

        this.manager.processChildChange(TOP_POS, (byte) 1);
        this.manager.verifyIntegrity();
        assertEquals(NodeManager.NODE_TYPE_LEAF, this.manager.nodeTypeAt(TOP_POS));
        assertEquals(1, this.manager.activeRequestCount());
        assertEquals(NodeManager.NODE_TYPE_REQUEST, this.manager.nodeTypeAt(child(0)));

        // The child arrives empty; the request completes and the top node
        // becomes an inner node with one child.
        this.manager.processGeometryResult(BuiltSection.empty(child(0)));
        this.manager.verifyIntegrity();
        assertEquals(NodeManager.NODE_TYPE_INNER, this.manager.nodeTypeAt(TOP_POS));
        assertEquals(0, this.manager.activeRequestCount());

        // The section becomes empty again: inner -> leaf, child removed.
        this.manager.processChildChange(TOP_POS, (byte) 0);
        this.manager.verifyIntegrity();
        assertEquals(NodeManager.NODE_TYPE_LEAF, this.manager.nodeTypeAt(TOP_POS));
        assertEquals(0, this.manager.activeRequestCount());

        // And non-empty once more: a fresh request must be created, not a
        // leaf with a dangling child mask.
        this.manager.processChildChange(TOP_POS, (byte) 1);
        this.manager.verifyIntegrity();
        assertEquals(1, this.manager.activeRequestCount());
        assertEquals(NodeManager.NODE_TYPE_REQUEST, this.manager.nodeTypeAt(child(0)));
    }

    @Test
    void emptyTopLevelLeafDoesNotKeepAnEmptyRequest() {
        this.manager.insertTopLevelNode(TOP_POS);
        this.manager.processGeometryResult(BuiltSection.empty(TOP_POS));
        this.manager.verifyIntegrity();

        // The traversal asks the empty leaf to expand. There is nothing to
        // request, so it must stay a request-free leaf instead of parking a
        // satisfied request that only a future child update can finish.
        this.manager.processRequest(TOP_POS);
        this.manager.verifyIntegrity();
        assertEquals(NodeManager.NODE_TYPE_LEAF, this.manager.nodeTypeAt(TOP_POS));
        assertEquals(0, this.manager.activeRequestCount());
    }

    @Test
    void childMaskArrivingBeforeTheInitialMeshIsExpandedLater() {
        this.manager.insertTopLevelNode(TOP_POS);
        this.manager.processChildChange(TOP_POS, (byte) 1);
        this.manager.processGeometryResult(BuiltSection.emptyWithChildren(TOP_POS, (byte) 1));
        this.manager.verifyIntegrity();
        assertEquals(NodeManager.NODE_TYPE_LEAF, this.manager.nodeTypeAt(TOP_POS));
        assertEquals(0, this.manager.activeRequestCount());

        this.manager.processRequest(TOP_POS);
        this.manager.verifyIntegrity();
        assertEquals(1, this.manager.activeRequestCount());
        assertEquals(NodeManager.NODE_TYPE_REQUEST, this.manager.nodeTypeAt(child(0)));
    }

    @Test
    void repeatEmptyUpdatesStayStable() {
        this.manager.insertTopLevelNode(TOP_POS);
        this.manager.processGeometryResult(BuiltSection.empty(TOP_POS));

        for (int i = 0; i < 16; i++) {
            this.manager.processChildChange(TOP_POS, (byte) 0);
            this.manager.processRequest(TOP_POS);
            this.manager.verifyIntegrity();
            assertEquals(NodeManager.NODE_TYPE_LEAF, this.manager.nodeTypeAt(TOP_POS));
            assertEquals(0, this.manager.activeRequestCount());
        }
    }

    private static long child(int index) {
        return WorldEngine.getWorldSectionId(WorldEngine.MAX_LOD_LAYER - 1,
                index & 1, (index >> 2) & 1, (index >> 1) & 1);
    }

    private static final class TestWatcher implements ISectionWatcher {
        private final Long2IntOpenHashMap flags = new Long2IntOpenHashMap();

        @Override
        public boolean watch(long position, int types) {
            int old = this.flags.get(position);
            if ((old & types) == types) {
                return false;
            }
            this.flags.put(position, old | types);
            return true;
        }

        @Override
        public boolean unwatch(long position, int types) {
            int old = this.flags.get(position);
            if ((old & types) == 0) {
                return false;
            }
            int now = old & ~types;
            if (now == 0) {
                this.flags.remove(position);
            } else {
                this.flags.put(position, now);
            }
            return true;
        }

        @Override
        public int get(long position) {
            return this.flags.get(position);
        }
    }

    private static final class TestGeometryManager implements IGeometryManager {
        private int nextId = 1;

        @Override
        public int uploadSection(BuiltSection section) {
            section.free();
            return this.nextId++;
        }

        @Override
        public int uploadReplaceSection(int oldId, BuiltSection section) {
            section.free();
            return this.nextId++;
        }

        @Override
        public void removeSection(int id) {
        }

        @Override
        public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
        }
    }
}
