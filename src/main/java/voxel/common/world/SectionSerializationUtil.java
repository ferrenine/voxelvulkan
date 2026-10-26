package voxel.common.world;

/**
 * Shared constants for section (de)serialization. The serializer itself lives
 * in {@link SectionSerializer}; compressors size their scratch buffers off
 * {@link #BIGGEST_SERIALIZED_SECTION_SIZE}.
 */
public final class SectionSerializationUtil {
    private SectionSerializationUtil() {}

    public static final int BIGGEST_SERIALIZED_SECTION_SIZE = 32 * 32 * 32 * 8 * 2 + 8;
}
