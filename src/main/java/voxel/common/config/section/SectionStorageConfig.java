package voxel.common.config.section;

import voxel.common.config.ConfigBuildCtx;
import voxel.common.config.Serialization;

public abstract class SectionStorageConfig {
    static {
        Serialization.CONFIG_TYPES.add(SectionStorageConfig.class);
    }

    public abstract SectionStorage build(ConfigBuildCtx ctx);
}
