/**
 * Model bakery: turns Minecraft blockstates into LOD-renderable quad metadata.
 *
 * <p>{@link voxel.client.core.model.ModelFactory} bakes each blockstate's
 * model, computes per-face occlusion/light/AO metadata (the packed
 * {@code metadata} long consumed by the mesher and shaders) and assigns model
 * ids. {@code bakery} contains the off-screen texture/position capture used to
 * build the atlas and per-tile geometry info.</p>
 */
package voxel.client.core.model;
