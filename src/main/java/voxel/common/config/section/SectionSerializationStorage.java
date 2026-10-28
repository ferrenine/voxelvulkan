package voxel.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import voxel.common.Logger;
import voxel.common.config.ConfigBuildCtx;
import voxel.common.config.storage.StorageBackend;
import voxel.common.config.storage.StorageConfig;
import voxel.common.util.ThreadLocalMemoryBuffer;
import voxel.common.world.SectionSerializationUtil;
import voxel.common.world.SectionSerializer;
import voxel.common.world.WorldEngine;
import voxel.common.world.WorldSection;
import voxel.common.world.Mapper;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.LongConsumer;

public class SectionSerializationStorage extends SectionStorage {
    /**
     * Section keys leave their low four bits free (see WorldEngine's key
     * layout). Setting bit 0 turns a key into a quarantine slot without
     * colliding with any live section.
     */
    private static final long QUARANTINE_BIT = 1L;

    private final StorageBackend backend;
    public SectionSerializationStorage(StorageBackend storageBackend) {
        this.backend = storageBackend;
    }

    private static final ThreadLocalMemoryBuffer MEMORY_CACHE = new ThreadLocalMemoryBuffer(SectionSerializationUtil.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    public int loadSection(WorldSection into) {
        var data = this.backend.getSectionData(into.key, MEMORY_CACHE.get().createUntrackedUnfreeableReference());
        if (data != null) {
            if (!SectionSerializer.deserialize(into, data)) {
                // Keep the damaged bytes for diagnosis instead of deleting
                // them. The live key is removed; level 0 is rebuilt by the
                // next chunk ingest and higher levels rebuild when their
                // children change.
                try {
                    this.backend.setSectionData(into.key | QUARANTINE_BIT, data);
                } catch (Exception e) {
                    Logger.error("Failed to quarantine corrupt section " + WorldEngine.pprintPos(into.key), e);
                }
                this.backend.deleteSectionData(into.key);
                synchronized (into) {
                    Arrays.fill(into.materializeData(), Mapper.AIR);
                }
                Logger.error("Section " + WorldEngine.pprintPos(into.key)
                        + " was corrupt; quarantined the raw data and reset the section to air. "
                        + "Level 0 rebuilds on the next chunk ingest; higher levels rebuild when their children change.");
                return -1;
            } else {
                return 0;
            }
        } else {
            //TODO: if we need to fetch an lod from a server, send the request here and block until the request is finished
            // the response should be put into the local db so that future data can just use that
            // the server can also send arbitrary updates to the client for arbitrary lods
            return 1;
        }
    }


    @Override
    public void saveSection(WorldSection section) {
        var saveData = SectionSerializer.serialize(section);
        this.backend.setSectionData(section.key, saveData);
        saveData.free();
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.backend.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return this.backend.getIdMappingsData();
    }

    @Override
    public void flush() {
        this.backend.flush();
    }

    @Override
    public void close() {
        this.backend.close();
    }

    @Override
    public void iterateStoredSectionPositions(LongConsumer consumer) {
        this.backend.iterateStoredSectionPositions(key -> {
            // Quarantine slots use one of the unused low key bits; they are
            // diagnostics, not live sections.
            if ((key&0xFL) == 0) {
                consumer.accept(key);
            }
        });
    }

    public static class Config extends SectionStorageConfig {
        public StorageConfig storage;

        @Override
        public SectionStorage build(ConfigBuildCtx ctx) {
            return new SectionSerializationStorage(this.storage.build(ctx));
        }

        public static String getConfigTypeName() {
            return "Serializer";
        }
    }
}
