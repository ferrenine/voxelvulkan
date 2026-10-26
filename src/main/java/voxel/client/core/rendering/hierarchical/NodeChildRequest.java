package voxel.client.core.rendering.hierarchical;

//Pending per-child results for one octree node, tracked as bitfields over 8
// child slots. Everything is packed into three longs/int fields instead of
// arrays — the whole request is 3 words of state:
//   required/results/existence masks  -> int msk (3×8 bits)
//   mesh ids (8×int, -1 default)      -> long meshLo + meshHi
//   childExistence bytes (8×byte)     -> long childBits
//Child states contain micrometadata in the top bits (isEmpty etc.), so mesh
// slots keep a full 32 bits each.
class NodeChildRequest {
    private static final int SLOTS = 8;

    private final long nodePos;
    private int msk;                    // bits 0-7 required, 8-15 results, 16-23 existence
    //8 mesh slots = 8×int = 256 bits = 4 longs (2 ints each, low half first)
    private long mesh01 = -1L, mesh23 = -1L, mesh45 = -1L, mesh67 = -1L;
    private long childBits;             // childExistence bytes, 8×8

    NodeChildRequest(long nodePos) {
        this.nodePos = nodePos;
    }

    private int getMeshRaw(int slot) {
        long packed = switch (slot >> 1) {
            case 0 -> this.mesh01; case 1 -> this.mesh23;
            case 2 -> this.mesh45; default -> this.mesh67;
        };
        return (int) (packed >>> ((slot & 1) * 32));
    }

    private void putMesh(int slot, int value) {
        int shift = (slot & 1) * 32;
        long clr = ~(0xFFFFFFFFL << shift);
        long ins = Integer.toUnsignedLong(value) << shift;
        switch (slot >> 1) {
            case 0 -> this.mesh01 = (this.mesh01 & clr) | ins;
            case 1 -> this.mesh23 = (this.mesh23 & clr) | ins;
            case 2 -> this.mesh45 = (this.mesh45 & clr) | ins;
            default -> this.mesh67 = (this.mesh67 & clr) | ins;
        }
    }

    private boolean required(int slot)   { return ((this.msk >>> slot) & 1) != 0; }
    private boolean hasResult(int slot)  { return ((this.msk >>> 8) & 1 << slot) != 0; }
    private boolean hasExistence(int slot) { return ((this.msk >>> 16) & 1 << slot) != 0; }

    public int getChildMesh(int childIdx) {
        if (!required(childIdx)) {
            throw new IllegalStateException("Tried getting mesh result of child not in mask");
        }
        return this.getMeshRaw(childIdx);
    }

    public void setChildChildExistence(int childIdx, byte childExistence) {
        if (!required(childIdx)) {
            throw new IllegalStateException("Tried setting child child existence in request when child isnt in mask");
        }
        int shift = childIdx * 8;
        this.childBits = (this.childBits & ~(0xFFL << shift)) | ((childExistence & 0xFFL) << shift);
        this.msk |= 1 << (16 + childIdx);
    }

    public boolean hasChildChildExistence(int childId) {
        if (!required(childId)) {
            throw new IllegalStateException("Tried getting child child existence set of child not in mask");
        }
        return this.hasExistence(childId);
    }

    public byte getChildChildExistence(int childIdx) {
        if (!this.hasChildChildExistence(childIdx)) {
            throw new IllegalStateException("Tried getting child child existence when child child existence for child was not set");
        }
        return (byte) (this.childBits >>> (childIdx * 8));
    }

    public int setChildMesh(int childIdx, int mesh) {
        if (!required(childIdx)) {
            throw new IllegalStateException("Tried setting child mesh when child isnt in mask");
        }
        boolean firstInsert = !this.hasResult(childIdx);
        this.msk |= 1 << (8 + childIdx);
        int prev = this.getMeshRaw(childIdx);
        this.putMesh(childIdx, mesh);
        return firstInsert ? -1 : prev;
    }

    public int removeAndUnRequire(int childIdx) {
        if (!required(childIdx)) {
            throw new IllegalStateException("Tried removing and unmasking child that was never masked");
        }
        boolean hadResult = this.hasResult(childIdx);
        this.msk &= ~(0x010101 << childIdx); // clears required+result+existence bits for the slot
        int mesh = this.getMeshRaw(childIdx);
        this.putMesh(childIdx, -1);
        return hadResult ? mesh : -1;
    }

    public void addChildRequirement(int childIdx) {
        if (required(childIdx)) {
            throw new IllegalStateException("Child already required!");
        }
        this.msk |= 1 << childIdx;
    }

    public boolean isSatisfied() {
        return ((this.msk >>> 8) & this.msk & 0xFF) == (this.msk & 0xFF);
    }

    public long getPosition() {
        return this.nodePos;
    }

    public byte getMsk() {
        return (byte) this.msk;
    }
}
