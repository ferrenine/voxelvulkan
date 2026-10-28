package voxel.common.world;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import voxel.common.util.MemoryBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionSerializerTest {

    private static final long STONE = Mapper.composeMappingId((byte) 0x0F, 1, 0);

    @Test
    void roundTripsBlockData() {
        WorldSection source = new WorldSection(0, 3, -2, 7, null);
        source.set(0, 0, 0, STONE);

        MemoryBuffer serialized = SectionSerializer.serialize(source);
        try {
            WorldSection decoded = new WorldSection(0, 3, -2, 7, null);

            assertTrue(SectionSerializer.deserialize(decoded, serialized));
            assertEquals(STONE, decoded._unsafeGetRawDataArray()[0]);
            assertEquals(1, decoded.getNonEmptyBlockCount());
        } finally {
            serialized.free();
        }
    }

    @Test
    void rejectsTruncatedPayload() {
        WorldSection source = new WorldSection(0, 0, 0, 0, null);
        source.set(0, 0, 0, STONE);

        MemoryBuffer serialized = SectionSerializer.serialize(source);
        try {
            MemoryBuffer legacy = asLegacy(serialized);
            try {
                WorldSection decoded = new WorldSection(0, 0, 0, 0, null);
                assertFalse(SectionSerializer.deserialize(decoded, legacy.address, 16));
            } finally {
                legacy.free();
            }
        } finally {
            serialized.free();
        }
    }

    @Test
    void rejectsOutOfRangeLutIndex() {
        WorldSection source = new WorldSection(0, 0, 0, 0, null);
        source.set(0, 0, 0, STONE);

        MemoryBuffer serialized = SectionSerializer.serialize(source);
        try {
            MemoryBuffer legacy = asLegacy(serialized);
            try {
                // The 32^3 LUT indices start right after key + metadata.
                MemoryUtil.memPutShort(legacy.address + 16L, (short) 0xFFFF);

                WorldSection decoded = new WorldSection(0, 0, 0, 0, null);
                assertFalse(SectionSerializer.deserialize(decoded, legacy));
            } finally {
                legacy.free();
            }
        } finally {
            serialized.free();
        }
    }

    @Test
    void rejectsUnknownStorageVersion() {
        WorldSection source = new WorldSection(0, 0, 0, 0, null);
        source.set(0, 0, 0, STONE);

        MemoryBuffer serialized = SectionSerializer.serialize(source);
        try {
            MemoryBuffer future = serialized.copy();
            try {
                long metadata = MemoryUtil.memGetLong(future.address + 8L);
                metadata = (metadata & ~(0xFFL << 24)) | (2L << 24);
                MemoryUtil.memPutLong(future.address + 8L, metadata);

                WorldSection decoded = new WorldSection(0, 0, 0, 0, null);
                assertFalse(SectionSerializer.deserialize(decoded, future));
            } finally {
                future.free();
            }
        } finally {
            serialized.free();
        }
    }

    @Test
    void rejectsChecksumMismatch() {
        WorldSection source = new WorldSection(0, 0, 0, 0, null);
        source.set(0, 0, 0, STONE);

        MemoryBuffer serialized = SectionSerializer.serialize(source);
        try {
            MemoryBuffer corrupt = serialized.copy();
            try {
                long lastByte = corrupt.address + corrupt.size - 1L;
                byte value = MemoryUtil.memGetByte(lastByte);
                MemoryUtil.memPutByte(lastByte, (byte) (value ^ 0xFF));

                WorldSection decoded = new WorldSection(0, 0, 0, 0, null);
                assertFalse(SectionSerializer.deserialize(decoded, corrupt));
            } finally {
                corrupt.free();
            }
        } finally {
            serialized.free();
        }
    }

    /** Clears the version and checksum so a test can exercise the legacy path. */
    private static MemoryBuffer asLegacy(MemoryBuffer source) {
        MemoryBuffer legacy = source.copy();
        long metadata = MemoryUtil.memGetLong(legacy.address + 8L);
        metadata &= ~(0xFFL << 24); // version = 0
        metadata &= 0x00000000FFFFFFFFL; // checksum = 0
        MemoryUtil.memPutLong(legacy.address + 8L, metadata);
        return legacy;
    }
}
