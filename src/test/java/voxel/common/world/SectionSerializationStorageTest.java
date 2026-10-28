package voxel.common.world;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import voxel.common.config.section.SectionSerializationStorage;
import voxel.common.config.storage.inmemory.MemoryStorageBackend;
import voxel.common.util.MemoryBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionSerializationStorageTest {

    private static final long KEY = WorldEngine.getWorldSectionId(0, 4, 5, 6);
    private static final long BLOCK = Mapper.composeMappingId((byte) 0x0F, 1, 0);

    @Test
    void corruptSectionIsQuarantinedInsteadOfDeleted() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        SectionSerializationStorage storage = new SectionSerializationStorage(backend);
        MemoryBuffer scratch = new MemoryBuffer(SectionSerializationUtil.BIGGEST_SERIALIZED_SECTION_SIZE + 1024L);
        try {
            WorldSection source = new WorldSection(0, 4, 5, 6, null);
            source.set(0, 0, 0, BLOCK);
            storage.saveSection(source);

            // Flip a payload byte while keeping the format header intact.
            MemoryBuffer stored = backend.getSectionData(KEY, scratch.createUntrackedUnfreeableReference());
            assertNotNull(stored);
            long lastByte = stored.address + stored.size - 1L;
            MemoryUtil.memPutByte(lastByte, (byte) (MemoryUtil.memGetByte(lastByte) ^ 0xFF));
            backend.setSectionData(KEY, stored);

            WorldSection target = new WorldSection(0, 4, 5, 6, null);
            assertTrue(storage.loadSection(target) < 0);
            assertTrue(Mapper.isAir(target._unsafeGetRawDataArray()[0]));

            // The live key is gone, the raw bytes are preserved under the
            // quarantine slot, and normal iteration does not expose them.
            assertNull(backend.getSectionData(KEY, scratch.createUntrackedUnfreeableReference()));
            assertNotNull(backend.getSectionData(KEY | 1L, scratch.createUntrackedUnfreeableReference()));

            List<Long> positions = new ArrayList<>();
            storage.iterateStoredSectionPositions(positions::add);
            assertTrue(positions.isEmpty());
        } finally {
            scratch.free();
            storage.close();
        }
    }

    @Test
    void healthySectionRoundTrips() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        SectionSerializationStorage storage = new SectionSerializationStorage(backend);
        try {
            WorldSection source = new WorldSection(0, 4, 5, 6, null);
            source.set(0, 0, 0, BLOCK);
            storage.saveSection(source);

            WorldSection target = new WorldSection(0, 4, 5, 6, null);
            assertTrue(storage.loadSection(target) >= 0);
            assertFalse(Mapper.isAir(target._unsafeGetRawDataArray()[0]));
        } finally {
            storage.close();
        }
    }
}
