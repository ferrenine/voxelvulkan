/**
 * LOD mesh generation: turns raw section cell data into GPU quads.
 *
 * <p>{@link voxel.client.core.rendering.building.RenderDataFactory} is the core:
 * per 32^3 section it prepares {@code sectionData} (packed quads, light byte at
 * bits 55..62, bit 63 = baked sun-shadow flag), repairs missing imported sky
 * light (column fill + bounded propagation incl. cross-boundary seeding), then
 * emits opaque / fluid / non-opaque faces into a quad buffer plus a parallel
 * per-corner light+AO buffer, returned as a {@code BuiltSection}.</p>
 */
package voxel.client.core.rendering.building;
