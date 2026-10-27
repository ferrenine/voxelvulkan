package voxel.client.core.rendering.bounding;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.lwjgl.system.MemoryUtil;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.core.vk.VkBuffer;
import voxel.common.Logger;

/**
 * Exact store of the currently loaded vanilla sections. Additions are applied
 * after the bound draw and removals before it, so the mask never references a
 * half-updated heap. This is the Vulkan port of Voxy's {@code ChunkBoundStore}.
 */
public class ChunkBoundStore implements IBoundStore {
    private static final int INIT_MAX_CHUNK_COUNT = 1 << 12;

    private VkBuffer chunkPosBuffer = new VkBuffer(INIT_MAX_CHUNK_COUNT * 8L);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
    private long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];

    private final LongOpenHashSet addQueue = new LongOpenHashSet();
    private final LongOpenHashSet remQueue = new LongOpenHashSet();
    private final VanillaSectionCoverage coverage = new VanillaSectionCoverage();

    public ChunkBoundStore() {
        this.chunk2idx.defaultReturnValue(-1);
    }

    /** Compiled vanilla sections, including empty ones, for LOD0 coverage tests. */
    public VanillaSectionCoverage coverage() {
        return this.coverage;
    }

    public void markCompiled(long sectionPos, boolean compiled) {
        this.coverage.markCompiled(sectionPos, compiled);
    }

    public void addSection(long pos) {
        if (!this.remQueue.remove(pos)) {
            this.addQueue.add(pos);
        }
    }

    public void removeSection(long pos) {
        if (!this.addQueue.remove(pos)) {
            this.remQueue.add(pos);
        }
    }

    @Override
    public void preRender(Viewport<?> viewport) {
        if (!this.remQueue.isEmpty()) {
            this.remQueue.forEach(this::removePosition);
            this.remQueue.clear();
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    public void postRender(Viewport<?> viewport) {
        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::addPosition);
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
    }

    private void removePosition(long pos) {
        int idx = this.chunk2idx.remove(pos);
        if (idx == -1) {
            Logger.warn("Chunk not in bound map: " + pos);
            return;
        }
        if (idx == this.chunk2idx.size()) {
            return;
        }
        if (this.idx2chunk[idx] != pos) {
            throw new IllegalStateException("Bound-store heap index mismatch");
        }

        long endPos = this.idx2chunk[this.chunk2idx.size()];
        if (this.chunk2idx.put(endPos, idx) == -1) {
            throw new IllegalStateException("Bound-store heap move failed");
        }
        this.idx2chunk[idx] = endPos;
        this.put(idx, endPos);
    }

    private void addPosition(long pos) {
        if (this.chunk2idx.containsKey(pos)) {
            Logger.warn("Chunk already in bound map: " + pos);
            return;
        }
        this.ensureSize();

        int idx = this.chunk2idx.size();
        this.chunk2idx.put(pos, idx);
        this.idx2chunk[idx] = pos;
        this.put(idx, pos);
    }

    private void ensureSize() {
        if (this.chunk2idx.size() < this.idx2chunk.length) {
            return;
        }
        UploadStream.INSTANCE.commit();

        int size = (int) (this.idx2chunk.length * 1.5);
        Logger.info("Resizing chunk bound position buffer to: " + size);
        VkBuffer old = this.chunkPosBuffer;
        // Do not zero the new buffer: the copy below fills the old range and the
        // remaining slots are written by put() before getCount() exposes them.
        // A zero() here would enqueue a fill immediately before the copy with no
        // barrier between them (fill could clobber the copied positions).
        this.chunkPosBuffer = new VkBuffer(size * 8L, false);
        old.copyTo(this.chunkPosBuffer, 0, 0, old.size());
        old.free();

        long[] oldArray = this.idx2chunk;
        this.idx2chunk = new long[size];
        System.arraycopy(oldArray, 0, this.idx2chunk, 0, oldArray.length);
    }

    private void put(int idx, long pos) {
        long ptr = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L * idx, 8);
        putPos(ptr, pos);
    }

    private static void putPos(long ptr, long pos) {
        pos = IBoundStore.transformBeforeStore(pos);
        MemoryUtil.memPutInt(ptr, (int) (pos & 0xFFFFFFFFL));
        MemoryUtil.memPutInt(ptr + 4, (int) ((pos >>> 32) & 0xFFFFFFFFL));
    }

    public void reset() {
        this.chunk2idx.clear();
        this.addQueue.clear();
        this.remQueue.clear();
        this.coverage.clear();
    }

    @Override
    public VkBuffer getBuffer() {
        return this.chunkPosBuffer;
    }

    @Override
    public int getCount() {
        return this.chunk2idx.size();
    }

    @Override
    public void free() {
        this.chunkPosBuffer.free();
    }
}
