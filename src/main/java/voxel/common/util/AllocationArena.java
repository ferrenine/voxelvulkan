package voxel.common.util;

import java.util.Arrays;

//First-fit arena over a linear address space.
// State lives in two parallel sorted-array indexes (no tree nodes, no boxing,
// no iterator allocation in the alloc/free hot path):
//   taken: blocks sorted by address — used for neighbour merge on free()
//   free:  gaps sorted by (size, then address) — used for best-fit on alloc()
// Invariants: taken blocks are disjoint and gap-separated; every free gap has
// a taken block (or nothing) immediately before AND after it except that no
// free gap may touch totalSize — freeing the last taken block shrinks it.
public class AllocationArena {
    public static final long SIZE_LIMIT = -1;

    private long[] takenAddr = new long[64];
    private long[] takenSize = new long[64];
    private int takenCount;
    private long[] freeAddr = new long[64];
    private long[] freeSize = new long[64];
    private int freeCount;

    private long sizeLimit = Long.MAX_VALUE;
    private long totalSize;
    private boolean resized;

    public void reset() {
        this.takenCount = 0;
        this.freeCount = 0;
        this.sizeLimit = Long.MAX_VALUE;
        this.totalSize = 0;
        this.resized = false;
    }

    public boolean getResetResized() {
        boolean ret = this.resized;
        this.resized = false;
        return ret;
    }

    public long getSize() {
        return this.totalSize;
    }

    //Index of the first free gap whose size >= size, preferring lowest address
    // among equal sizes. Returns freeCount if nothing fits.
    private int freeFit(long size) {
        int lo = 0, hi = this.freeCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            long s = this.freeSize[mid];
            if (s < size) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    //Insertion point for a (size,addr) key in the free index.
    private int freeSearch(long size, long addr) {
        int lo = 0, hi = this.freeCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            long s = this.freeSize[mid];
            if (s < size || (s == size && this.freeAddr[mid] < addr)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void insertFree(long addr, long size) {
        if (this.freeCount == this.freeAddr.length) {
            this.freeAddr = Arrays.copyOf(this.freeAddr, this.freeAddr.length * 2);
            this.freeSize = Arrays.copyOf(this.freeSize, this.freeSize.length * 2);
        }
        int i = this.freeSearch(size, addr);
        int tail = this.freeCount - i;
        if (tail != 0) {
            System.arraycopy(this.freeAddr, i, this.freeAddr, i + 1, tail);
            System.arraycopy(this.freeSize, i, this.freeSize, i + 1, tail);
        }
        this.freeAddr[i] = addr;
        this.freeSize[i] = size;
        this.freeCount++;
    }

    private void removeFree(int i) {
        int tail = --this.freeCount - i;
        if (tail != 0) {
            System.arraycopy(this.freeAddr, i + 1, this.freeAddr, i, tail);
            System.arraycopy(this.freeSize, i + 1, this.freeSize, i, tail);
        }
    }

    private void insertTaken(long addr, long size) {
        if (this.takenCount == this.takenAddr.length) {
            this.takenAddr = Arrays.copyOf(this.takenAddr, this.takenAddr.length * 2);
            this.takenSize = Arrays.copyOf(this.takenSize, this.takenSize.length * 2);
        }
        int i = ~Arrays.binarySearch(this.takenAddr, 0, this.takenCount, addr);
        int tail = this.takenCount - i;
        if (tail != 0) {
            System.arraycopy(this.takenAddr, i, this.takenAddr, i + 1, tail);
            System.arraycopy(this.takenSize, i, this.takenSize, i + 1, tail);
        }
        this.takenAddr[i] = addr;
        this.takenSize[i] = size;
        this.takenCount++;
    }

    private void removeTaken(int i) {
        int tail = --this.takenCount - i;
        if (tail != 0) {
            System.arraycopy(this.takenAddr, i + 1, this.takenAddr, i, tail);
            System.arraycopy(this.takenSize, i + 1, this.takenSize, i, tail);
        }
    }

    private int takenIndex(long addr) {
        return Arrays.binarySearch(this.takenAddr, 0, this.takenCount, addr);
    }

    public long alloc(int size) {
        if (size == 0) throw new IllegalArgumentException();
        int i = this.freeFit(size);
        if (i < this.freeCount) {
            long addr = this.freeAddr[i];
            long gap = this.freeSize[i];
            removeFree(i);
            if (gap != size) {
                insertFree(addr + size, gap - size);
            }
            insertTaken(addr, size);
            return addr;
        }
        //Nothing reusable — grow the arena tail.
        this.resized = true;
        long addr = this.totalSize;
        if (addr + size > this.sizeLimit) {
            return SIZE_LIMIT;
        }
        this.totalSize += size;
        insertTaken(addr, size);
        return addr;
    }

    public int free(long addr) {
        int i = this.takenIndex(addr);
        if (i < 0 || this.takenAddr[i] != addr) {
            throw new IllegalStateException("free of untracked address " + addr);
        }
        long size = this.takenSize[i];
        removeTaken(i);
        //i now indexes the next taken block (or == takenCount at tail).

        long start = addr, end = addr + size;

        long prevEnd = i == 0 ? 0 : this.takenAddr[i - 1] + this.takenSize[i - 1];
        if (prevEnd < start) {
            removeFree(this.freeSearch(start - prevEnd, prevEnd));
            start = prevEnd;
        }

        if (i == this.takenCount) {
            //Freed the last taken block: the merged range evaporates — the
            // arena tail shrinks instead of accruing a free gap.
            this.resized = true;
            this.totalSize -= end - start;
            return (int) size;
        }

        long nextStart = this.takenAddr[i];
        if (end < nextStart) {
            removeFree(this.freeSearch(nextStart - end, end));
            end = nextStart;
        }

        insertFree(start, end - start);
        return (int) size;
    }

    //Grow an allocation into the free gap after it (or the arena tail).
    //AUDIT-FIXME: expanding into the tail grows totalSize without setting
    // resized — callers watching getResetResized() never learn the backing
    // buffer must grow. Inherited behaviour, kept pending a caller audit.
    public boolean expand(long addr, int extra) {
        int i = this.takenIndex(addr);
        if (i < 0) {
            if (-i - 1 < this.takenCount) {
                throw new IllegalStateException();
            }
            return false;
        }
        long size = this.takenSize[i];
        long end = addr + size;

        if (i + 1 == this.takenCount) {
            if (this.totalSize + extra > this.sizeLimit) {
                return false;
            }
            this.takenSize[i] = size + extra;
            this.totalSize += extra;
            return true;
        }

        long gap = this.takenAddr[i + 1] - end;
        if (extra > gap) {
            return false;
        }
        removeFree(this.freeSearch(gap, end));
        if (gap != extra) {
            insertFree(end + extra, gap - extra);
        }
        this.takenSize[i] = size + extra;
        return true;
    }

    public long getSize(long addr) {
        int i = this.takenIndex(addr);
        if (i < 0) {
            throw new IllegalArgumentException();
        }
        return this.takenSize[i];
    }

    public void setLimit(long size) {
        this.sizeLimit = size;
        if (this.sizeLimit < this.totalSize) {
            throw new IllegalStateException("Size set smaller than current size");
        }
    }

    public long getLimit() {
        return this.sizeLimit;
    }
}
