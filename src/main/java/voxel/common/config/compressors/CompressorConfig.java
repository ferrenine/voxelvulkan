package voxel.common.config.compressors;

import voxel.common.config.ConfigBuildCtx;
import voxel.common.config.Serialization;

public abstract class CompressorConfig {
    static {
        Serialization.CONFIG_TYPES.add(CompressorConfig.class);
    }

    public abstract StorageCompressor build(ConfigBuildCtx ctx);
}
