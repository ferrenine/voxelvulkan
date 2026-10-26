package voxel.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import voxel.common.Logger;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.ThreadLocalMemoryBuffer;
import voxel.common.world.Mapper;
import org.lwjgl.system.MemoryUtil;

public class SectionSerializer {
    public static final int STORAGE_VERSION = 0;

    private record SerializationCache(Long2ShortOpenHashMap lutMapCache, MemoryBuffer memoryBuffer) {
        public SerializationCache() {
            this(new Long2ShortOpenHashMap(1024), ThreadLocalMemoryBuffer.create(WorldSection.SECTION_VOLUME*2+WorldSection.SECTION_VOLUME*8+1024));
            this.lutMapCache.defaultReturnValue((short) -1);
        }
    }

    private static final ThreadLocal<SerializationCache> CACHE = ThreadLocal.withInitial(SerializationCache::new);

    //TODO: Cache like long2short and the short and other data to stop allocs
    /** Wire format: key | metadata(lutSize|nonEmptyChildren) | 32³ u16 LUT
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
        //5 bytes free

        MemoryUtil.memPutLong(metadataPtr, metadata);
        //TODO: do hash

        //TODO: rework the storage system to not need to do useless copies like this (this is an issue for serialization, deserialization has solved this already)
        return buffer.subSize(ptr-buffer.address).copy();
    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        long ptr = data.address;
        long key = MemoryUtil.memGetLong(ptr); ptr += 8;

        if (section.key != key) {
            //throw new IllegalStateException("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            Logger.error("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            return false;
        }

        final long metadata = MemoryUtil.memGetLong(ptr); ptr += 8;
        section.nonEmptyChildren = (byte) ((metadata>>>16)&0xFF);
        final long lutBasePtr = ptr + WorldSection.SECTION_VOLUME * 2;
        if (section.lvl == 0) {
            int nonEmptyBlockCount = 0;
            final var blockData = section.materializeData();//must not write into SHARED_AIR_DATA
            for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
                final short lutId = MemoryUtil.memGetShort(ptr); ptr += 2;
                final long blockId = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(lutId) * 8L);
                nonEmptyBlockCount += Mapper.isAir(blockId) ? 0 : 1;
                blockData[i] = blockId;
            }
            section.nonEmptyBlockCount = nonEmptyBlockCount;
            if (nonEmptyBlockCount > 0) {
                section.nonEmptyChildren = (byte) 0xFF;
            }
        } else {
            byte inferredChildren = section.nonEmptyChildren;
            final var blockData = section.materializeData();
            for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
                final long blockId = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(MemoryUtil.memGetShort(ptr)) * 8L); ptr += 2;
                blockData[i] = blockId;
                if (!Mapper.isAir(blockId)) {
                    int octant = ((i >> 4) & 1) | ((i >> 8) & 2) | ((i >> 12) & 4);
                    inferredChildren |= (byte) (1 << octant);
                }
            }
            section.nonEmptyChildren = inferredChildren;
        }
        ptr = lutBasePtr + (metadata & 0xFFFF) * 8L;
        return true;
    }
}
