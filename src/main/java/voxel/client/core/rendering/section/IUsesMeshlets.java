package voxel.client.core.rendering.section;

//A dummy empty interface to mark that the geometry manager as using meshlets, so that the mesh generator emits as meshlets
//AUDIT-DEAD: no class implements this — the emitMeshlets flag it gates is
// permanently false; kept as the seam for a future meshlet path.
public interface IUsesMeshlets {
}
