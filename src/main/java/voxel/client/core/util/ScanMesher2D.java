package voxel.client.core.util;

/**
 * 32x32 2D bitmask-driven greedy surface mesher for merging coplanar voxel faces
 * into up to 16x16 quads across scanlines.
 *
 * <p>The 16-cell cap comes from the two 4-bit size fields in the packed quad
 * format, not from the scan algorithm. Raising it to 32 needs a wider quad
 * encoding (or a side buffer); at LOD &gt; 0 one 16x16 cell already covers
 * 1024 blocks, so the extra merge range mostly helps huge uniform LOD0
 * planes.</p>
 */
public abstract class ScanMesher2D {

    private static final int MAX_SPAN = 16;

    private final long[] spanKey = new long[32];
    private final int[] spanLength = new int[32];
    private final int[] spanDepth = new int[32];
    private int activeColumnsMask = 0;

    private int cursor = 0;
    private int runLength = 0;
    private long activeKey = 0L;

    public final void putNext(long data) {
        int col = (this.cursor++) & 31;

        if (col == 0) {
            if (this.activeKey != 0L) {
                if ((this.activeColumnsMask & (1 << 31)) != 0) {
                    this.emitQuad(31, ((this.cursor - 1) >> 5) - 1, this.spanLength[31], this.spanDepth[31], this.spanKey[31]);
                }
                this.activeColumnsMask |= (1 << 31);
                this.spanLength[31] = this.runLength;
                this.spanDepth[31] = 1;
                this.spanKey[31] = this.activeKey;
            }
            this.activeKey = data;
            this.runLength = 0;
        }

        if (data != this.activeKey || this.runLength == MAX_SPAN) {
            if (this.activeKey != 0L) {
                int prevCol = col - 1;
                this.spanDepth[prevCol] = 1;
                this.spanLength[prevCol] = this.runLength;
                this.spanKey[prevCol] = this.activeKey;
                this.activeColumnsMask |= (1 << prevCol);
            }
            this.activeKey = data;
            this.runLength = 0;
        }
        this.runLength++;

        int colBit = 1 << col;
        boolean hasPrevRowSpan = (this.activeColumnsMask & colBit) != 0;
        boolean hitMaxDepth = false;

        if (this.activeKey != 0L
                && hasPrevRowSpan
                && this.spanLength[col] == this.runLength
                && this.spanKey[col] == this.activeKey) {
            int depth = ++this.spanDepth[col];
            this.runLength = 0;
            this.activeKey = 0L;
            if (depth != MAX_SPAN) {
                return;
            }
            hitMaxDepth = true;
        }

        if (hasPrevRowSpan) {
            this.emitQuad(col, ((this.cursor - 1) >> 5) - (hitMaxDepth ? 0 : 1), this.spanLength[col], this.spanDepth[col], this.spanKey[col]);
            this.activeColumnsMask &= ~colBit;
        }
    }

    private void flushMaskedColumns(int mask) {
        int pending = this.activeColumnsMask & mask;
        this.activeColumnsMask &= ~mask;
        int rowZ = (this.cursor >> 5) - 1;
        while (pending != 0) {
            int col = Integer.numberOfTrailingZeros(pending);
            pending &= pending - 1;
            this.emitQuad(col, rowZ, this.spanLength[col], this.spanDepth[col], this.spanKey[col]);
        }
    }

    public final void skip(int count) {
        if (count == 0) {
            return;
        }
        if (this.activeKey != 0L) {
            this.putNext(0L);
            count--;
        }
        if (count > 0) {
            int mask = (int) ((1L << Math.min(32, count)) - 1L) << (this.cursor & 31);
            this.flushMaskedColumns(mask);
            this.cursor += count;
        }
    }

    public final void reset() {
        this.activeColumnsMask = 0;
        this.runLength = 0;
        this.activeKey = 0L;
        this.cursor = 0;
    }

    public final void endRow() {
        int rem = this.cursor & 31;
        if (rem != 0) {
            this.skip(32 - rem);
        }
    }

    public final void finish() {
        if (this.cursor != 0) {
            this.skip(32 - (this.cursor & 31));
            this.flushMaskedColumns(-1);
        }
        this.reset();
    }

    // Contract: x,z are the run's LAST cell (top-right); implementations subtract
    // (length-1, width-1) for the quad origin. Low 26 bits of `data` carry auxData
    // consumed by emit (bit0=axisSide, bits1..2=type) before position overwrites.
    protected abstract void emitQuad(int x, int z, int length, int width, long data);
}
