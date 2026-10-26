/**
 * Common (side-agnostic) implementation glue: shared instance state,
 * configuration store, world importers and the common mixin set.
 *
 * <p>{@link voxel.commonimpl.VoxelCommon} / {@link voxel.commonimpl.VoxelInstance}
 * are the process-level singletons holding world engine, mapper and config.
 * {@link voxel.commonimpl.importers.WorldImporter} (+{@code DHImporter}) convert
 * external world data into the section store. Mixin classes here touch vanilla
 * world lifecycle so the engine can ingest/save with the game.</p>
 */
package voxel.commonimpl;
