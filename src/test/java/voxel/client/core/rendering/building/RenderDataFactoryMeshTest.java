package voxel.client.core.rendering.building;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import voxel.client.core.model.ModelLookup;
import voxel.common.config.section.SectionSerializationStorage;
import voxel.common.config.storage.inmemory.MemoryStorageBackend;
import voxel.common.util.MemoryBuffer;
import voxel.common.world.Mapper;
import voxel.common.world.WorldEngine;
import voxel.common.world.WorldSection;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden mesh tests. Uses a real {@link WorldEngine} backed by in-memory
 * storage but a deterministic {@link ModelLookup}; no client, GPU or texture
 * atlas is involved. The hashes cover the geometry buffer, light/AO buffer and
 * the eight bucket offsets, so an axis refactor or culling change is caught.
 */
class RenderDataFactoryMeshTest {

    private static final int STONE = 1;
    private static final int GLASS = 2;
    private static final int LEAVES = 3;

    private static final String STONE_HASH = "a940b6777162f4ede4a0a12e395d569f16b812a0c62f31e716b975b2beb4fae3";
    private static final String GLASS_HASH = "3181df80f64faba34f4a6400e70a8e57482d4a01157b184470e7e4ea5a18f1d7";
    private static final String LEAVES_HASH = "465f79e48f4fb8cc99973b5e3b8b70542b34489f37e01d039902f7cce2b7dd2c";
    private static final String CUBE_HASH = "04b3f9a6da22af7cf2ff59a174a08ab1c98f7029bc6d160f0cf5a4bcccc5eebf";
    private static final String BOUNDARY_HASH = "abf627f553f9e5c1d344812a122fb18fc1f59d5f58ffc06d753a00106091875b";
    private static final String GLASS_PAIR_HASH = "9ec10a8a4b2c8d3edb13be1e34094af941aa6c7de96b994e219209eb37810e8c";

    private static final long FULLY_OPAQUE = 1L << 54;
    private static final long CULLS_SAME = 1L << 53;
    private static final long TRANSLUCENT = 1L << 49;
    private static final long IS_LEAVES = 1L << 56;

    private WorldEngine engine;
    private RenderDataFactory factory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        SectionSerializationStorage storage = new SectionSerializationStorage(backend);
        this.engine = new WorldEngine(storage);
        this.factory = new RenderDataFactory(this.engine, fakeModels(), false);
    }

    @AfterEach
    void tearDown() {
        this.factory.free();
        this.engine.free();
    }

    @Test
    void emptySectionHasNoGeometry() {
        BuiltSection result = this.mesh(STONE);
        try {
            assertTrue(result.isEmpty());
            assertNull(result.geometryBuffer);
            assertNull(result.lightBuffer);
        } finally {
            result.free();
        }
    }

    @Test
    void singleStoneHasSixQuads() {
        BuiltSection result = this.mesh(STONE, 16, 16, 16);
        try {
            assertNotNull(result.geometryBuffer);
            assertEquals(6L * 8L, result.geometryBuffer.size);
            assertEquals(STONE_HASH, hash(result));
        } finally {
            result.free();
        }
    }

    @Test
    void singleGlassHasGeometry() {
        BuiltSection result = this.mesh(GLASS, 16, 16, 16);
        try {
            assertNotNull(result.geometryBuffer);
            assertTrue(result.geometryBuffer.size > 0);
            assertEquals(GLASS_HASH, hash(result));
        } finally {
            result.free();
        }
    }

    @Test
    void singleLeavesHasGeometry() {
        BuiltSection result = this.mesh(LEAVES, 16, 16, 16);
        try {
            assertNotNull(result.geometryBuffer);
            assertTrue(result.geometryBuffer.size > 0);
            assertEquals(LEAVES_HASH, hash(result));
        } finally {
            result.free();
        }
    }

    @Test
    void twoByTwoStoneCubeHasGeometry() {
        int[] positions = new int[8 * 3];
        int i = 0;
        for (int x = 15; x <= 16; x++) {
            for (int y = 15; y <= 16; y++) {
                for (int z = 15; z <= 16; z++) {
                    positions[i++] = x;
                    positions[i++] = y;
                    positions[i++] = z;
                }
            }
        }
        BuiltSection result = this.mesh(STONE, positions);
        try {
            assertNotNull(result.geometryBuffer);
            assertTrue(result.geometryBuffer.size > 0);
            assertEquals(CUBE_HASH, hash(result));
        } finally {
            result.free();
        }
    }

    @Test
    void stoneAtSectionBoundaryHasGeometry() {
        BuiltSection result = this.mesh(STONE, 31, 16, 16);
        try {
            assertNotNull(result.geometryBuffer);
            assertTrue(result.geometryBuffer.size > 0);
            assertEquals(BOUNDARY_HASH, hash(result));
        } finally {
            result.free();
        }
    }

    @Test
    void adjacentGlassHasGeometry() {
        BuiltSection result = this.mesh(GLASS, 16, 16, 16, 17, 16, 16);
        try {
            assertNotNull(result.geometryBuffer);
            assertTrue(result.geometryBuffer.size > 0);
            assertEquals(GLASS_PAIR_HASH, hash(result));
        } finally {
            result.free();
        }
    }

    @Test
    void affineLightPlaneHasZeroResidualCells() {
        int length = 4;
        int width = 3;
        long[] keys = new long[length * width];
        for (int dz = 0; dz < width; dz++) {
            for (int dx = 0; dx < length; dx++) {
                keys[dz * length + dx] = packAffineCell(dx, dz, 3, 2, 1, 14, -2, -1, 3, 0, 0);
            }
        }

        assertEquals(0, RenderDataFactory.countAffineResidualCells(keys, length, width));
        long macro = RenderDataFactory.packMacroCornerLightAO(keys, length, width);
        assertEquals(packCorner(3, 14, 3), (int) (macro & 0x3FFL));
        assertEquals(packCorner(3 + 3, 14 - 3, 3), (int) ((macro >>> 16) & 0x3FFL));
        assertEquals(packCorner(3 + 8, 14 - 8, 3), (int) ((macro >>> 32) & 0x3FFL));
        assertEquals(packCorner(3 + 11, 14 - 11, 3), (int) ((macro >>> 48) & 0x3FFL));

        // Perturbing one inner cell's corner leaves exactly one residual cell.
        keys[1 * length + 2] ^= 1L;
        assertEquals(1, RenderDataFactory.countAffineResidualCells(keys, length, width));
    }

    private static long packAffineCell(
            int dx, int dz,
            int sky0, int skyX, int skyZ,
            int block0, int blockX, int blockZ,
            int ao0, int aoX, int aoZ) {
        long packed = 0L;
        for (int c = 0; c < 4; c++) {
            int cx = dx + (c >>> 1);
            int cz = dz + (c & 1);
            int bits = packCorner(
                    sky0 + skyX * cx + skyZ * cz,
                    block0 + blockX * cx + blockZ * cz,
                    ao0 + aoX * cx + aoZ * cz);
            packed |= (long) bits << (c << 4);
        }
        return packed;
    }

    private static int packCorner(int sky, int block, int ao) {
        return (sky & 0xF) | ((block & 0xF) << 4) | ((ao & 0x3) << 8);
    }

    private BuiltSection mesh(int blockId, int... positions) {
        WorldSection section = this.engine.acquire(0, 0, 0, 0);
        try {
            if (positions.length != 0) {
                for (int i = 0; i < positions.length; i += 3) {
                    section.set(positions[i], positions[i+1], positions[i+2],
                            Mapper.composeMappingId((byte) 0x0F, blockId, 0));
                }
                section.addNonEmptyBlockCount(positions.length / 3);
                section.updateLvl0State();
            }
            return this.factory.generateMesh(section);
        } finally {
            section.release();
        }
    }

    private static ModelLookup fakeModels() {
        long stone = faceBits(0b00000101) | FULLY_OPAQUE | CULLS_SAME;
        long glass = faceBits(0b00000100) | TRANSLUCENT | CULLS_SAME;
        long leaves = faceBits(0b00000100) | IS_LEAVES;

        return new ModelLookup() {
            @Override
            public int getModelIdAcquire(int blockId) {
                return blockId == STONE || blockId == GLASS || blockId == LEAVES ? blockId : -1;
            }

            @Override
            public int getModelId(int blockId) {
                return blockId;
            }

            @Override
            public long getModelMetadataFromClientId(int clientId) {
                return switch (clientId) {
                    case STONE -> stone;
                    case GLASS -> glass;
                    case LEAVES -> leaves;
                    default -> 0L;
                };
            }

            @Override
            public int getStairStepModelId(int clientModelId) {
                return -1;
            }

            @Override
            public int getLambdabettergrassVariant(int clientModelId) {
                return -1;
            }

            @Override
            public int getFluidClientStateId(int clientBlockStateId) {
                return -1;
            }

            @Override
            public int lavaFlowModelId() {
                return -1;
            }
        };
    }

    private static long faceBits(int bits) {
        long metadata = 0L;
        for (int face = 0; face < 6; face++) {
            metadata |= (long) bits << (8 * face);
        }
        return metadata;
    }

    private static String hash(BuiltSection section) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int offset : section.offsets) {
                digest.update(ByteBuffer.allocate(4).putInt(offset).array());
            }
            update(digest, section.geometryBuffer);
            update(digest, section.lightBuffer);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void update(MessageDigest digest, MemoryBuffer buffer) {
        if (buffer == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        digest.update(MemoryUtil.memByteBuffer(buffer.address, (int) buffer.size));
    }
}
