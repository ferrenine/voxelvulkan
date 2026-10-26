package voxel.common.config.compressors;

import voxel.common.util.MemoryBuffer;

public interface StorageCompressor {
    MemoryBuffer compress(MemoryBuffer saveData);

    MemoryBuffer decompress(MemoryBuffer saveData);

    void close();
}
