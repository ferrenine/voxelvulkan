/**
 * Section draw backends: how built LOD sections are actually drawn.
 *
 * <p>{@code backend} holds renderer implementations — notably the MDIC
 * (multi-draw-indirect-count) path under {@code backend.mdic} which is the
 * production GPU-driven path feeding {@code lod/vk} shaders from section quad
 * buffers produced by {@code rendering.building}.</p>
 */
package voxel.client.core.rendering.section;
