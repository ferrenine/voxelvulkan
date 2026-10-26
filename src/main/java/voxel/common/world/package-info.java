/**
 * Voxel world data model: sections, engines, storage and ingestion.
 *
 * <p>The LOD world is organised into {@code 32^3} cell sections
 * ({@link voxel.common.world.WorldSection}) addressed by packed long keys
 * ({@link voxel.common.world.WorldEngine#getWorldSectionId}). LOD level 0 is
 * block resolution; each level up halves resolution per axis. Each cell is a
 * packed {@code long} — see {@link voxel.common.world.Mapper} for the bit
 * layout (blockId / biomeId / light).</p>
 *
 * <p>{@link voxel.common.world.WorldEngine} owns section lifecycle, dirty
 * tracking and save callbacks. {@link voxel.common.world.WorldUpdater} ingests
 * Minecraft chunk data into sections. {@link voxel.common.world.SectionSerializationUtil}
 * (compression utils) and {@link voxel.common.world.SectionSerializer} (section
 * serialization) handle persistence. {@link voxel.common.world.Mipper} merges
 * 2x2x2 cells into the next LOD level.</p>
 */
package voxel.common.world;
