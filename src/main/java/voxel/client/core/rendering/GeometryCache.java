package voxel.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import voxel.client.core.rendering.building.BuiltSection;

import java.util.concurrent.locks.ReentrantLock;

//CPU side cache for section geometry, not thread safe
public class GeometryCache {
    private final ReentrantLock lock = new ReentrantLock();
    private long maxCombinedSize;
    private long currentSize;
    private final Long2ObjectLinkedOpenHashMap<BuiltSection> cache = new Long2ObjectLinkedOpenHashMap<>();
    public GeometryCache(long maxSize) {
        this.setMaxTotalSize(maxSize);
    }

    public void setMaxTotalSize(long size) {
        this.maxCombinedSize = size;
    }

    //Puts the section into the cache
    public void put(BuiltSection section) {
        long sz = sizeOf(section);
        this.lock.lock();
        var prev = this.cache.put(section.position, section);
        this.currentSize += sz;
        if (prev != null) {
            this.currentSize -= sizeOf(prev);
        }
        while (this.maxCombinedSize <= this.currentSize && !this.cache.isEmpty()) {
            var entry = this.cache.removeFirst();
            this.currentSize -= sizeOf(entry);
            entry.free();
        }
        this.lock.unlock();
        if (prev != null) {
            prev.free();
        }
    }

    /** Both native buffers belong to the cache entry; lightAO is as large as geometry. */
    private static long sizeOf(BuiltSection section) {
        long size = section.geometryBuffer != null ? section.geometryBuffer.size : 0L;
        if (section.lightBuffer != null) {
            size += section.lightBuffer.size;
        }
        return size;
    }

    public BuiltSection remove(long position) {
        this.lock.lock();
        var section = this.cache.remove(position);
        if (section != null) {
            this.currentSize -= sizeOf(section);
        }
        this.lock.unlock();
        return section;
    }

    public void clear(long position) {
        var sec = this.remove(position);
        if (sec != null) {
            sec.free();
        }
    }

    public void free() {
        this.lock.lock();
        this.cache.values().forEach(BuiltSection::free);
        this.cache.clear();
        this.currentSize = 0L;
        this.lock.unlock();
    }
}
