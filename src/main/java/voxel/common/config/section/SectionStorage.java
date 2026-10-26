package voxel.common.config.section;

import voxel.common.config.IMappingStorage;
import voxel.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);
}
