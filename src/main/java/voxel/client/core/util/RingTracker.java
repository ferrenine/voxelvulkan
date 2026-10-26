package voxel.client.core.util;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import voxel.common.Logger;

import java.util.Random;

//Tracks pending load(+1)/unload(-1) positions of a radius-`radius` disc as its
// centre moves across the grid. Ops accumulate per position and are drained
// incrementally by process() — a +1/-1 pair cancels to 0 and is skipped.
public class RingTracker {
    private final Long2ByteOpenHashMap operations = new Long2ByteOpenHashMap(1<<13);
    private final int[] halfWidth;  // per row: disc half-extent along the moving axis
    private final int radius;
    private int centerX;
    private int centerZ;

    public RingTracker(int radius, int centerX, int centerZ, boolean fill) {
        this(null, radius, centerX, centerZ, fill);
    }

    public RingTracker(RingTracker stealFrom, int radius, int centerX, int centerZ, boolean fill) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.halfWidth = discHalfWidths(radius);
        if (stealFrom != null) {
            this.operations.putAll(stealFrom.operations);
            stealFrom.operations.clear();
        }
        if (fill) {
            this.fillRing(true);
        }
    }

    private static long pack(int x, int z) {
        return Integer.toUnsignedLong(x)|(Integer.toUnsignedLong(z)<<32);
    }

    private void op(long pos, int what) {
        int res = this.operations.addTo(pos, (byte) what);
        if (what == 1 ? res > 0 : res < 0) {
            throw new IllegalStateException((what == 1 ? "double load" : "double unload") + " at " + pos);
        }
    }

    private void fillRing(boolean load) {
        for (int i = 0; i <= this.radius * 2; i++) {
            int x = this.centerX + i - this.radius;
            int d = this.halfWidth[i];
            for (int z = this.centerZ - d; z <= this.centerZ + d; z++) {
                this.op(pack(x, z), load ? 1 : -1);
            }
        }
    }

    public void unload() {
        this.fillRing(false);
    }

    public void moveCenter(int x, int z) {
        //A jump larger than the disc is cheaper as full unload+reload.
        if (Math.abs(x - this.centerX) > this.radius + 1 || Math.abs(z - this.centerZ) > this.radius + 1) {
            this.fillRing(false);
            this.centerX = x;
            this.centerZ = z;
            this.fillRing(true);
            return;
        }
        this.moveAxis(x - this.centerX, true);
        this.moveAxis(z - this.centerZ, false);
    }

    //Shift the disc one cell at a time along the axis. Each step emits a
    // leading-edge load column and a trailing-edge unload column; multi-step
    // moves let intermediate cells cancel to 0 inside the ops map.
    private void moveAxis(int delta, boolean xAxis) {
        int sign = Integer.signum(delta);
        for (int s = Math.abs(delta); s != 0; s--) {
            int axis = xAxis ? this.centerX : this.centerZ;
            int other = xAxis ? this.centerZ : this.centerX;
            for (int i = 0; i <= this.radius * 2; i++) {
                int perp = other + i - this.radius;
                int half = this.halfWidth[i] * sign;
                long addPos = xAxis ? pack(axis + half + sign, perp) : pack(perp, axis + half + sign);
                long remPos = xAxis ? pack(axis - half, perp) : pack(perp, axis - half);
                this.op(addPos, 1);
                this.op(remPos, -1);
            }
            if (xAxis) this.centerX += sign; else this.centerZ += sign;
        }
    }

    public interface IUpdateConsumer {
        void accept(int x, int z);
    }

    //Drain up to N pending ops; positions whose ops cancelled out are skipped.
    public int process(int N, IUpdateConsumer onAdd, IUpdateConsumer onRemove) {
        if (this.operations.isEmpty()) {
            return 0;
        }
        var iter = this.operations.long2ByteEntrySet().fastIterator();
        int i = 0;
        while (iter.hasNext() && N-- != 0) {
            var entry = iter.next();
            byte op = entry.getByteValue();
            if (op == 0) {
                iter.remove();
                N++;
                continue;
            }
            i++;
            if (op != 1 && op != -1) {
                throw new IllegalStateException();
            }
            long pos = entry.getLongKey();
            int x = (int) (pos & 0xFFFFFFFFL);
            int z = (int) ((pos >>> 32) & 0xFFFFFFFFL);
            if (op == 1) {
                onAdd.accept(x, z);
            } else {
                onRemove.accept(x, z);
            }
            iter.remove();
        }
        return i;
    }

    private static int[] discHalfWidths(int radius) {
        var ret = new int[radius * 2 + 1];
        for (int i = -radius; i <= radius; i++) {
            ret[i + radius] = (int) Math.sqrt(radius * radius - i * i);
        }
        return ret;
    }

    public static void main(String[] args) {
        for (int j = 0; j < 50; j++) {
            Random r = new Random((j + 18723) * 1234);
            var tracker = new RingTracker(r.nextInt(100) + 1, 0, 0, true);
            int R = r.nextInt(500);
            for (int i = 0; i < 50_000; i++) {
                int x = r.nextInt(R * 2 + 1) - R;
                int z = r.nextInt(R * 2 + 1) - R;
                tracker.moveCenter(x, z);
            }
            tracker.fillRing(false);
            tracker.process(64, (x, z) -> {
                Logger.info("Add:", x, ",", z);
            }, (x, z) -> {
                Logger.info("Remove:", x, ",", z);
            });
        }
    }
}
