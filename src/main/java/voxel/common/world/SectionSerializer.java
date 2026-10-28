package voxel.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.ThreadLocalMemoryBuffer;
import voxel.common.world.Mapper;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

public class SectionSerializer {
    /** New writes use the checksummed format. */
    public static final int STORAGE_VERSION = 1;
    /** Version 0 predates the checksum field and is still accepted. */
    public static final int LEGACY_STORAGE_VERSION = 0;

    private record SerializationCache(Long2ShortOpenHashMap lutMapCache, MemoryBuffer memoryBuffer) {
        public SerializationCache() {
            this(new Long2ShortOpenHashMap(1024), ThreadLocalMemoryBuffer.create(WorldSection.SECTION_VOLUME*2+WorldSection.SECTION_VOLUME*8+1024));
            this.lutMapCache.defaultReturnValue((short) -1);
        }
    }

    private static final ThreadLocal<SerializationCache> CACHE = ThreadLocal.withInitial(SerializationCache::new);

    //TODO: Cache like long2short and the short and other data to stop allocs
    /** Wire format: key | metadata(lutSize|nonEmptyChildren|storageVersion) | 32³ u16 LUT
     *  indices in LINEAR y-major order | LUT longs. (The Morton lin2z/z2lin
     *  helpers in SectionSerializationUtil are unrelated to this layout.) */
    public static MemoryBuffer serialize(WorldSection section) {
        var cache = CACHE.get();
        var data = section.data;

        Long2ShortOpenHashMap LUT = cache.lutMapCache; LUT.clear();

        MemoryBuffer buffer = cache.memoryBuffer().createUntrackedUnfreeableReference();
        long ptr = buffer.address;

        MemoryUtil.memPutLong(ptr, section.key); ptr += 8;
        long metadataPtr = ptr; ptr += 8;

        long blockPtr = ptr; ptr += WorldSection.SECTION_VOLUME*2;
        byte inferredChildren = 0;
        for (int i = 0; i < data.length; i++) {
            long block = data[i];
            if (!Mapper.isAir(block)) {
                int octant = ((i >> 4) & 1) | ((i >> 8) & 2) | ((i >> 12) & 4);
                inferredChildren |= (byte) (1 << octant);
            }
            short mapping = LUT.putIfAbsent(block, (short) LUT.size());
            if (mapping == -1) {
                mapping = (short) (LUT.size()-1);
                MemoryUtil.memPutLong(ptr, block); ptr+=8;
            }
            MemoryUtil.memPutShort(blockPtr, mapping); blockPtr+=2;
        }
        if (LUT.size() >= 1<<16) {
            throw new IllegalStateException();
        }

        byte nonEmptyChildren = section.getNonEmptyChildren();
        if (section.lvl == 0) {
            if (inferredChildren != 0) {
                nonEmptyChildren = (byte) 0xFF;
            }
        } else {
            nonEmptyChildren |= inferredChildren;
        }

        //TODO: note! can actually have the first (last?) byte of metadata be the storage version!
        long metadata = 0;
        metadata |= Integer.toUnsignedLong(LUT.size());//Bottom 2 bytes
        metadata |= Byte.toUnsignedLong(nonEmptyChildren)<<16;//Next byte
        metadata |= (long) (STORAGE_VERSION & 0xFF) << 24;//Storage version
        //Upper 4 bytes: CRC32C over everything except the checksum field.
        MemoryUtil.memPutLong(metadataPtr, metadata);

        final long serializedSize = ptr - buffer.address;
        final int checksum = computeChecksum(buffer.address, serializedSize);
        MemoryUtil.memPutInt(metadataPtr + 4, checksum);

        //TODO: rework the storage system to not need to do useless copies like this (this is an issue for serialization, deserialization has solved this already)
        return buffer.subSize(serializedSize).copy();
    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        if (data == null) {
            return false;
        }
        return deserialize(section, data.address, data.size);
    }

    /**
     * Deserializes a section from raw memory. The layout is trusted only after
     * its size and every LUT index have been validated: this method is also
     * reachable from the network path, where a corrupt or malicious payload
     * must not be able to turn an out-of-range LUT entry into an out-of-bounds
     * native read.
     */
    public static boolean deserialize(WorldSection section, long address, long size) {
        if (section == null || address == 0L || size < 16L) {
            return false;
        }

        long ptr = address;
        final long key = MemoryUtil.memGetLong(ptr); ptr += 8;

        if (section.key != key) {
            //throw new IllegalStateException("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            return false;
        }

        final long metadata = MemoryUtil.memGetLong(ptr); ptr += 8;
        final int version = (int) ((metadata >>> 24) & 0xFFL);
        if (version != LEGACY_STORAGE_VERSION && version != STORAGE_VERSION) {
            return false;
        }
        if (version == STORAGE_VERSION && (int) (metadata >>> 32) != computeChecksum(address, size)) {
            return false;
        }
        final int lutSize = (int) (metadata & 0xFFFFL);
        if (lutSize <= 0) {
            return false;
        }

        final long lutBasePtr = ptr + (long) WorldSection.SECTION_VOLUME * 2L;
        final long requiredSize = 16L + (long) WorldSection.SECTION_VOLUME * 2L + (long) lutSize * 8L;
        if (size < requiredSize) {
            return false;
        }

        // Validate every LUT index before touching the section, so a corrupt
        // payload cannot leave a partially written section behind.
        long indexPtr = ptr;
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            final int lutId = Short.toUnsignedInt(MemoryUtil.memGetShort(indexPtr)); indexPtr += 2;
            if (lutId >= lutSize) {
                return false;
            }
        }

        section.nonEmptyChildren = (byte) ((metadata >>> 16) & 0xFF);
        if (section.lvl == 0) {
            int nonEmptyBlockCount = 0;
            synchronized (section) {
                final var blockData = section.materializeData();//must not write into SHARED_AIR_DATA
                long readPtr = ptr;
                for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
                    final int lutId = Short.toUnsignedInt(MemoryUtil.memGetShort(readPtr)); readPtr += 2;
                    final long blockId = MemoryUtil.memGetLong(lutBasePtr + (long) lutId * 8L);
                    nonEmptyBlockCount += Mapper.isAir(blockId) ? 0 : 1;
                    blockData[i] = blockId;
                }
            }
            section.nonEmptyBlockCount = nonEmptyBlockCount;
            if (nonEmptyBlockCount > 0) {
                section.nonEmptyChildren = (byte) 0xFF;
            }
        } else {
            byte inferredChildren = section.nonEmptyChildren;
            synchronized (section) {
                final var blockData = section.materializeData();
                long readPtr = ptr;
                for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
                    final int lutId = Short.toUnsignedInt(MemoryUtil.memGetShort(readPtr)); readPtr += 2;
                    final long blockId = MemoryUtil.memGetLong(lutBasePtr + (long) lutId * 8L);
                    blockData[i] = blockId;
                    if (!Mapper.isAir(blockId)) {
                        int octant = ((i >> 4) & 1) | ((i >> 8) & 2) | ((i >> 12) & 4);
                        inferredChildren |= (byte) (1 << octant);
                    }
                }
            }
            section.nonEmptyChildren = inferredChildren;
        }
        return true;
    }

    /**
     * CRC32C over the serialized section, skipping the four checksum bytes in
     * the metadata word. The checksum detects silent storage corruption; the
     * bounds checks in {@link #deserialize} remain the security boundary.
     */
    private static int computeChecksum(long address, long size) {
        if (size < 16L) {
            return 0;
        }
        CRC32C checksum = new CRC32C();
        ByteBuffer head = MemoryUtil.memByteBuffer(address, 12);
        checksum.update(head);
        if (size > 16L) {
            ByteBuffer tail = MemoryUtil.memByteBuffer(address + 16L, (int) (size - 16L));
            checksum.update(tail);
        }
        return (int) checksum.getValue();
    }
}
