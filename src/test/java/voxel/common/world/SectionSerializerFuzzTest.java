package voxel.common.world;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import voxel.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Exercises the untrusted-input path with random and mutated payloads. The
 * contract is deliberately weak (any result is fine) but a crash, an
 * out-of-bounds native read or an exception escaping is not.
 */
class SectionSerializerFuzzTest {

    private static final long STONE = Mapper.composeMappingId((byte) 0x0F, 1, 0);

    @Test
    void randomPayloadsDoNotEscapeAsExceptions() {
        Random random = new Random(0x5EEDL);
        for (int i = 0; i < 1_000; i++) {
            int size = random.nextInt(2_048);
            MemoryBuffer buffer = new MemoryBuffer(size);
            try {
                if (size > 0) {
                    ByteBuffer bytes = MemoryUtil.memByteBuffer(buffer.address, size);
                    byte[] data = new byte[size];
                    random.nextBytes(data);
                    bytes.put(data);
                }
                WorldSection section = new WorldSection(0, 0, 0, 0, null);
                assertDoesNotThrow(() -> SectionSerializer.deserialize(section, buffer));
            } finally {
                buffer.free();
            }
        }
    }

    @Test
    void mutatedValidPayloadsDoNotEscapeAsExceptions() {
        WorldSection source = new WorldSection(0, 0, 0, 0, null);
        source.set(0, 0, 0, STONE);

        MemoryBuffer serialized = SectionSerializer.serialize(source);
        try {
            Random random = new Random(0xC0FFEEL);
            for (int i = 0; i < 200; i++) {
                MemoryBuffer copy = serialized.copy();
                try {
                    int offset = random.nextInt((int) copy.size);
                    byte value = MemoryUtil.memGetByte(copy.address + offset);
                    MemoryUtil.memPutByte(copy.address + offset, (byte) (value ^ (1 << random.nextInt(8))));

                    WorldSection decoded = new WorldSection(0, 0, 0, 0, null);
                    assertDoesNotThrow(() -> SectionSerializer.deserialize(decoded, copy));
                } finally {
                    copy.free();
                }
            }
        } finally {
            serialized.free();
        }
    }
}
