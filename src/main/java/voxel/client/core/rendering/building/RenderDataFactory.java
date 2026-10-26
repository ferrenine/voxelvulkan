package voxel.client.core.rendering.building;

import voxel.client.core.model.IdNotYetComputedException;
import voxel.client.core.model.ModelFactory;
import voxel.client.core.model.ModelQueries;
import voxel.client.core.util.ScanMesher2D;
import voxel.common.Logger;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.UnsafeUtil;
import voxel.common.world.WorldEngine;
import voxel.common.world.WorldSection;
import voxel.common.world.Mapper;
import voxel.commonimpl.VoxelCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;


public class RenderDataFactory {
    // AUDIT-DEAD: OccupancySet (2-level compacted 32^3 bitset for a planned AO
    // pass) was deleted together with its dead plumbing — it was behind a
    // permanently-false flag and never uploaded. Reimplement fresh if AO needs it.

    private static final boolean CHECK_NEIGHBOR_FACE_OCCLUSION = true;
    // AUDIT-FIXME: translucents (e.g. stained glass) break on chunk borders
    // with this set to false — known-open rendering bug.
    private static final boolean DISABLE_CULL_SAME_OCCLUDES = false;

    private static final boolean VERIFY_MESHING = VoxelCommon.isVerificationFlagOn("verifyMeshing");
    private static final VarHandle LONG_ARRAY_ELEMENTS = MethodHandles.arrayElementVarHandle(long[].class);

    //TODO: MAKE a render cache that caches each WorldSection directional face generation, cause then can just pull that directly
    // instead of needing to regen the entire thing

    //Ok so the idea for fluid rendering is to make it use a seperate mesher and use a different code path for it
    // since fluid states are explicitly overlays over the base block
    // can do funny stuff like double rendering


    private final WorldEngine world;
    private final ModelFactory modelMan;

    //private final long[] sectionData = new long[32*32*32*2];
    //Per-worker snapshot: the mesher must not read the live section array while
    //ingest can swap/recycle it (and must not see a half-written update).
    private final long[] rawSnapshot = new long[WorldSection.SECTION_VOLUME];
    private final long[] sectionData = new long[32*32*32*2];
    private final long[] neighboringFaces = new long[32*32*6];
    // Per-cell lava own height (1..255 units of one block; 0 = not lava).
    private final byte[] fluidOwnHeight = new byte[32*32*32];
    // Set by applyLavaFlowHeights for the UP face when the corner height
    // gradient implies a non-zero flow direction.
    private boolean lavaTopFlows;
    private static final int LAVA_FLOW_MIN_GRADIENT_SQ = 4;
    //private final int[] neighboringOpaqueMasks = new int[32*6];

    private final int[] opaqueMasks = new int[32*32];
    private final int[] nonOpaqueMasks = new int[32*32];
    private final int[] fluidMasks = new int[32*32];//Used to separately mesh fluids, allowing for fluid + blockstate
    private final int[] shadowCasterMasks = new int[32*32];
    private final WorldSection[] shadowNeighborSections = new WorldSection[27];
    private final boolean[] shadowNeighborQueried = new boolean[27];


    // AUDIT-PERF: emit directly to memory buffer instead of long arrays —
    // quads go through an extra full-array copy per section.

    //Each axis gets a max quad count of 2^16 (65536 quads) since that is the max the basic geometry manager can handle
    private final MemoryBuffer quadBuffer = new MemoryBuffer(8*(8*(1<<16)));//6 faces + dual direction + translucents
    private final long quadBufferPtr = this.quadBuffer.address;
    // Per-quad 4-corner smooth light + AO. Same 8-byte/element layout as the
    // geometry buffer so the vertex shader can read it with the same quad index.
    private final MemoryBuffer lightAOBuffer = new MemoryBuffer(8*(8*(1<<16)));
    private final long lightAOBufferPtr = this.lightAOBuffer.address;
    private final int[] quadCounters = new int[8];

    // The section metadata packs each bucket's count into a 16-bit delta, so
    // 65,535 is the largest encodable count (65,536 wraps to 0). The scratch
    // buffer still has 65,536 slots; the last one is deliberately unused.
    private static final int MAX_QUADS_PER_BUCKET = (1 << 16) - 1;
    private static final java.util.concurrent.atomic.AtomicLong QUAD_OVERFLOW_TOTAL =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong LARGE_SECTION_QUAD_COUNT =
            new java.util.concurrent.atomic.AtomicLong();
    private long sectionOverflowCount;
    private long meshSectionKey;
    private long lastOverflowLogNanos;


    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    private int quadCount = 0;

    /**
     * LOD level of the section currently being meshed. Level 0 is full detail;
     * coarser levels store merged voxels, one cell per 2^lvl blocks.
     */
    private int lodLevel = 0;
    /** World sea level, used to place coarse sea-surface water on the real surface. */
    private int seaLevel = 63;
    /** World-space Y origin of the LOD section currently being meshed. */
    private long meshSectionOriginY;



    //Wont work for double sided quads
    private final class Mesher extends ScanMesher2D {
        public int auxiliaryPosition = 0;
        public boolean doAuxiliaryFaceOffset = true;
        public int axis = 0;//Y,Z,X

        //Note x, z are in top right
        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            if (VERIFY_MESHING) {
                if (length<1||length>16) {
                    throw new IllegalStateException("length out of bounds: " + length);
                }
                if (width<1||width>16) {
                    throw new IllegalStateException("width out of bounds: " + width);
                }
                if (x<0||x>31) {
                    throw new IllegalStateException("x out of bounds: " + x);
                }
                if (z<0||z>31) {
                    throw new IllegalStateException("z out of bounds: " + z);
                }
                if (x-(length-1)<0 || z-(width-1)<0) {
                    throw new IllegalStateException("dim out of bounds: " + (x-(length-1))+", " + (z-(width-1)));
                }
            }

            x -= length-1;
            z -= width-1;

            if (this.axis == 2) {
                //Need to swizzle the data if on x axis
                int tmp = x;
                x = z;
                z = tmp;

                tmp = length;
                length = width;
                width = tmp;
            }

            //Lower 26 bits can be auxiliary data since that is where quad position information goes;
            int auxData = (int) (data&((1<<26)-1));
            data &= ~((1<<26)-1);

            int axisSide = auxData&1;
            int type = (auxData>>1)&3;//Translucent, double side, directional

            if (VERIFY_MESHING) {
                if (type == 3) {
                    throw new IllegalStateException();
                }
            }

            //Shift up if is negative axis
            int auxPos = this.auxiliaryPosition;
            auxPos += 1-(this.doAuxiliaryFaceOffset?axisSide:1);//Shift

            if (VERIFY_MESHING) {
                if (auxPos > 31) {
                    throw new IllegalStateException("OOB face: " + auxPos + ", " + axisSide);
                }
            }

            final int axis = this.axis;
            int face = (axis<<1)|axisSide;

            int modelId = (int) ((data >>> 26) & 0xFFFFL);

            long emitModelMeta = RenderDataFactory.this.modelMan.getModelMetadataFromClientId(modelId);
            boolean lava = ModelQueries.isLava(emitModelMeta);
            boolean lavaFlow = lava && RenderDataFactory.this.lodLevel == 0;

            // A coarse fluid cell reports water because some block inside its
            // 2^lodLevel volume is water, but its top face is emitted at the
            // cell top. At sea level that cell top is one block above the real
            // water surface (the sea-level water block is the lower half of the
            // cell), which leaves a one-block step/gap against LOD0 water. Drop
            // the sea-level top face by that one block. Non-sea-level water
            // keeps the cell top for now; it has no cross-LOD reference plane.
            boolean waterTop = face == 1 && RenderDataFactory.this.lodLevel > 0 && !lava
                    && ModelQueries.isFluid(emitModelMeta);
            int waterTopOffset = 0;
            if (waterTop) {
                long cellBottom = RenderDataFactory.this.meshSectionOriginY
                        + ((long) auxPos << RenderDataFactory.this.lodLevel);
                long cellTop = cellBottom + (1L << RenderDataFactory.this.lodLevel);
                // The sea-level surface is the one water plane shared by every
                // LOD. If this cell is the one containing that surface, lower
                // the coarse top face from the cell top onto the real plane.
                if (cellBottom <= RenderDataFactory.this.seaLevel
                        && RenderDataFactory.this.seaLevel <= cellTop) {
                    waterTopOffset = (int) (cellTop - RenderDataFactory.this.seaLevel);
                }
            }
            // Coarse LOD cells represent a volume of 2^lodLevel blocks, not a
            // single block: their shell faces must be lit by the cell's own
            // (mip-averaged) light instead of the open air in front of the shell.
            boolean volumeLight = false;
            if (RenderDataFactory.this.lodLevel > 0) {
                volumeLight = !ModelQueries.isFullyOpaque(emitModelMeta);
            }
            boolean leafSelfLight = RenderDataFactory.this.lodLevel == 0
                    && ModelQueries.isLeaves(emitModelMeta);

            // Fully-opaque cells have their record light byte masked to zero in
            // packPartialQuadData ("its fully opaque it always uses neighbor
            // light"). The fallback light path bakes lm(recordByte) as its tint
            // anchor, so that zero byte anchored every lit face on lm(0,0) and
            // rendered lit terrain ~15% below vanilla (far-field dirt bands.
            // the near field's background grass escapes this because mip-merged
            // LOD cells carry column-max light 15). Re-record the byte with the
            // raw light of the front-air centre cell (lo nibble = sky, hi
            // nibble = block) - the same cell the smooth pool treats as its
            // primary sample - so the anchor matches vanilla's neighbour-light
            // usage. Stairs inherit this too via stepData below.
            if (ModelQueries.isFullyOpaque(RenderDataFactory.this.modelMan.getModelMetadataFromClientId(modelId))) {
                int anchorLayer = auxPos + (axisSide == 1 ? 1 : -1);
                int front = RenderDataFactory.this.aoCellLight(
                        axis, anchorLayer, x + ((length - 1) >> 1), z + ((width - 1) >> 1));
                if (front >= 0) {
                    data = (data & ~(0xFFL << 55)) | (((long) front & 0xFFL) << 55);
                }
            }
            int stepModelId = RenderDataFactory.this.modelMan.getStairStepModelId(modelId);
            if (stepModelId != -1) {
                long stepMeta = RenderDataFactory.this.modelMan.getModelMetadataFromClientId(stepModelId);
                if (ModelQueries.faceExists(stepMeta, face)) {
                    int bufferIdx = type + (type == 2 ? face : 0);
                    int shiftAmount = axis == 0 ? 16 : (axis == 1 ? 11 : 21);
                    long stepData = (data & ~(0xFFFFL << 26)) | (Integer.toUnsignedLong(stepModelId) << 26);
                    long stepLightAO = openLightAO((int) ((stepData >>> 55) & 0xFFL));
                    for (int dx = 0; dx < length; dx++) {
                        for (int dz = 0; dz < width; dz++) {
                            int encPos = face;
                            encPos |= (x + dx) << (axis == 2 ? 16 : 21);
                            encPos |= (z + dz) << (axis == 1 ? 16 : 11);
                            encPos |= auxPos << shiftAmount;
                            long uEncPos = Integer.toUnsignedLong(encPos);

                            // A stair-step cell emits two quads; reserve both so
                            // a full bucket cannot leave half a step behind.
                            int qi = RenderDataFactory.this.allocQuads(bufferIdx, 2);
                            if (qi < 0) {
                                continue;
                            }
                            long off1 = qi * 8L + bufferIdx * 8L * (1 << 16);
                            MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + off1, data | uEncPos);
                            MemoryUtil.memPutLong(RenderDataFactory.this.lightAOBufferPtr + off1, stepLightAO);

                            long off2 = (qi + 1) * 8L + bufferIdx * 8L * (1 << 16);
                            MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + off2, stepData | uEncPos);
                            MemoryUtil.memPutLong(RenderDataFactory.this.lightAOBufferPtr + off2, stepLightAO);
                        }
                    }
                    //Update AABB bounds
                    if (axis == 0) {//Y
                        RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, auxPos);
                        RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, auxPos + 1);
                        RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                        RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);
                        RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                        RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
                    } else if (axis == 1) {//Z
                        RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, auxPos);
                        RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, auxPos + 1);
                        RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                        RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);
                        RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, z);
                        RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, z + width);
                    } else {//X
                        RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, auxPos);
                        RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, auxPos + 1);
                        RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, x);
                        RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, x + length);
                        RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                        RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
                    }
                    return;
                }
            }

            int fallbackLight = (int) ((data >>> 55) & 0xFFL);
            int shiftAmount = axis==0?16:(axis==1?11:21);

            // Greedy merging is based on the block data only. At LOD0,
            // vertical faces must carry their own per-block smooth light/AO,
            // otherwise the merged quad only has values at its two ends and
            // the contact shadow appears at the run ends instead of on each
            // block. Split every vertical run unconditionally at LOD0;
            // horizontal faces keep the cheaper key-based split.
            //
            // At coarse LODs the mesher is already operating on cells that
            // aggregate 2^lodLevel blocks. Carrying one corner sample across
            // such a face is visually equivalent at that distance and avoids
            // multiplying the far-field quad count by the merged cell area.
            // Offset models (grass, flowers, ...) and slanted faces are placed
            // per block in the vertex shader, so they still need one quad per
            // cell at every LOD.
            boolean split = lavaFlow;
            if (!lavaFlow && (length > 1 || width > 1)) {
                boolean perBlockQuads = ModelQueries.needsPerBlockQuads(
                        RenderDataFactory.this.modelMan.getModelMetadataFromClientId(modelId));
                if (perBlockQuads || (RenderDataFactory.this.lodLevel == 0 && face > 1)) {
                    split = true;
                } else if (RenderDataFactory.this.lodLevel == 0) {
                    long firstKey = RenderDataFactory.this.computeCornerLightAO(
                            x, z, 1, 1, axis, axisSide, auxPos, fallbackLight, volumeLight, leafSelfLight);
                    outer:
                    for (int dx = 0; dx < length; dx++) {
                        for (int dz = 0; dz < width; dz++) {
                            if (dx == 0 && dz == 0) {
                                continue;
                            }
                            long key = RenderDataFactory.this.computeCornerLightAO(
                                    x + dx, z + dz, 1, 1, axis, axisSide, auxPos, fallbackLight, volumeLight, leafSelfLight);
                            if (key != firstKey) {
                                split = true;
                                break outer;
                            }
                        }
                    }
                }
            }

            if (split) {
                int bufferIdx = type+(type==2?face:0);
                for (int dx = 0; dx < length; dx++) {
                    for (int dz = 0; dz < width; dz++) {
                        int qi = RenderDataFactory.this.allocQuads(bufferIdx, 1);
                        if (qi < 0) {
                            continue;
                        }
                        int qx = x + dx;
                        int qz = z + dz;
                        int encPos = face;
                        encPos |= qx << (axis==2?16:21);
                        encPos |= qz << (axis==1?16:11);
                        encPos |= auxPos << shiftAmount;
                        long splitQuad = data | Integer.toUnsignedLong(encPos);
                        long splitLight = RenderDataFactory.this.computeCornerLightAO(
                                qx, qz, 1, 1, axis, axisSide, auxPos, fallbackLight, volumeLight, leafSelfLight);
                        if (lava) {
                            splitLight = lavaFlow
                                    ? applyLavaFlowHeights(splitLight, axis, face, qx, qz, auxPos)
                                    : applyLavaCornerY(splitLight, 0, 0, 0, 0);
                            if (lavaFlow && face == 1 && RenderDataFactory.this.lavaTopFlows) {
                                int flowId = RenderDataFactory.this.modelMan.lavaFlowModelId();
                                if (flowId >= 0) {
                                    splitQuad = (splitQuad & ~(0xFFFFL << 26)) | (Integer.toUnsignedLong(flowId) << 26);
                                }
                            }
                        }
                        splitLight = applyWaterTopOffset(splitLight, waterTopOffset);
                        long off = qi*8L + bufferIdx*8L*(1<<16);
                        MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + off, splitQuad);
                        MemoryUtil.memPutLong(RenderDataFactory.this.lightAOBufferPtr + off, splitLight);
                    }
                }
            } else {
                int encodedPosition = face;
                encodedPosition |= ((width - 1) << 7) | ((length - 1) << 3);
                encodedPosition |= x << (axis==2?16:21);
                encodedPosition |= z << (axis==1?16:11);
                encodedPosition |= auxPos << (shiftAmount);

                long quad = data | Integer.toUnsignedLong(encodedPosition);
                long lightAO = RenderDataFactory.this.computeCornerLightAO(
                        x, z, length, width, axis, axisSide, auxPos, fallbackLight, volumeLight, leafSelfLight);
                if (lava) {
                    lightAO = applyLavaCornerY(lightAO, 0, 0, 0, 0);
                }
                lightAO = applyWaterTopOffset(lightAO, waterTopOffset);

                int bufferIdx = type+(type==2?face:0);//Translucent, double side, directional
                int quadIdx = RenderDataFactory.this.allocQuads(bufferIdx, 1);
                if (quadIdx < 0) {
                    return;
                }
                long bufferOffset = quadIdx*8L + bufferIdx*8L*(1<<16);
                MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + bufferOffset, quad);
                MemoryUtil.memPutLong(RenderDataFactory.this.lightAOBufferPtr + bufferOffset, lightAO);
            }


            //Update AABB bounds
            if (axis == 0) {//Y
                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, auxPos);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, auxPos + 1);

                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
            } else if (axis == 1) {//Z
                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, auxPos);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, auxPos + 1);

                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, z);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, z + width);
            } else {//X
                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, auxPos);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, auxPos + 1);

                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, x);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, x + length);

                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
            }
        }
    }

    private final Mesher blockMesher = new Mesher();
    private final Mesher seondaryblockMesher = new Mesher();//Used for dual non-opaque geometry

    /**
     * Opaque lookup for the smooth-lighting corner sample. The section edge
     * returns false; the neighbouring section's own face gets its own light.
     *
     * <p>(t0, t1) are the quad's in-plane coordinates as encoded by emitQuad:
     * axis 0 = (X, Z), axis 1 = (X, Y), axis 2 = (Y, Z) after emitQuad's X-axis
     * swizzle (matches extractPos: bits 21/16/11 = X/Y/Z). The layer is the
     * coordinate along the face normal.</p>
     */
    private boolean isAOOpaque(int axis, int layer, int t0, int t1) {
        int x;
        int y;
        int z;
        switch (axis) {
            case 0 -> { x = t0; y = layer; z = t1; }
            case 1 -> { x = t0; y = t1; z = layer; }
            default -> { x = layer; y = t0; z = t1; }
        }
        if ((x | y | z) < 0 || x > 31 || y > 31 || z > 31) {
            return false;
        }
        int idx = (y << 10) | (z << 5) | x;
        long meta = this.sectionData[(idx << 1) + 1];
        return ModelQueries.isFullyOpaque(meta) || ModelQueries.isLeaves(meta);
    }

    /** Opacity at the block layer of the face itself (not the adjacent air layer). */
    private boolean isFacePlaneOpaque(int axis, int layer, int t0, int t1) {
        int x;
        int y;
        int z;
        switch (axis) {
            case 0 -> { x = t0; y = layer; z = t1; }
            case 1 -> { x = t0; y = t1; z = layer; }
            default -> { x = layer; y = t0; z = t1; }
        }
        if ((x | y | z) < 0 || x > 31 || y > 31 || z > 31) {
            return false;
        }
        int idx = (y << 10) | (z << 5) | x;
        return ModelQueries.isFullyOpaque(this.sectionData[(idx << 1) + 1]);
    }

    /** Packed sky/block light at an open cell, or -1 when opaque/out of bounds. */
    private int aoCellLight(int axis, int layer, int t0, int t1) {
        int x;
        int y;
        int z;
        switch (axis) {
            case 0 -> { x = t0; y = layer; z = t1; }
            case 1 -> { x = t0; y = t1; z = layer; }
            default -> { x = layer; y = t0; z = t1; }
        }
        if ((x | y | z) < 0 || x > 31 || y > 31 || z > 31) {
            return this.neighborCellLight(x, y, z);
        }
        int idx = (y << 10) | (z << 5) | x;
        // Raw stored light; opaque cells store 0. computeCornerLightAO
        // replaces 0 samples with the centre sample, exactly like vanilla blend.
        return (int) ((this.sectionData[idx << 1] >>> 55) & 0xFFL);
    }

    /** Sides whose boundary layer was copied into {@link #neighboringFaces} for the current mesh. */
    private int acquiredNeighborMsk;

    /**
     * Light of a cell one step outside this section, read from the
     * neighbouring section's boundary layer. Returns 0 for a solid cell (so
     * blend substitutes it) and -1 when unknown: outside along more than one
     * axis, neighbour not loaded, or air with no stored light (all-air
     * sections are ingested with light 0, which is missing data, not darkness).
     */
    private int neighborCellLight(int x, int y, int z) {
        int outside = (x < 0 || x > 31 ? 1 : 0) + (y < 0 || y > 31 ? 1 : 0) + (z < 0 || z > 31 ? 1 : 0);
        if (outside != 1) {
            return -1;
        }
        int side;
        int index;
        if (x < 0)       { side = 0; index = (y << 5) | z; }
        else if (x > 31) { side = 1; index = (y << 5) | z; }
        else if (y < 0)  { side = 2; index = (z << 5) | x; }
        else if (y > 31) { side = 3; index = (z << 5) | x; }
        else if (z < 0)  { side = 4; index = (y << 5) | x; }
        else             { side = 5; index = (y << 5) | x; }
        if ((this.acquiredNeighborMsk & (1 << side)) == 0) {
            return -1;
        }
        long raw = this.neighboringFaces[side * 1024 + index];
        int light = (int) ((raw >>> 56) & 0xFFL);
        // A stored 0 on a NON-air cell is real darkness (deep water legitimately
        // has sky 0). Only air stores 0 as missing data — all-air sections are
        // ingested with light 0 and need the -1 recovery path.
        return light == 0 && Mapper.isAir(raw) ? -1 : light;
    }

    /**
     * Vanilla-style per-corner smooth light + AO for one (possibly merged) quad.
     * Four corners x 16 bits: sky[0..3] | block[4..7] | ao[8..9].
     */
    private long computeCornerLightAO(int x, int z, int length, int width, int axis,
                                      int axisSide, int auxPos, int fallbackLight, boolean volumeLight,
                                      boolean leafSelfLight) {
        int airLayer = auxPos + (axisSide == 1 ? 1 : -1);
        long packed = 0L;
        for (int c = 0; c < 4; c++) {
            int s0 = (c < 2) ? -1 : 1;      // tangent 0: 0 min, 1 max
            int s1 = ((c & 1) == 0) ? -1 : 1; // tangent 1
            int t0 = (s0 < 0) ? x : x + length - 1;
            int t1 = (s1 < 0) ? z : z + width - 1;
            int n0 = t0 + s0;
            int n1 = t1 + s1;

            boolean side1 = this.isAOOpaque(axis, airLayer, n0, t1);
            boolean side2 = this.isAOOpaque(axis, airLayer, t0, n1);
            boolean corner = this.isAOOpaque(axis, airLayer, n0, n1);
            int ao = (side1 && side2) ? 0 : 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0));

            // Vanilla ModelBlockRenderer.AmbientOcclusionRenderStorage.blend
            // (1.21.11 bytecode): any sample whose packed light is 0 (opaque
            // cells store 0) is REPLACED by the centre (front-air) sample before
            // the 4-way average. Opaque neighbours therefore never pull the
            // light down; the contact darkening comes only from the AO shade
            // ladder. Counting them as 0 (the old behaviour) halved the light
            // at every inner corner and produced the black rolls on the LOD.
            int center = this.aoCellLight(axis, airLayer, t0, t1);
            int l1 = this.aoCellLight(axis, airLayer, n0, t1);
            int l2 = this.aoCellLight(axis, airLayer, t0, n1);
            // When both sides are blocked vanilla reuses side 0 for the corner.
            int l3 = (side1 && side2) ? l1 : this.aoCellLight(axis, airLayer, n0, n1);
            if (center <= 0) {
                int ownerLayer = airLayer + (axisSide == 1 ? -1 : 1);
                int owner = this.aoCellLight(axis, ownerLayer, t0, t1);
                if (owner > 0) {
                    center = owner;
                }
            }
            if (center < 0) {
                // Front cell unknown (section boundary without usable data).
                // Use the brightest known neighbour, then the quad's own byte;
                // a face with no light information at all is assumed open-sky
                // rather than rendered black on the 32-block section grid.
                center = Math.max(Math.max(l1, l2), l3);
                if (center <= 0) {
                    center = (fallbackLight & 0xFF) != 0 ? (fallbackLight & 0xFF) : 0x0F;
                }
            }
            if (leafSelfLight) {
                int ownerLayer = airLayer + (axisSide == 1 ? -1 : 1);
                int owner = this.aoCellLight(axis, ownerLayer, t0, t1);
                if (owner > 0) {
                    center = owner;
                }
            }
            if (l1 <= 0) l1 = center;
            if (l2 <= 0) l2 = center;
            if (l3 <= 0) l3 = center;

            if (volumeLight) {
                // Merged LOD cell: sample the cell the face belongs to (one step
                // back from the air layer) and light the whole shell with it.
                // Using the air cell would bake open-sky 15 into the shell and
                // flatten distant canopies back to full daylight.
                int ownerLayer = airLayer + (axisSide == 1 ? -1 : 1);
                int owner = this.aoCellLight(axis, ownerLayer,
                        x + ((length - 1) >> 1), z + ((width - 1) >> 1));
                if (owner > 0) {
                    center = owner;
                    l1 = owner;
                    l2 = owner;
                    l3 = owner;
                }
            }

            int sky = ((center & 0xF) + (l1 & 0xF) + (l2 & 0xF) + (l3 & 0xF) + 2) >> 2;
            int block = (((center >> 4) & 0xF) + ((l1 >> 4) & 0xF) + ((l2 >> 4) & 0xF) + ((l3 >> 4) & 0xF) + 2) >> 2;
            int bits = (sky & 0xF) | ((block & 0xF) << 4) | ((ao & 3) << 8);
            packed |= ((long) bits) << (c << 4);
        }
        return packed;
    }

    private static long openLightAO(int packedLight) {
        int bits = (packedLight & 0xF) | (((packedLight >> 4) & 0xF) << 4) | (3 << 8);
        long v = (long) bits;
        return v | (v << 16) | (v << 32) | (v << 48);
    }

    //AUDIT-DEAD: emitMeshlets is accepted then silently dropped (never stored) —
    // dead plumbing through RenderGenerationService; remove when the meshlet
    // path is either revived or fully stripped.
    public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
        this.world = world;
        this.modelMan = modelManager;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            this.seaLevel = level.getSeaLevel();
        }
    }

    private static long getQuadTyping(long metadata) {//2 bits
        int type = 0;
        {
            boolean a = ModelQueries.isTranslucent(metadata);
            boolean b = ModelQueries.isDoubleSided(metadata);
            //Pre shift by 1
            //type = a|b?0:4;
            //type |= b&!a?2:0;
            type = a?0:(b?2:4);
        }
        return type;
    }

    private static long packPartialQuadData(int modelId, long state, long metadata, final long[] rawSectionData, int idx) {
        //This uses hardcoded data to shuffle things
        boolean biomeColoured = ModelQueries.isBiomeColoured(metadata);
        long lightAndBiome =  (state&((0x1FFL<<47)|(0xFFL<<56)))>>>1;
        lightAndBiome &= biomeColoured ? ~(7L << 52) : ~(0x1FFL << 46);
        lightAndBiome &= ModelQueries.isFullyOpaque(metadata)?~(0xFFL<<55):-1;//If its fully opaque it always uses neighbor light?

        if (biomeColoured) {
            lightAndBiome |= computeBiomeBlendBits(rawSectionData, idx, Mapper.getBiomeId(state));
        }

        long quadData = lightAndBiome;
        quadData |= Integer.toUnsignedLong(modelId)<<26;
        quadData |= getQuadTyping(metadata);//Returns the typing already shifted by 1
        return quadData;
    }

    private static long computeBiomeBlendBits(final long[] rawSectionData, int idx, int selfBiome) {
        if (selfBiome < 0 || selfBiome >= 64) {
            return 0L;
        }
        int x = idx & 31;
        int z = (idx >> 5) & 31;
        int yBase = idx & ~1023;
        int x0 = Math.max(0, x - 3), x1 = Math.min(31, x + 3);
        int z0 = Math.max(0, z - 3), z1 = Math.min(31, z + 3);
        int b0 = Mapper.getBiomeId(rawSectionData[yBase | (z << 5) | x0]);
        int b1 = Mapper.getBiomeId(rawSectionData[yBase | (z << 5) | x1]);
        int b2 = Mapper.getBiomeId(rawSectionData[yBase | (z0 << 5) | x]);
        int b3 = Mapper.getBiomeId(rawSectionData[yBase | (z1 << 5) | x]);
        if (b0 == selfBiome && b1 == selfBiome && b2 == selfBiome && b3 == selfBiome) {
            return 0L;
        }

        int sameCount = 0;
        int otherBiome = -1;
        int otherCount = 0;
        for (int dz = -3; dz <= 3; dz++) {
            int sz = Math.clamp(z + dz, 0, 31) << 5;
            for (int dx = -3; dx <= 3; dx++) {
                int sx = Math.clamp(x + dx, 0, 31);
                int nb = Mapper.getBiomeId(rawSectionData[yBase | sz | sx]);
                if (nb == selfBiome) {
                    sameCount++;
                } else if (nb >= 0 && nb < 32) {
                    if (otherBiome == -1 || nb == otherBiome) {
                        otherBiome = nb;
                        otherCount++;
                    }
                }
            }
        }
        if (otherBiome < 0 || otherCount == 0) {
            return 0L;
        }
        int blendWeight = Math.clamp(Math.round((otherCount * 4.0f) / (sameCount + otherCount)), 0, 3);
        if (blendWeight == 0) {
            return 0L;
        }
        long bits = ((long) (blendWeight & 3)) << 42;
        bits |= ((long) (otherBiome & 3)) << 44;
        bits |= ((long) ((otherBiome >>> 2) & 7)) << 52;
        return bits;
    }

    private int prepareSectionData(final long[] rawSectionData) {
        final var sectionData = this.sectionData;
        Arrays.fill(this.fluidOwnHeight, (byte) 0);
        long opaque = 0;
        long notEmpty = 0;
        long pureFluid = 0;
        long partialFluid = 0;
        long shadowCaster = 0;
        // Submerged plants (waterlogged cross-geometry: seagrass, kelp) go into
        // pureFluid so the water inside their cell meshes with the fluid path,
        // but they must ALSO reach the non-opaque mesher or the plant itself
        // renders nothing underwater.
        long submergedPlant = 0;

        // Geometric sky repair for sections whose import NBT carried no light
        // arrays (WorldImporter stores 0 for every cell in that case). Every air
        // cell sitting strictly above the topmost solid of its column is
        // sky-exposed by plain geometry, so a zero stored light byte there
        // (missing-data signature) gets its sky nibble repaired to 15. Genuine
        // dark air (caves, under overhangs) lies below the top solid and is never
        // touched. The repaired cells feed both the smooth-light pool and the
        // baked byte, so this is a fix and a discriminator at once: heavy
        // repairing proves missing light data; zero repairs proves the data was
        // present and the dark bands come from elsewhere.
        final int[] colTopY = new int[1024];//local: mesh workers may run concurrently
        for (int col = 0; col < 1024; col++) {
            colTopY[col] = 31;//default: fully air column -> nothing to repair
            for (int y = 31; y >= 0; y--) {
                if (!Mapper.isAir(rawSectionData[(y << 10) | col])) {
                    colTopY[col] = y;
                    break;
                }
            }
        }
        int neighborAcquireMskAndFlags = 0;//-+x, -+z, -+y
        for (int i = 0; i < 32*32*32;) {
            long block = rawSectionData[i];//Get the block mapping
            if (Mapper.isAir(block)) {//If it is air, just emit lighting
                sectionData[i * 2] = (block&(0xFFL<<56))>>>1;
                sectionData[i * 2 + 1] = 0;
                int cy = i >>> 10;
                if (cy > colTopY[i & 0x3FF]) {
                    sectionData[i * 2] |= 0xFL << 55;//direct sky exposure = sky 15 by definition
                }
            } else {
                int modelId = this.modelMan.getModelIdAcquire(Mapper.getBlockId(block));
                if (modelId == -1) {//Failed, so just return error
                    return Mapper.getBlockId(block)|(1<<31);
                }
                long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);

                if (ModelQueries.isLava(modelMetadata)) {
                    BlockState state = this.world.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(block));
                    float h = state.getFluidState().getOwnHeight();
                    this.fluidOwnHeight[i] = (byte) Math.clamp(Math.round(h * 255.0f), 1, 255);
                }

                sectionData[i * 2] = packPartialQuadData(modelId, block, modelMetadata, rawSectionData, i);
                sectionData[i * 2 + 1] = modelMetadata;

                long msk = 1L << (i & 63);
                boolean isSubmergedPlant = ModelQueries.containsFluid(modelMetadata) && ModelQueries.isDoubleSided(modelMetadata);
                boolean isFluidOrPlant = ModelQueries.isFluid(modelMetadata) || ModelQueries.isDoubleSided(modelMetadata) || isSubmergedPlant;
                boolean hasFullVerticalSide = (modelMetadata & 0x0000020202020000L) != 0L;
                opaque |= ModelQueries.isFullyOpaque(modelMetadata) ? msk : 0;
                notEmpty |= modelId != 0 ? msk : 0;
                pureFluid |= (ModelQueries.isFluid(modelMetadata) || isSubmergedPlant) ? msk : 0;
                partialFluid |= ModelQueries.containsFluid(modelMetadata) ? msk : 0;
                submergedPlant |= isSubmergedPlant ? msk : 0;
                shadowCaster |= (modelId != 0 && (ModelQueries.isFullyOpaque(modelMetadata) || (!isFluidOrPlant && hasFullVerticalSide))) ? msk : 0;
            }

            //Do increment here
            i++;

            if ((i & 63) == 0 && notEmpty != 0) {
                long nonOpaque = ((notEmpty^opaque)&~pureFluid) | submergedPlant;
                long fluid = pureFluid|partialFluid;
                this.opaqueMasks[(i >> 5) - 2] = (int) opaque;
                this.opaqueMasks[(i >> 5) - 1] = (int) (opaque>>>32);
                this.nonOpaqueMasks[(i >> 5) - 2] = (int) nonOpaque;
                this.nonOpaqueMasks[(i >> 5) - 1] = (int) (nonOpaque>>>32);
                this.fluidMasks[(i >> 5) - 2] = (int) fluid;
                this.fluidMasks[(i >> 5) - 1] = (int) (fluid>>>32);
                this.shadowCasterMasks[(i >> 5) - 2] = (int) shadowCaster;
                this.shadowCasterMasks[(i >> 5) - 1] = (int) (shadowCaster>>>32);

                int packedEmpty = (int) ((notEmpty>>>32)|notEmpty);

                int neighborMsk = 0;
                //-+x
                neighborMsk += packedEmpty&1;//-x
                neighborMsk += (packedEmpty>>>30)&0b10;//+x

                //notEmpty = (notEmpty != 0)?1:0;
                neighborMsk += ((((i - 1) >> 10) == 0) ? 0b100 : 0)*(packedEmpty!=0?1:0);//-y
                neighborMsk += ((((i - 1) >> 10) == 31) ? 0b1000 : 0)*(packedEmpty!=0?1:0);//+y
                neighborMsk += (((((i - 33) >> 5) & 0x1F) == 0) ? 0b10000 : 0)*(((int)notEmpty)!=0?1:0);//-z
                neighborMsk += (((((i - 1) >> 5) & 0x1F) == 31) ? 0b100000 : 0)*((notEmpty>>>32)!=0?1:0);//+z

                neighborAcquireMskAndFlags |= neighborMsk;
                neighborAcquireMskAndFlags |= opaque != 0 ? (1 << 6) : 0;
                neighborAcquireMskAndFlags |= fluid != 0 ? (1 << 7) : 0;
                neighborAcquireMskAndFlags |= nonOpaque != 0 ? (1 << 8) : 0;

                opaque = 0;
                notEmpty = 0;
                pureFluid = 0;
                partialFluid = 0;
                shadowCaster = 0;
                submergedPlant = 0;
            }
        }

        // Only run top-down fluid sky-light attenuation when the section actually contains fluid voxels
        if ((neighborAcquireMskAndFlags & (1 << 7)) != 0) {
            for (int z = 0; z < 32; z++) {
                int f31 = this.fluidMasks[(31 << 5) | z];
                int f30 = this.fluidMasks[(30 << 5) | z];
                for (int x = 0; x < 32; x++) {
                    int bit = 1 << x;
                    int submerged = 0;
                    if ((f31 & bit) != 0 && (f30 & bit) != 0) {
                        int s31 = (int) ((sectionData[(((31 << 10) | (z << 5) | x) * 2)] >>> 55) & 0xFL);
                        int s30 = (int) ((sectionData[(((30 << 10) | (z << 5) | x) * 2)] >>> 55) & 0xFL);
                        if (s31 == 15 && s30 == 15) {
                            submerged = 8;
                        }
                    }
                    for (int y = 31; y >= 0; y--) {
                        if ((this.fluidMasks[(y << 5) | z] & bit) != 0) {
                            submerged++;
                            int idx2 = ((y << 10) | (z << 5) | x) * 2;
                            long q = sectionData[idx2];
                            int sky = (int) ((q >>> 55) & 0xFL);
                            int maxSky = Math.max(0, 15 - submerged * 2);
                            if (sky > maxSky) {
                                sectionData[idx2] = (q & ~(0xFL << 55)) | ((long) maxSky << 55);
                            }
                        } else {
                            submerged = 0;
                        }
                    }
                }
            }
        }

        return neighborAcquireMskAndFlags;
    }

    /**
     * Reads a cell one step across one of the 6 cardinal section faces from
     * {@link #neighboringFaces}, or 0L when outside along multiple axes or when
     * that neighbour face was not acquired.
     */
    private long getNeighborRawBlock(int x, int y, int z) {
        int outside = (x < 0 || x > 31 ? 1 : 0) + (y < 0 || y > 31 ? 1 : 0) + (z < 0 || z > 31 ? 1 : 0);
        if (outside != 1) {
            return 0L;
        }
        int side;
        int index;
        if (x < 0)       { side = 0; index = (y << 5) | z; }
        else if (x > 31) { side = 1; index = (y << 5) | z; }
        else if (y < 0)  { side = 2; index = (z << 5) | x; }
        else if (y > 31) { side = 3; index = (z << 5) | x; }
        else if (z < 0)  { side = 4; index = (y << 5) | x; }
        else             { side = 5; index = (y << 5) | x; }
        if ((this.acquiredNeighborMsk & (1 << side)) == 0) {
            return 0L;
        }
        return this.neighboringFaces[side * 1024 + index];
    }

    private boolean isBlockIdLava(int blockId) {
        if (blockId <= 0) {
            return false;
        }
        int mid = this.modelMan.getModelIdAcquire(blockId);
        if (mid > 0) {
            return ModelQueries.isLava(this.modelMan.getModelMetadataFromClientId(mid));
        }
        return this.world.getMapper().getBlockStateFromBlockId(blockId)
                .getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    private boolean isLavaAt(int x, int y, int z) {
        if ((x | y | z) >= 0 && x < 32 && y < 32 && z < 32) {
            return this.fluidOwnHeight[(y << 10) | (z << 5) | x] != 0;
        }
        long raw = this.getNeighborRawBlock(x, y, z);
        return raw != 0L && !Mapper.isAir(raw) && this.isBlockIdLava(Mapper.getBlockId(raw));
    }

    /**
     * Vanilla {@code LiquidBlockRenderer.getHeight}: returns 1.0f if the cell
     * is lava with lava above it, {@code getOwnHeight()} if it is exposed lava,
     * 0.0f if it is non-solid (air/plants), or -1.0f if it is solid.
     */
    private float lavaGetHeight(int x, int y, int z) {
        if ((x | y | z) >= 0 && x < 32 && y < 32 && z < 32) {
            int idx = (y << 10) | (z << 5) | x;
            int ownH = this.fluidOwnHeight[idx] & 0xFF;
            if (ownH != 0) {
                return this.isLavaAt(x, y + 1, z) ? 1.0f : (ownH / 255.0f);
            }
            long meta = this.sectionData[(idx << 1) + 1];
            if (meta == 0L) {
                return 0.0f;
            }
            return (ModelQueries.isDoubleSided(meta) || ModelQueries.isFluid(meta)) ? 0.0f : -1.0f;
        }
        long raw = this.getNeighborRawBlock(x, y, z);
        if (raw == 0L) {
            return -1.0f;
        }
        if (Mapper.isAir(raw)) {
            return 0.0f;
        }
        int bid = Mapper.getBlockId(raw);
        if (this.isBlockIdLava(bid)) {
            if (this.isLavaAt(x, y + 1, z)) {
                return 1.0f;
            }
            return this.world.getMapper().getBlockStateFromBlockId(bid).getFluidState().getOwnHeight();
        }
        BlockState state = this.world.getMapper().getBlockStateFromBlockId(bid);
        return !state.canOcclude() ? 0.0f : -1.0f;
    }

    private static void lavaAddWeightedHeight(float[] fs, float f) {
        if (f >= 0.8f) {
            fs[0] += f * 10.0f;
            fs[1] += 10.0f;
        } else if (f >= 0.0f) {
            fs[0] += f;
            fs[1] += 1.0f;
        }
    }

    /** Vanilla {@code LiquidBlockRenderer.calculateAverageHeight}. */
    private float lavaCalculateAverageHeight(float f, float g, float h, int diagX, int y, int diagZ) {
        if (h >= 1.0f || g >= 1.0f) {
            return 1.0f;
        }
        float[] fs = new float[2];
        if (h > 0.0f || g > 0.0f) {
            float i = this.lavaGetHeight(diagX, y, diagZ);
            if (i >= 1.0f) {
                return 1.0f;
            }
            lavaAddWeightedHeight(fs, i);
        }
        lavaAddWeightedHeight(fs, f);
        lavaAddWeightedHeight(fs, h);
        lavaAddWeightedHeight(fs, g);
        return fs[0] / fs[1];
    }

    /** Signed 1/64-block Y offset for a per-corner height, packed as int8. */
    private static int encodeLavaYOffset(float offset) {
        return Math.clamp(Math.round(offset * 64.0f), -127, 127);
    }

    /** Writes the four signed per-corner Y offsets into the lightAO word. */
    private static long applyLavaCornerY(long lightAO, int y0, int y1, int y2, int y3) {
        long v = lightAO;
        v = (v & ~(0xFFL << 8))  | (((long) (y0 & 0xFF)) << 8);
        v = (v & ~(0xFFL << 24)) | (((long) (y1 & 0xFF)) << 24);
        v = (v & ~(0xFFL << 40)) | (((long) (y2 & 0xFF)) << 40);
        v = (v & ~(0xFFL << 56)) | (((long) (y3 & 0xFF)) << 56);
        return v;
    }

    /**
     * Coarse sea-surface water sits below the LOD cell top. The vertex shader
     * reads this 5-bit world-space offset from the unused pad bits of corner 0
     * (bits 11..15); water never uses the lava-flow bytes.
     */
    private static long applyWaterTopOffset(long lightAO, int offset) {
        if (offset == 0) {
            return lightAO;
        }
        return (lightAO & ~(0x1FL << 11)) | ((long) (offset & 0x1F) << 11);
    }

    /**
     * Vanilla-aligned per-corner Y offsets for one lava quad, matching
     * {@code LiquidBlockRenderer.tesselate}.
     */
    private long applyLavaFlowHeights(long lightAO, int axis, int face, int qx, int qz, int auxPos) {
        this.lavaTopFlows = false;
        if (axis == 0 && face == 0) {
            return applyLavaCornerY(lightAO, 0, 0, 0, 0);
        }
        final int cellX;
        final int cellY;
        final int cellZ;
        if (axis == 0) {
            cellX = qx;
            cellY = auxPos;
            cellZ = qz;
        } else if (axis == 1) {
            cellX = qx;
            cellY = qz;
            cellZ = auxPos;
        } else {
            cellX = auxPos;
            cellY = qx;
            cellZ = qz;
        }

        float n = this.lavaGetHeight(cellX, cellY, cellZ);
        final float p; // NW (cellX, cellZ)
        final float r; // SW (cellX, cellZ + 1)
        final float o; // NE (cellX + 1, cellZ)
        final float q; // SE (cellX + 1, cellZ + 1)
        if (n >= 1.0f) {
            p = 1.0f;
            r = 1.0f;
            o = 1.0f;
            q = 1.0f;
        } else {
            float s = this.lavaGetHeight(cellX, cellY, cellZ - 1);
            float t = this.lavaGetHeight(cellX, cellY, cellZ + 1);
            float u = this.lavaGetHeight(cellX + 1, cellY, cellZ);
            float v = this.lavaGetHeight(cellX - 1, cellY, cellZ);
            o = this.lavaCalculateAverageHeight(n, s, u, cellX + 1, cellY, cellZ - 1);
            p = this.lavaCalculateAverageHeight(n, s, v, cellX - 1, cellY, cellZ - 1);
            q = this.lavaCalculateAverageHeight(n, t, u, cellX + 1, cellY, cellZ + 1);
            r = this.lavaCalculateAverageHeight(n, t, v, cellX - 1, cellY, cellZ + 1);
        }

        int y0 = 0;
        int y1 = 0;
        int y2 = 0;
        int y3 = 0;
        if (axis == 0) {
            y0 = encodeLavaYOffset(p - 1.0f);
            y1 = encodeLavaYOffset(r - 1.0f);
            y2 = encodeLavaYOffset(o - 1.0f);
            y3 = encodeLavaYOffset(q - 1.0f);
            int flowX = y0 + y1 - y2 - y3;
            int flowZ = y0 + y2 - y1 - y3;
            this.lavaTopFlows = flowX * flowX + flowZ * flowZ > LAVA_FLOW_MIN_GRADIENT_SQ;
        } else if (axis == 1) {
            if ((face & 1) == 0) {
                y1 = encodeLavaYOffset(p - 1.0f);
                y3 = encodeLavaYOffset(o - 1.0f);
            } else {
                y1 = encodeLavaYOffset(r - 1.0f);
                y3 = encodeLavaYOffset(q - 1.0f);
            }
        } else {
            if ((face & 1) == 0) {
                y2 = encodeLavaYOffset(p - 1.0f);
                y3 = encodeLavaYOffset(r - 1.0f);
            } else {
                y2 = encodeLavaYOffset(o - 1.0f);
                y3 = encodeLavaYOffset(q - 1.0f);
            }
        }
        return applyLavaCornerY(lightAO, y0, y1, y2, y3);
    }

    private boolean isColumnEmpty(int x, int z) {
        int bit = 1 << x;
        for (int y = 0; y < 32; y++) {
            int row = (y << 5) | z;
            if (((this.opaqueMasks[row] | this.nonOpaqueMasks[row] | this.fluidMasks[row]) & bit) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * prepareSectionData only repairs air above the topmost solid of a column,
     * so a column that is entirely air inside this section is never touched.
     * All-air MC sections are ingested without light arrays and stored as
     * light 0, so the air around anything that pokes into such a section
     * (tower tops, tall roofs) reads as black. Treat a fully-air column as
     * sky-exposed when the same column in the section above is also air.
     */
    private void repairFullyAirColumns(WorldSection section) {
        boolean needed = false;
        for (int col = 0; col < 1024 && !needed; col++) {
            int x = col & 31;
            int z = col >>> 5;
            if (this.isColumnEmpty(x, z)) {
                for (int y = 0; y < 32; y++) {
                    int idx = (y << 10) | (z << 5) | x;
                    if (((this.sectionData[idx << 1] >>> 55) & 0xFL) != 0xFL) {
                        needed = true;
                        break;
                    }
                }
            }
        }
        if (!needed) {
            return;
        }

        WorldSection above = this.world.acquire(section.lvl, section.x, section.y + 1, section.z);
        if (above == null) {
            return;
        }
        try {
            synchronized (above) {
                long[] aboveRaw = above._unsafeGetRawDataArray();
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        if (!this.isColumnEmpty(x, z)) {
                            continue;
                        }
                        boolean covered = false;
                        for (int y = 0; y < 32; y++) {
                            if (!Mapper.isAir(aboveRaw[(y << 10) | (z << 5) | x])) {
                                covered = true;
                                break;
                            }
                        }
                        if (covered) {
                            continue;
                        }
                        for (int y = 0; y < 32; y++) {
                            int i2 = ((y << 10) | (z << 5) | x) << 1;
                            this.sectionData[i2] |= 0xFL << 55;
                        }
                    }
                }
            }
        } finally {
            above.release();
        }
    }

    private final byte[] skyScratch = new byte[32 * 32 * 32];

    /**
     * In-section sky light propagation (vanilla rule: -1 per step through
     * non-opaque cells) for cells whose stored light is missing. Only ever
     * raises the stored value, so real light data is kept, and the result is
     * a lower bound of what vanilla's light engine would have produced. This
     * restores the sky light under eaves, in porches and inside open houses
     * that the import left at 0. Boundary cells are additionally seeded from
     * the neighbouring section's boundary layer: pockets that are open to the
     * sky through a section edge would otherwise stay black forever since the
     * in-section sweep cannot see the lit cells across it.
     */
    private void propagateSkyLight(WorldSection section, int neighborMsk) {
        final long[] data = this.sectionData;
        final byte[] sky = this.skyScratch;
        boolean hasDark = false;
        boolean hasSeed = false;
        for (int i = 0; i < 32 * 32 * 32; i++) {
            int row = i >>> 5;
            int bit = 1 << (i & 31);
            if ((this.opaqueMasks[row] & bit) != 0) {
                sky[i] = -1;
                continue;
            }
            int s = (int) ((data[i << 1] >>> 55) & 0xFL);
            sky[i] = (byte) s;
            hasDark |= s < 14;
            hasSeed |= s >= 2;
        }
        if (hasDark) {
            for (int side = 0; side < 6; side++) {
                if ((neighborMsk & (1 << side)) == 0) {
                    continue;
                }
                int base = side << 10;
                for (int i = 0; i < 1024; i++) {
                    long nf = this.neighboringFaces[base + i];
                    int s = (int) ((nf >>> 56) & 0xFL);
                    if (s == 0 && side != 2 && side != 3 && Mapper.isAir(nf)) {
                        // The neighbour's own column-fill repair is not visible
                        // in raw data, so synthesise it: on x/z boundary layers
                        // the layer itself contains the whole column, and air
                        // all the way up means sky-exposed.
                        boolean open = true;
                        for (int yy = (i >>> 5) + 1; yy < 32; yy++) {
                            if (!Mapper.isAir(this.neighboringFaces[base + ((yy << 5) | (i & 31))])) {
                                open = false;
                                break;
                            }
                        }
                        if (open) {
                            s = 15;
                        }
                    }
                    s -= 1;
                    if (s <= 0) {
                        continue;
                    }
                    int idx = switch (side) {
                        case 0 -> i << 5;
                        case 1 -> (i << 5) | 31;
                        case 2 -> i;
                        case 3 -> i | (31 << 10);
                        case 4 -> Integer.expand(i, 0b11111_00000_11111);
                        default -> Integer.expand(i, 0b11111_00000_11111) | (31 << 5);
                    };
                    if (sky[idx] >= 0 && sky[idx] < s) {
                        sky[idx] = (byte) s;
                        hasSeed = true;
                    }
                }
            }
        }
        if (!hasDark || !hasSeed) {
            return;
        }

        for (int level = 15; level > 1; level--) {
            byte next = (byte) (level - 1);
            for (int i = 0; i < 32 * 32 * 32; i++) {
                if (sky[i] != level) {
                    continue;
                }
                int x = i & 31;
                int z = (i >>> 5) & 31;
                int y = i >>> 10;
                if (x > 0 && sky[i - 1] >= 0 && sky[i - 1] < next) sky[i - 1] = next;
                if (x < 31 && sky[i + 1] >= 0 && sky[i + 1] < next) sky[i + 1] = next;
                if (z > 0 && sky[i - 32] >= 0 && sky[i - 32] < next) sky[i - 32] = next;
                if (z < 31 && sky[i + 32] >= 0 && sky[i + 32] < next) sky[i + 32] = next;
                if (y > 0 && sky[i - 1024] >= 0 && sky[i - 1024] < next) sky[i - 1024] = next;
                if (y < 31 && sky[i + 1024] >= 0 && sky[i + 1024] < next) sky[i + 1024] = next;
            }
        }

        for (int i = 0; i < 32 * 32 * 32; i++) {
            int s = sky[i];
            if (s <= 0) {
                continue;
            }
            long q = data[i << 1];
            if (((q >>> 55) & 0xFL) < s) {
                data[i << 1] = (q & ~(0xFL << 55)) | ((long) s << 55);
            }
        }
    }

    /**
     * Write the repaired sky light (column fills + propagation, i.e. whatever
     * raised the nibble above what was imported) back into the section's raw
     * store so neighbouring sections can seed their own propagation from it
     * when they next build. Memory-only: the values are a recomputable lower
     * bound, not authoritative world data. Deliberately does NOT markDirty —
     * a dirty notification here re-queues this section plus all neighbours on
     * every mesh, which floods the mesh pipeline and starves the far LOD.
     */
    private void writeBackRepairedSky(WorldSection section) {
        if (this.lodLevel != 0) {
            return;
        }
        final long[] data = this.sectionData;
        // The section lock keeps setToSharedAir()/materializeData() from
        // swapping or recycling the array while we merge into it. WorldUpdater
        // writes individual elements without this lock, so each update below is
        // a CAS on the element: if the block/biome bits changed between the read
        // and the write, retry with the new value instead of restoring the old
        // ones. The repaired light is a recomputable lower bound, so dropping an
        // update when the array is replaced is acceptable.
        synchronized (section) {
            long[] raw = section._unsafeGetRawDataArray();
            for (int i = 0; i < 32 * 32 * 32; i++) {
                int s = (int) ((data[i << 1] >>> 55) & 0xFL);
                if (s <= 0) {
                    continue;
                }
                long r = raw[i];
                while (((r >>> 56) & 0xFL) < s) {
                    long next = (r & ~(0xFL << 56)) | ((long) s << 56);
                    if (LONG_ARRAY_ELEMENTS.compareAndSet(raw, i, r, next)) {
                        break;
                    }
                    r = raw[i];
                }
            }
        }
    }

    private void acquireNeighborData(WorldSection section, int msk) {
        //AUDIT-NOTE: the 6×1024 copy is load-bearing, not waste — the sun-shadow
        // pass MUTATES these records (ORs shadow bits in) which a raw on-demand
        // read couldn't do. Real remaining cost is the per-side world.acquire
        // calls + strided reads, not the memcpy.
        if ((msk&1)!=0) {//-x
            var sec = this.world.acquire(section.lvl, section.x - 1, section.y, section.z);
            synchronized (sec) {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 32*32; i++) {
                    this.neighboringFaces[i] = raw[(i<<5)+31];//pull the +x faces from the section
                }
            }
            sec.release();
        }
        if ((msk&2)!=0) {//+x
            var sec = this.world.acquire(section.lvl, section.x + 1, section.y, section.z);
            synchronized (sec) {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 32*32; i++) {
                    this.neighboringFaces[i+32*32] = raw[(i<<5)];//pull the -x faces from the section
                }
            }
            sec.release();
        }

        if ((msk&4)!=0) {//-y
            var sec = this.world.acquire(section.lvl, section.x, section.y - 1, section.z);
            synchronized (sec) {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 32*32; i++) {
                    this.neighboringFaces[i+32*32*2] = raw[i|(0x1F<<10)];//pull the +y faces from the section
                }
            }
            sec.release();
        }
        if ((msk&8)!=0) {//+y
            var sec = this.world.acquire(section.lvl, section.x, section.y + 1, section.z);
            synchronized (sec) {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 32*32; i++) {
                    this.neighboringFaces[i+32*32*3] = raw[i];//pull the -y faces from the section
                }
            }
            sec.release();
        }

        if ((msk&16)!=0) {//-z
            var sec = this.world.acquire(section.lvl, section.x, section.y, section.z - 1);
            synchronized (sec) {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 32*32; i++) {
                    this.neighboringFaces[i+32*32*4] = raw[Integer.expand(i,0b11111_00000_11111)|(0x1F<<5)];//pull the +z faces from the section
                }
            }
            sec.release();
        }
        if ((msk&32)!=0) {//+z
            var sec = this.world.acquire(section.lvl, section.x, section.y, section.z + 1);
            synchronized (sec) {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 32*32; i++) {
                    this.neighboringFaces[i+32*32*5] = raw[Integer.expand(i,0b11111_00000_11111)];//pull the -z faces from the section
                }
            }
            sec.release();
        }
    }

    private WorldSection getShadowNeighborSection(WorldSection section, int sdx, int sdy, int sdz) {
        int idx = (sdy + 1) * 9 + (sdz + 1) * 3 + (sdx + 1);
        if (!this.shadowNeighborQueried[idx]) {
            this.shadowNeighborQueried[idx] = true;
            this.shadowNeighborSections[idx] = this.world.acquire(section.lvl, section.x + sdx, section.y + sdy, section.z + sdz);
        }
        return this.shadowNeighborSections[idx];
    }

    private void releaseShadowNeighbors() {
        for (int i = 0; i < 27; i++) {
            if (this.shadowNeighborSections[i] != null) {
                this.shadowNeighborSections[i].release();
                this.shadowNeighborSections[i] = null;
            }
            this.shadowNeighborQueried[i] = false;
        }
    }

    private boolean isShadowCasterVoxel(WorldSection section, int rx, int ry, int rz) {
        if ((rx | ry | rz) >= 0 && rx < 32 && ry < 32 && rz < 32) {
            return (this.shadowCasterMasks[(ry << 5) | rz] & (1 << rx)) != 0;
        }
        int sdx = rx >> 5;
        int sdy = ry >> 5;
        int sdz = rz >> 5;
        if (sdx < -1 || sdx > 1 || sdy < -1 || sdy > 1 || sdz < -1 || sdz > 1) {
            return false;
        }
        WorldSection nSec = this.getShadowNeighborSection(section, sdx, sdy, sdz);
        if (nSec == null) {
            return false;
        }
        long nBlock = nSec._unsafeGetRawDataArray()[((ry & 31) << 10) | ((rz & 31) << 5) | (rx & 31)];
        if (!Mapper.isAir(nBlock)) {
            int mId = this.modelMan.getModelIdAcquire(Mapper.getBlockId(nBlock));
            if (mId > 0) {
                long mMeta = this.modelMan.getModelMetadataFromClientId(mId);
                boolean hasFullVert = (mMeta & 0x0000020202020000L) != 0L;
                return ModelQueries.isFullyOpaque(mMeta) || (!ModelQueries.isFluid(mMeta) && !ModelQueries.isDoubleSided(mMeta) && hasFullVert);
            }
        }
        return false;
    }

    private boolean traceSunRay(WorldSection section, int startVoxelX, int startVoxelY, int startVoxelZ, float lx, float ly, float lz) {
        float ox = startVoxelX + 0.5f;
        float oy = startVoxelY + 0.5f;
        float oz = startVoxelZ + 0.5f;
        boolean hasGroundBelow = false;
        if ((startVoxelX | startVoxelY | startVoxelZ) >= 0 && startVoxelX < 32 && startVoxelY < 32 && startVoxelZ < 32) {
            int row = (startVoxelY << 5) | startVoxelZ;
            int occRow = this.opaqueMasks[row];
            // Nudge ray origin toward the adjacent block surface (0.15 blocks off the face) so vertical cliff faces
            // on the same plane X or Z are accurately shadowed by higher cliff steps along that plane.
            if (startVoxelX < 31 && (occRow & (1 << (startVoxelX + 1))) != 0 && (occRow & (1 << startVoxelX)) == 0) {
                ox = startVoxelX + 0.85f;
            } else if (startVoxelX > 0 && (occRow & (1 << (startVoxelX - 1))) != 0 && (occRow & (1 << startVoxelX)) == 0) {
                ox = startVoxelX + 0.15f;
            }
            if (startVoxelZ < 31 && (this.opaqueMasks[(startVoxelY << 5) | (startVoxelZ + 1)] & (1 << startVoxelX)) != 0) {
                oz = startVoxelZ + 0.85f;
            } else if (startVoxelZ > 0 && (this.opaqueMasks[(startVoxelY << 5) | (startVoxelZ - 1)] & (1 << startVoxelX)) != 0) {
                oz = startVoxelZ + 0.15f;
            }
            if (startVoxelY > 0 && (this.opaqueMasks[((startVoxelY - 1) << 5) | startVoxelZ] & (1 << startVoxelX)) != 0) {
                hasGroundBelow = true;
                oy = startVoxelY + 0.20f;
            }
        }

        for (int s = 1; s <= 28; s++) {
            float dist = s * 0.72f;
            float fx = ox + lx * dist;
            float fy = oy + ly * dist;
            float fz = oz + lz * dist;
            int ry = (int) Math.floor(fy);
            if (ry <= startVoxelY) {
                continue;
            }
            int rx0 = (int) Math.floor(fx - 0.32f);
            int rx1 = (int) Math.floor(fx + 0.32f);
            int rz0 = (int) Math.floor(fz - 0.32f);
            int rz1 = (int) Math.floor(fz + 0.32f);
            if (this.isShadowCasterVoxel(section, rx0, ry, rz0)) {
                return true;
            }
            if ((rx1 != rx0 || rz1 != rz0) && this.isShadowCasterVoxel(section, rx1, ry, rz1)) {
                return true;
            }
        }
        return false;
    }

    private void precomputeSectionSunShadows(WorldSection section, int neighborMsk) {
        float[] sunDir = voxel.client.core.util.BerylUtil.getBerylLightDirAndActive();
        float lx = sunDir[0];
        float ly = sunDir[1];
        float lz = sunDir[2];
        if (ly <= 0.04f) {
            return;
        }
        try {
            for (int y = 0; y < 32; y++) {
                int yRowBase = y << 5;
                for (int z = 0; z < 32; z++) {
                    int row = yRowBase | z;
                    int occ = this.opaqueMasks[row] | this.nonOpaqueMasks[row] | this.fluidMasks[row];
                    int adj = occ | (occ << 1) | (occ >>> 1);
                    if (y > 0)  adj |= this.opaqueMasks[row - 32] | this.nonOpaqueMasks[row - 32] | this.fluidMasks[row - 32];
                    if (y < 31) adj |= this.opaqueMasks[row + 32] | this.nonOpaqueMasks[row + 32] | this.fluidMasks[row + 32];
                    if (z > 0)  adj |= this.opaqueMasks[row - 1]  | this.nonOpaqueMasks[row - 1]  | this.fluidMasks[row - 1];
                    if (z < 31) adj |= this.opaqueMasks[row + 1]  | this.nonOpaqueMasks[row + 1]  | this.fluidMasks[row + 1];
                    int sampleMask = (adj & ~this.opaqueMasks[row]) | this.nonOpaqueMasks[row];
                    while (sampleMask != 0) {
                        int x = Integer.numberOfTrailingZeros(sampleMask);
                        sampleMask &= sampleMask - 1;
                        int idx2 = ((row << 5) | x) * 2;
                        long q = this.sectionData[idx2];
                        if (((q >>> 55) & 0xFL) >= 12L) {
                            if (this.traceSunRay(section, x, y, z, lx, ly, lz)) {
                                this.sectionData[idx2] = q | (1L << 63);
                            }
                        }
                    }
                }
            }

            // AUDIT-FIXME: the six ±x/±y/±z sun-shadow boundary blocks below are
            // near-identical copies differing only in mask bit and array base —
            // copy-paste drift risk; fix in one place means fix in all six.
            if ((neighborMsk & 1) != 0) { // -x (bx = -1)
                for (int y = 0; y < 32; y++) {
                    for (int z = 0; z < 32; z++) {
                        int i = (y << 5) | z;
                        if (((this.opaqueMasks[i] | this.nonOpaqueMasks[i] | this.fluidMasks[i]) & 1) != 0) {
                            long nf = this.neighboringFaces[i] & ~1L;
                            if (((nf >>> 56) & 0xFL) >= 12L && this.traceSunRay(section, -1, y, z, lx, ly, lz)) {
                                nf |= 1L;
                            }
                            this.neighboringFaces[i] = nf;
                        }
                    }
                }
            }
            if ((neighborMsk & 2) != 0) { // +x (bx = 32)
                for (int y = 0; y < 32; y++) {
                    for (int z = 0; z < 32; z++) {
                        int i = (y << 5) | z;
                        if (((this.opaqueMasks[i] | this.nonOpaqueMasks[i] | this.fluidMasks[i]) & (1 << 31)) != 0) {
                            long nf = this.neighboringFaces[i + 1024] & ~1L;
                            if (((nf >>> 56) & 0xFL) >= 12L && this.traceSunRay(section, 32, y, z, lx, ly, lz)) {
                                nf |= 1L;
                            }
                            this.neighboringFaces[i + 1024] = nf;
                        }
                    }
                }
            }
            if ((neighborMsk & 4) != 0) { // -y (by = -1)
                for (int z = 0; z < 32; z++) {
                    int msk = this.opaqueMasks[z] | this.nonOpaqueMasks[z] | this.fluidMasks[z];
                    while (msk != 0) {
                        int x = Integer.numberOfTrailingZeros(msk);
                        msk &= msk - 1;
                        int i = (z << 5) | x;
                        long nf = this.neighboringFaces[i + 2048] & ~1L;
                        if (((nf >>> 56) & 0xFL) >= 12L && this.traceSunRay(section, x, -1, z, lx, ly, lz)) {
                            nf |= 1L;
                        }
                        this.neighboringFaces[i + 2048] = nf;
                    }
                }
            }
            if ((neighborMsk & 8) != 0) { // +y (by = 32)
                for (int z = 0; z < 32; z++) {
                    int row = (31 << 5) | z;
                    int msk = this.opaqueMasks[row] | this.nonOpaqueMasks[row] | this.fluidMasks[row];
                    while (msk != 0) {
                        int x = Integer.numberOfTrailingZeros(msk);
                        msk &= msk - 1;
                        int i = (z << 5) | x;
                        long nf = this.neighboringFaces[i + 3072] & ~1L;
                        if (((nf >>> 56) & 0xFL) >= 12L && this.traceSunRay(section, x, 32, z, lx, ly, lz)) {
                            nf |= 1L;
                        }
                        this.neighboringFaces[i + 3072] = nf;
                    }
                }
            }
            if ((neighborMsk & 16) != 0) { // -z (bz = -1)
                for (int y = 0; y < 32; y++) {
                    int row = y << 5;
                    int msk = this.opaqueMasks[row] | this.nonOpaqueMasks[row] | this.fluidMasks[row];
                    while (msk != 0) {
                        int x = Integer.numberOfTrailingZeros(msk);
                        msk &= msk - 1;
                        int i = (y << 5) | x;
                        long nf = this.neighboringFaces[i + 4096] & ~1L;
                        if (((nf >>> 56) & 0xFL) >= 12L && this.traceSunRay(section, x, y, -1, lx, ly, lz)) {
                            nf |= 1L;
                        }
                        this.neighboringFaces[i + 4096] = nf;
                    }
                }
            }
            if ((neighborMsk & 32) != 0) { // +z (bz = 32)
                for (int y = 0; y < 32; y++) {
                    int row = (y << 5) | 31;
                    int msk = this.opaqueMasks[row] | this.nonOpaqueMasks[row] | this.fluidMasks[row];
                    while (msk != 0) {
                        int x = Integer.numberOfTrailingZeros(msk);
                        msk &= msk - 1;
                        int i = (y << 5) | x;
                        long nf = this.neighboringFaces[i + 5120] & ~1L;
                        if (((nf >>> 56) & 0xFL) >= 12L && this.traceSunRay(section, x, y, 32, lx, ly, lz)) {
                            nf |= 1L;
                        }
                        this.neighboringFaces[i + 5120] = nf;
                    }
                }
            }
        } finally {
            this.releaseShadowNeighbors();
        }
    }

    // LM selects the emitted quad's lighting payload: repacked light byte
    // (55..62) | sunShadow bit 63. neighboringFaces records keep the RAW Mapper
    // layout (light@56..63) with the sun-shadow flag parked in unused bit 0;
    // extractNeighborLightAndShadow() converts between the two conventions.
    private static final long LM = (0xFFL << 55) | (1L << 63);

    private static long extractNeighborLightAndShadow(long neighborId) {
        return ((neighborId & (0xFFL << 56)) >>> 1) | ((neighborId & 1L) << 63);
    }

    private static boolean shouldMeshNonOpaqueBlockFace(int face, long quad, long meta, long neighborQuad, long neighborMeta) {
        // AUDIT-FIXME: same-model shortcut can wrongly cull (self-acknowledged
        // hack) — the cullsSame/self-occlusion rules are unresolved.
        if (((quad^neighborQuad)&(0xFFFFL<<26))==0 && (DISABLE_CULL_SAME_OCCLUDES || (ModelQueries.cullsSame(meta)||ModelQueries.faceOccludes(meta, face)))) return false;//This is a hack, if the neigbor and this are the same, dont mesh the face
        if (!ModelQueries.faceExists(meta, face)) return false;//Dont mesh if no face
        if (ModelQueries.faceCanBeOccluded(meta, face)) //TODO: maybe enable this
          if (ModelQueries.faceOccludes(neighborMeta, face^1)) return false;
        return true;
    }

    private static void meshNonOpaqueFace(int face, long quad, long meta, long neighborQuad, long neighborMeta, Mesher mesher) {
        if (shouldMeshNonOpaqueBlockFace(face, quad, meta, neighborQuad, neighborMeta)) {
            mesher.putNext((long) (face&1) |
                    (quad&~LM) |
                    ((ModelQueries.faceUsesSelfLighting(meta, face)?quad:neighborQuad) & LM));
        } else {
            mesher.skip(1);
        }
    }

    private void generateYZOpaqueInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                int current = this.opaqueMasks[pidx];
                int next = this.opaqueMasks[pidx + skipAmount];

                int msk = current ^ next;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = msk & current;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);
                        int shift = skipAmount * 32 * 2;

                        //Flip data with respect to facing direction
                        int iA = idx * 2 + (facingForward == 1 ? 0 : shift);
                        int iB = idx * 2 + (facingForward == 1 ? shift : 0);

                        long selfModel = this.sectionData[iA];
                        long nextModel = this.sectionData[iB];

                        //Check if next culls this face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            long neighbor = this.sectionData[iB + 1];
                            boolean culls = false;
                            culls |= ((selfModel^nextModel)&(0xFFFFL<<26))==0&&ModelQueries.cullsSame(neighbor);
                            culls |= ModelQueries.faceOccludes(neighbor, (axis << 1) | (1 - facingForward));
                            if (culls) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        this.blockMesher.putNext(((long) facingForward) |//Facing
                                (selfModel&~LM) |
                                (nextModel&LM)//Apply lighting
                        );
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZOpaqueOuterGeometry(int axis) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        //Hacky generate section side faces (without check neighbor section)
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.opaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];
                        long A = this.sectionData[idx * 2];

                        int nib = Mapper.getBlockId(neighborId);
                        if (nib != 0) {//Not air
                            int cid = this.modelMan.getModelId(nib);
                            long meta = this.modelMan.getModelMetadataFromClientId(cid);
                            if (ModelQueries.isFullyOpaque(meta)) {//Dont mesh this face
                                this.blockMesher.skip(1);
                                continue;
                            }

                            //This very funnily causes issues when not combined with meshing non full opaque geometry
                            //TODO:FIXME, when non opaque geometry is added
                            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                                boolean culls = false;
                                culls |= cid==((A>>26)&0xFFFF)&&ModelQueries.cullsSame(meta);
                                culls |= ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side));
                                if (culls) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }
                        }



                        this.blockMesher.putNext(((side == 0) ? 0L : 1L) |
                                (A&~LM) |
                                extractNeighborLightAndShadow(neighborId)
                        );
                    }
                }
                this.blockMesher.endRow();
            }

            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZFluidInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                //TODO: this needs to take into account opaqueMasks to not mesh any faces with it set
                int current = this.fluidMasks[pidx];
                int next = this.fluidMasks[pidx + skipAmount];

                int msk;
                int faceForwardMsk;
                if (axis == 0 && this.lodLevel == 0) {
                    // Vanilla LiquidBlockRenderer: a solid block above a fluid (Direction.UP)
                    // does not occlude the fluid's UP face (which sits below y+1, e.g. at 8/9),
                    // whereas a solid block below (Direction.DOWN) does occlude the DOWN face.
                    int upMsk = current & ~next;
                    int downMsk = next & ~(current | this.opaqueMasks[pidx]);
                    msk = upMsk | downMsk;
                    faceForwardMsk = upMsk;
                } else {
                    msk = (current | this.opaqueMasks[pidx]) ^ (next | this.opaqueMasks[pidx + skipAmount]);
                    msk &= current | next;
                    faceForwardMsk = msk & current;
                }
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);

                        int a = idx*2;
                        int b = (idx + skipAmount * 32) * 2;

                        //Flip data with respect to facing direction
                        int ai = facingForward == 1 ? a : b;
                        int bi = facingForward == 1 ? b : a;

                        //TODO: check if must cull against next entries face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {//TODO:SELF OCCLUSION
                            if (!(axis == 0 && facingForward == 1 && this.lodLevel == 0)
                                    && ModelQueries.faceOccludesFluid(this.sectionData[bi + 1], (axis << 1) | (1 - facingForward))) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];
                        //If it isnt a fluid but contains one,
                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //Update quad typing info
                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        long lighter = ModelQueries.isFullyOpaque(this.sectionData[bi + 1]) ? A : this.sectionData[bi];
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        this.blockMesher.putNext(((long) facingForward) |//Facing
                                (A&~LM) |
                                (lighter&LM)//Apply lighting
                        );
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZFluidOuterGeometry(int axis) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        //Hacky generate section side faces (without check neighbor section)
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.fluidMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[idx * 2 + 1];

                        if (ModelQueries.containsFluid(B)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            B = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //We need to update the typing info for A
                            A &= ~0b110L; A |= getQuadTyping(B);
                        }

                        long neighborLight = extractNeighborLightAndShadow(neighborId);
                        //Check and test if can cull W.R.T neighbor
                        if (Mapper.getBlockId(neighborId) != 0) {//Not air
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                            long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                            if (ModelQueries.containsFluid(meta)) {
                                modelId = this.modelMan.getFluidClientStateId(modelId);
                            }
                            if (ModelQueries.cullsSame(B)) {
                                if (modelId == ((A>>26)&0xFFFF)) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }

                            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                                if (!(axis == 0 && side == 1 && this.lodLevel == 0)
                                        && ModelQueries.faceOccludesFluid(meta, (axis << 1) | (1 - side))) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }
                            if (ModelQueries.isFullyOpaque(meta)) {
                                neighborLight = A & LM;
                            }
                        }

                        this.blockMesher.putNext((side == 0 ? 0L : 1L) |
                                (A&~LM) |
                                neighborLight
                        );
                    }
                }
                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZNonOpaqueInnerGeometry(int axis) {
        //Note: think is ok to just reuse.. blockMesher
        this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.seondaryblockMesher.axis = axis;
        for (int layer = 1; layer < 31; layer++) {//(should be 1->31, then have outer face mesher)
            this.blockMesher.auxiliaryPosition = layer;
            this.seondaryblockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {//TODO: need to do the faces that border sections
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int skipAmount = axis==0?32*32:32;

                int msk = this.nonOpaqueMasks[pidx];

                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                this.seondaryblockMesher.skip(cSkip);
                cSkip = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.seondaryblockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);

                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[idx * 2+1];

                        meshNonOpaqueFace((axis<<1)|0, A, B, this.sectionData[(idx-skipAmount)*2], this.sectionData[(idx-skipAmount)*2+1], this.seondaryblockMesher);//-
                        meshNonOpaqueFace((axis<<1)|1, A, B, this.sectionData[(idx+skipAmount)*2], this.sectionData[(idx+skipAmount)*2+1], this.blockMesher);//+
                    }
                }
                this.blockMesher.endRow();
                this.seondaryblockMesher.endRow();
            }
            this.blockMesher.finish();
            this.seondaryblockMesher.finish();
        }
    }

    private void generateYZNonOpaqueOuterGeometry(int axis) {
        //Note: think is ok to just reuse.. blockMesher
        this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.seondaryblockMesher.axis = axis;
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            int skipAmount = (axis==0?32*32:32) * (1-(side*2));
            this.blockMesher.auxiliaryPosition = layer;
            this.seondaryblockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.nonOpaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                this.seondaryblockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.seondaryblockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[idx * 2 + 1];

                        boolean fail = false;
                        //Check and test if can cull W.R.T neighbor
                        if (Mapper.getBlockId(neighborId) != 0) {//Not air
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));


                            // AUDIT-FIXME: cullsSame shortcut does not check self-occlusion — known-wrong for some models.
                            if (ModelQueries.cullsSame(B) && modelId == ((A>>26)&0xFFFF)) {
                                fail = true;
                            } else {
                                long meta = this.modelMan.getModelMetadataFromClientId(modelId);

                                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                                    fail = true;
                                }
                            }
                        }

                        long nA = this.sectionData[(idx+skipAmount) * 2];
                        long nB = this.sectionData[(idx+skipAmount) * 2 + 1];
                        boolean failB = false;

                        // AUDIT-FIXME: same cullsSame-without-self-occlusion gap as above.
                        if (ModelQueries.cullsSame(nB) && (nA&(0xFFFFL<<26)) == (A&(0xFFFFL<<26))) {
                            failB = true;
                        } else {
                            if (ModelQueries.faceOccludes(nB, (axis << 1) | (side))) {
                                failB = true;
                            }
                        }


                        long nLight = extractNeighborLightAndShadow(neighborId);
                        if (ModelQueries.faceExists(B, (axis<<1)|1) && ((side==1&&!fail) || (side==0&&!failB))) {
                            long fLight = ModelQueries.faceUsesSelfLighting(B, (axis<<1)|1) ? (A & LM) : (side == 1 ? nLight : (nA & LM));
                            this.blockMesher.putNext(1L | (A & ~LM) | fLight);
                        } else {
                            this.blockMesher.skip(1);
                        }

                        if (ModelQueries.faceExists(B, (axis<<1)|0) && ((side==0&&!fail) || (side==1&&!failB))) {
                            long fLight = ModelQueries.faceUsesSelfLighting(B, (axis<<1)|0) ? (A & LM) : (side == 0 ? nLight : (nA & LM));
                            this.seondaryblockMesher.putNext(0L | (A & ~LM) | fLight);
                        } else {
                            this.seondaryblockMesher.skip(1);
                        }
                    }
                }
                this.blockMesher.endRow();
                this.seondaryblockMesher.endRow();
            }
            this.blockMesher.finish();
            this.seondaryblockMesher.finish();
        }
    }

    private void generateYZFaces(boolean hasOpaque, boolean hasFluid, boolean hasNonOpaque) {
        for (int axis = 0; axis < 2; axis++) {//Y then Z
            this.blockMesher.axis = axis;

            if (hasOpaque) {
                this.generateYZOpaqueInnerGeometry(axis);
                this.generateYZOpaqueOuterGeometry(axis);
            }
            if (hasFluid) {
                this.generateYZFluidInnerGeometry(axis);
                this.generateYZFluidOuterGeometry(axis);
            }
            if (CHECK_NEIGHBOR_FACE_OCCLUSION && hasNonOpaque) {
                this.generateYZNonOpaqueInnerGeometry(axis);
                this.generateYZNonOpaqueOuterGeometry(axis);
            }
        }
    }


    private final Mesher[] xAxisMeshers = new Mesher[32];
    private final Mesher[] secondaryXAxisMeshers = new Mesher[32];
    {
        for (int i = 0; i < 32; i++) {
            var mesher = new Mesher();
            mesher.auxiliaryPosition = i;
            mesher.axis = 2;//X axis
            this.xAxisMeshers[i] = mesher;
        }
        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            for (int i = 0; i < 32; i++) {
                var mesher = new Mesher();
                mesher.auxiliaryPosition = i;
                mesher.axis = 2;//X axis
                mesher.doAuxiliaryFaceOffset = false;
                this.secondaryXAxisMeshers[i] = mesher;
            }
        }
    }

    private static final long X_I_MSK = 0x4210842108421L;

    // AUDIT-FIXME: the six generateX* functions below (~700 lines) are
    // line-for-line copies of the generateYZ*(axis) functions with axis=2
    // hardcoded — including the same unresolved self-occlusion FIXMEs.
    // Any fix to the YZ variants must be mirrored here (or the two folded).
    private void generateXOpaqueInnerGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int lMsk = this.opaqueMasks[y*32+z];
                msk = (lMsk^(lMsk>>>1));
                //TODO: fixme? doesnt this generate extra geometry??
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        //TODO: fixme! check this is correct or if should be 30
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                /*
                {//Dont need this as can just increment everything then -1 in mask
                    //Compute and increment skips for indexes
                    long imsk = Integer.toUnsignedLong(~msk);// we only want to increment where there isnt a face
                    sumA += Long.expand(imsk, X_I_MSK);
                    sumB += Long.expand(imsk>>11, X_I_MSK);
                    sumC += Long.expand(imsk>>22, X_I_MSK);
                }*/

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);
                        //TODO: swap this out for something not getting the next entry

                        //Flip data with respect to facing direction
                        int iA = idx * 2 + (facingForward == 1 ? 0 : 2);
                        int iB = idx * 2 + (facingForward == 1 ? 2 : 0);

                        //Check if next culls this face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            //TODO: check self occlsuion
                            if (ModelQueries.faceOccludes(this.sectionData[iB + 1], (2 << 1) | (1 - facingForward))) {
                                mesher.skip(1);
                                continue;
                            }
                        }

                        long selfModel = this.sectionData[iA];
                        long nextModel = this.sectionData[iB];

                        //Example thing thats just wrong but as example
                        mesher.putNext(((long) facingForward) |//Facing
                                (selfModel&~LM) |
                                (nextModel&LM)
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterOpaqueGeometry() {
        //Generate the side faces, hackily, using 0 and 31 mesher

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.opaqueMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        } else if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 1))) {
                            //TODO check self occlsion
                            oki = false;
                        }
                    }
                    if (oki) {
                        ma.skip(skipA); skipA = 0;
                        long A = this.sectionData[(i<<5) * 2];
                        ma.putNext(0L |
                                (A&~LM) |
                                extractNeighborLightAndShadow(neighborId)
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        } else if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 0))) {
                            //TODO check self occlsion
                            oki = false;
                        }
                    }
                    if (oki) {
                        mb.skip(skipB); skipB = 0;
                        long A = this.sectionData[(i*32+31) * 2];
                        mb.putNext(1L |
                                (A&~LM) |
                                extractNeighborLightAndShadow(neighborId)
                        );
                    } else {skipB++;}
                } else {skipB++;}
            }
            ma.skip(skipA);
            mb.skip(skipB);
        }

        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = true;
        mb.doAuxiliaryFaceOffset = true;
    }

    private void generateXInnerFluidGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int oMsk = this.opaqueMasks[y*32+z];
                int fMsk = this.fluidMasks[y*32+z];
                int lMsk = oMsk|fMsk;
                msk = (lMsk^(lMsk>>>1));
                //TODO: fixme? doesnt this generate extra geometry??
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Dont generate geometry for opaque faces
                msk &= fMsk|(fMsk>>1);

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        //TODO: fixme! check this is correct or if should be 30
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        //The facingForward thing is to get next entry automajicly
                        int ai = (idx+(1-facingForward))*2;
                        int bi = (idx+facingForward)*2;

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludesFluid(this.sectionData[bi + 1], (2 << 1) | (1 - facingForward))) {
                                //TODO check self occlsion
                                mesher.skip(1);
                                continue;
                            }
                        }

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];

                        //TODO: check if must cull against next entries face
                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //Update quad typing info to be the fluid type
                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        long lighter = this.sectionData[bi];
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        //Example thing thats just wrong but as example
                        mesher.putNext(((long) facingForward) |//Facing
                                (A&~LM) |
                                (lighter&LM)//Lighting
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterFluidGeometry() {
        //Generate the side faces, hackily, using 0 and 31 mesher

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.fluidMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                        //Update quad typing info to be the fluid type
                        A &= ~0b110L; A |= getQuadTyping(Am);
                    }


                    if (Mapper.getBlockId(neighborId) != 0) {//Not air

                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        //Check neighbor face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludesFluid(meta, (2 << 1) | (1-0))) {
                                oki = false;
                            }
                        }

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                oki = false;
                            }
                        }
                    }

                    if (oki) {
                        ma.skip(skipA); skipA = 0;

                        long lightData = extractNeighborLightAndShadow(neighborId);

                        ma.putNext(0L |
                                (A&~LM) |
                                lightData
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;


                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    //TODO: check if must cull against next entries face
                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                    }



                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        //Check neighbor face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludesFluid(meta, (2 << 1) | (1-1))) {
                                oki = false;
                            }
                        }

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                oki = false;
                            }
                        }
                    }

                    if (oki) {
                        mb.skip(skipB); skipB = 0;

                        long lightData = extractNeighborLightAndShadow(neighborId);

                        mb.putNext(1L |
                                (A&~LM) |
                                lightData
                        );
                    } else {skipB++;}
                } else {skipB++;}
            }
            ma.skip(skipA);
            mb.skip(skipB);
        }

        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = true;
        mb.doAuxiliaryFaceOffset = true;
    }

    private void generateXNonOpaqueInnerGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                msk = this.nonOpaqueMasks[y*32+z]&(~0x80000001);//Dont mesh the outer layer

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);

                        this.xAxisMeshers[index].skip(31);
                        this.secondaryXAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);


                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    var mesherA = this.xAxisMeshers[index];
                    var mesherB = this.secondaryXAxisMeshers[index];
                    if (skipCount != 0) {
                        mesherA.skip(skipCount);
                        mesherB.skip(skipCount);
                    }

                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        long A = this.sectionData[idx*2];
                        long Am = this.sectionData[idx*2+1];

                        //Check and generate the mesh for both + and - faces
                        meshNonOpaqueFace(2<<1, A, Am, this.sectionData[(idx-1)*2], this.sectionData[(idx-1)*2+1], mesherB);//-
                        meshNonOpaqueFace((2<<1)|1, A, Am, this.sectionData[(idx+1)*2], this.sectionData[(idx+1)*2+1], mesherA);//+
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                        this.secondaryXAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }



    private static void dualMeshNonOpaqueOuterX(int side, long quad, long meta, int neighborAId, long neighborLightAndShadow, long neighborAMeta, long neighborBQuad, long neighborBMeta, Mesher ma, Mesher mb) {
        //side == 0 if is on 0 side and 1 if on 31 side

        //TODO: Check (neighborAId!=0) && works oki
        if ((neighborAId==0 && ModelQueries.faceExists(meta, ((2<<1)|0)^side))||(neighborAId!=0&&shouldMeshNonOpaqueBlockFace(((2<<1)|0)^side, quad, meta, ((long)neighborAId)<<26, neighborAMeta))) {
            ma.putNext(((long)side)|
                    (quad&~LM) |
                    (ModelQueries.faceUsesSelfLighting(meta, ((2<<1)|0)^side)?(quad&LM):neighborLightAndShadow)
            );
        } else {
            ma.skip(1);
        }

        if (shouldMeshNonOpaqueBlockFace(((2<<1)|1)^side, quad, meta, neighborBQuad, neighborBMeta)) {
            mb.putNext(((long)(side^1))|
                    (quad&~LM) |
                    ((ModelQueries.faceUsesSelfLighting(meta, ((2<<1)|1)^side)?quad:neighborBQuad)&LM)
            );
        } else {
            mb.skip(1);
        }
    }

    private void generateXNonOpaqueOuterGeometry() {
        var npx = this.xAxisMeshers[0]; npx.finish();
        var nnx = this.secondaryXAxisMeshers[0]; nnx.finish();
        var ppx = this.xAxisMeshers[31]; ppx.finish();
        var pnx = this.secondaryXAxisMeshers[31]; pnx.finish();

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.nonOpaqueMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    //TODO also check self occlusion

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    int modelId = 0;
                    long nM = 0;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        nM = this.modelMan.getModelMetadataFromClientId(modelId);
                    }

                    nnx.skip(skipA);
                    npx.skip(skipA); skipA = 0;

                    dualMeshNonOpaqueOuterX(0, A, Am, modelId, extractNeighborLightAndShadow(neighborId), nM, this.sectionData[sidx+2], this.sectionData[sidx+3], nnx, npx);
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    //TODO also check self occlusion


                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    int modelId = 0;
                    long nM = 0;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        nM = this.modelMan.getModelMetadataFromClientId(modelId);
                    }

                    pnx.skip(skipB);
                    ppx.skip(skipB); skipB = 0;

                    dualMeshNonOpaqueOuterX(1, A, Am, modelId, extractNeighborLightAndShadow(neighborId), nM, this.sectionData[sidx-2], this.sectionData[sidx-1], ppx, pnx);
                } else {skipB++;}
            }
            nnx.skip(skipA);
            npx.skip(skipA);
            pnx.skip(skipB);
            ppx.skip(skipB);
        }
    }

    private void generateXFaces(boolean hasOpaque, boolean hasFluid, boolean hasNonOpaque) {
        if (hasOpaque) {
            this.generateXOpaqueInnerGeometry();
            this.generateXOuterOpaqueGeometry();

            for (var mesher : this.xAxisMeshers) {
                mesher.finish();
            }
        }

        if (hasFluid) {
            this.generateXInnerFluidGeometry();
            this.generateXOuterFluidGeometry();

            for (var mesher : this.xAxisMeshers) {
                mesher.finish();
            }
        }
        if (CHECK_NEIGHBOR_FACE_OCCLUSION && hasNonOpaque) {
            this.generateXNonOpaqueInnerGeometry();
            this.generateXNonOpaqueOuterGeometry();

            for (var mesher : this.xAxisMeshers) {
                mesher.finish();
            }
            for (var mesher : this.secondaryXAxisMeshers) {
                mesher.finish();
            }
        }
    }

    /**
     * Reserves {@code count} consecutive quad slots in {@code bucket}, or
     * returns -1 when the bucket is full. Keeps Voxy's 8 x 65536 layout and
     * 16-bit metadata encoding; the only deviation is that an overflow quad is
     * dropped instead of overwriting the next bucket or wrapping the count.
     */
    private int allocQuads(int bucket, int count) {
        int base = this.quadCounters[bucket];
        if (base + count > MAX_QUADS_PER_BUCKET) {
            this.sectionOverflowCount += count;
            QUAD_OVERFLOW_TOTAL.addAndGet(count);
            long now = System.nanoTime();
            if (now - this.lastOverflowLogNanos > 1_000_000_000L) {
                this.lastOverflowLogNanos = now;
                Logger.warn("Section " + WorldEngine.pprintPos(this.meshSectionKey)
                        + " quad bucket " + bucket + " reached its 16-bit limit; dropping "
                        + count + " quad(s) (total dropped in this section: "
                        + this.sectionOverflowCount + ")");
            }
            return -1;
        }
        this.quadCounters[bucket] = base + count;
        this.quadCount += count;
        return base;
    }

    /** Quads dropped because a 16-bit bucket was full, across all workers. */
    public static long getQuadOverflowTotal() {
        return QUAD_OVERFLOW_TOTAL.get();
    }

    //section is already acquired and gets released by the parent
    public BuiltSection generateMesh(WorldSection section) {
        this.meshSectionKey = section.key;
        // The mesher needs to know the LOD level: at coarser levels one cell
        // represents 2^lvl blocks, so its shell must be lit by the cell's own
        // (mip-averaged) light instead of the open air in front of it.
        this.lodLevel = section.lvl;
        this.meshSectionOriginY = (long) section.y << (5 + section.lvl);
        //TODO: FIXME: because of the exceptions that are thrown when aquiring modelId
        // this can result in the state of all block meshes and well _everything_ from being incorrect
        //THE EXCEPTION THAT THIS THROWS CAUSES MAJOR ISSUES

        //Copy section data to end of array so that can mutate array while reading safely
        //section.copyDataTo(this.sectionData, 32*32*32);

        //We must reset _everything_ that could have changed as we dont exactly know the state due to how the model id exception
        // throwing system works
        this.quadCount = 0;
        this.sectionOverflowCount = 0;

        {//Reset all the block meshes
            this.blockMesher.reset();
            this.blockMesher.doAuxiliaryFaceOffset = true;
            this.seondaryblockMesher.reset();
            this.seondaryblockMesher.doAuxiliaryFaceOffset = true;
            for (var mesher : this.xAxisMeshers) {
                mesher.reset();
                mesher.doAuxiliaryFaceOffset = true;
            }
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                for (var mesher : this.secondaryXAxisMeshers) {
                    mesher.reset();
                    mesher.doAuxiliaryFaceOffset = false;
                }
            }
        }

        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.minZ = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
        this.maxZ = Integer.MIN_VALUE;

        Arrays.fill(this.quadCounters,0);
        Arrays.fill(this.opaqueMasks, 0);
        Arrays.fill(this.nonOpaqueMasks, 0);
        Arrays.fill(this.fluidMasks, 0);
        Arrays.fill(this.shadowCasterMasks, 0);

        //Prepare everything. Take a consistent snapshot first: ingest may be
        //writing this section (or setToSharedAir may be recycling its array)
        //while the mesh runs. One 256KB copy per mesh is cheap next to the
        //quad generation and removes the torn-snapshot window entirely.
        final long[] rawSnapshot = this.rawSnapshot;
        synchronized (section) {
            System.arraycopy(section._unsafeGetRawDataArray(), 0, rawSnapshot, 0, WorldSection.SECTION_VOLUME);
        }
        int neighborMskAndFlags = this.prepareSectionData(rawSnapshot);
        if ((neighborMskAndFlags&(1<<31))!=0) {//We failed to get everything so throw exception
            throw new IdNotYetComputedException(neighborMskAndFlags&((1<<20)-1), true);
        }
        int neighborMsk = neighborMskAndFlags&0b11_11_11;
        int flags = neighborMskAndFlags>>>6;
        boolean hasOpaque = (flags & 1) != 0;
        boolean hasFluid = (flags & 2) != 0;
        boolean hasNonOpaque = (flags & 4) != 0;
        if (!hasOpaque && !hasFluid && !hasNonOpaque) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }
        this.repairFullyAirColumns(section);
        this.acquiredNeighborMsk = 0;
        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            this.acquireNeighborData(section, neighborMsk);
            this.acquiredNeighborMsk = neighborMsk;
        }
        this.propagateSkyLight(section, this.acquiredNeighborMsk);
        this.writeBackRepairedSky(section);
        this.precomputeSectionSunShadows(section, neighborMsk);

        try {
            this.generateYZFaces(hasOpaque, hasFluid, hasNonOpaque);
            this.generateXFaces(hasOpaque, hasFluid, hasNonOpaque);
        } catch (IdNotYetComputedException e) {
            e.auxBitMsk = neighborMsk;
            e.auxData = this.neighboringFaces;
            throw e;
        }

        //TODO:NOTE! when doing face culling of translucent blocks,
        // if the connecting type of the translucent block is the same AND the face is full, discard it
        // this stops e.g. multiple layers of glass (and ocean) from having 3000 layers of quads etc
        if (this.quadCount == 0) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }

        if (this.quadCount >= 1<<16) {
            // Dense LOD0 forest sections legitimately exceed 65k quads across
            // their 8 buckets. Logging every one of them (thousands during a
            // world load) stalls the meshers on synchronous log I/O.
            long n = LARGE_SECTION_QUAD_COUNT.incrementAndGet();
            if ((n & 1023L) == 1L) {
                Logger.warn("Sections with >65,536 quads: " + n + " (latest "
                        + WorldEngine.pprintPos(section.key) + " = " + this.quadCount + ")");
            }
        }

        if (this.minX<0 || this.minY<0 || this.minZ<0 || 32<this.maxX || 32<this.maxY || 32<this.maxZ) {
            throw new IllegalStateException();
        }

        int[] offsets = new int[8];
        var buff = new MemoryBuffer(this.quadCount * 8L);
        var lightBuff = new MemoryBuffer(this.quadCount * 8L);
        long ptr = buff.address;
        long lptr = lightBuff.address;
        int coff = 0;
        int loff = 0;
        for (int buffer = 0; buffer < 8; buffer++) {// translucent, double sided quads, 6 faces
            offsets[buffer] = coff;
            int size = this.quadCounters[buffer];
            UnsafeUtil.memcpy(this.quadBufferPtr + (buffer*(8*(1<<16))), ptr + coff*8L, (size* 8L));
            UnsafeUtil.memcpy(this.lightAOBufferPtr + (buffer*(8*(1<<16))), lptr + loff*8L, (size* 8L));
            coff += size;
            loff += size;
        }



        int aabb = 0;
        aabb |= this.minX;
        aabb |= this.minY<<5;
        aabb |= this.minZ<<10;
        aabb |= Math.max(0, this.maxX-this.minX-1)<<15;
        aabb |= Math.max(0, this.maxY-this.minY-1)<<20;
        aabb |= Math.max(0, this.maxZ-this.minZ-1)<<25;

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, lightBuff, offsets);
    }

    public void free() {
        this.quadBuffer.free();
        this.lightAOBuffer.free();
    }
}