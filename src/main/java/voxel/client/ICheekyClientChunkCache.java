package voxel.client;

import net.minecraft.world.level.chunk.LevelChunk;

public interface ICheekyClientChunkCache {
    LevelChunk voxel$cheekyGetChunk(int x, int z);
}
