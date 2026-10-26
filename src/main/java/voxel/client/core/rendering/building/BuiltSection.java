package voxel.client.core.rendering.building;

import voxel.common.util.MemoryBuffer;
import voxel.commonimpl.VoxelCommon;

import java.util.Arrays;

//TODO: also have an AABB size stored
public final class BuiltSection {
    public static final boolean VERIFY_BUILT_SECTION_OFFSETS = VoxelCommon.isVerificationFlagOn("verifyBuiltSectionOffsets");
    public final long position;
    public final byte childExistence;
    public final int aabb;
    public final MemoryBuffer geometryBuffer;
    public final MemoryBuffer lightBuffer;
    public final int[] offsets;
    /**
     * Monotonic content generation from the build task (T-02). Results are
     * only accepted while this still matches the position's current task.
     */
    public long generation;

    private BuiltSection(long position, byte children) {
        this(position, children, -1, null, null, null);
    }

    public static BuiltSection empty(long position) {
        return new BuiltSection(position, (byte) 0);
    }
    public static BuiltSection emptyWithChildren(long position, byte children) {
        return new BuiltSection(position, children);
    }

    public BuiltSection(long position, byte childExistence, int aabb, MemoryBuffer geometryBuffer, MemoryBuffer lightBuffer, int[] offsets) {
        this.position = position;
        this.childExistence = childExistence;
        this.aabb = aabb;
        this.geometryBuffer = geometryBuffer;
        this.lightBuffer = lightBuffer;
        this.offsets = offsets;
        if (offsets != null && VERIFY_BUILT_SECTION_OFFSETS) {
            for (int i = 0; i < offsets.length-1; i++) {
                int delta = offsets[i+1] - offsets[i];
                if (delta<0||delta>=(1<<16)) {
                    throw new IllegalArgumentException("Offsets out of range");
                }
            }
        }
    }

    public BuiltSection clone() {
        BuiltSection copy = new BuiltSection(this.position, this.childExistence, this.aabb,
                this.geometryBuffer!=null?this.geometryBuffer.copy():null,
                this.lightBuffer!=null?this.lightBuffer.copy():null,
                this.offsets!=null?Arrays.copyOf(this.offsets, this.offsets.length):null);
        copy.generation = this.generation;
        return copy;
    }

    public void free() {
        if (this.geometryBuffer != null) {
            this.geometryBuffer.free();
        }
        if (this.lightBuffer != null) {
            this.lightBuffer.free();
        }
    }

    public boolean isEmpty() {
        return this.geometryBuffer == null;
    }
}
