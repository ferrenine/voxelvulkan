# VoxelVulkan

A GPU-driven voxel LOD renderer for **Minecraft 1.21.11 (Fabric)**, running on **VulkanMod**, and optionally integrating **Beryl** shader pipeline.

## Features

- **GPU-driven hierarchical LOD**: Node traversal, occlusion culling, and draw command generation are completed in Vulkan compute, reducing CPU submission overhead.
- **Adaptive far LOD**: when the frame rate drops while looking across a large vista, the far LOD is temporarily coarsened above the configured sub-division size and returns once the view stabilises. It can be disabled in the display settings.
- **Vanilla-covered LOD0 culling**: LOD0 sections whose eight vanilla child sections have compiled meshes are skipped, because the vanilla pass already draws the same blocks. This removes duplicate near-field geometry without changing the image.
- **MDIC indirect drawing**: Use `vkCmdDrawIndexedIndirectCount` with GPU-side counting, batch submission of large sections.
- **Vulkan native resources and synchronization**: Persistent mapping upload/download streams, explicit pipeline barriers, descriptor ring, and deferred destruction, adapted to the frame queue of VulkanMod.
- **Optional Beryl integration**: Access Beryl's Vulkan shader pipeline and shadow rendering.

## System Requirements

- **Minecraft**: 1.21.11
- **Mod Loader**: Fabric Loader + Fabric API
- **Renderer**: VulkanMod 0.6.8+ (the build is compiled and tested against 0.6.8)
- **Java**: 21 (recommended)
- **GPU**: Vulkan 1.1+ with compute and `shaderInt64`
- **Tested configuration**: Windows x86_64 with an NVIDIA GeForce RTX 4060 Laptop GPU; AMD and Intel GPUs are untested.
- **Optional**: Beryl 0.2.1-alpha (shader pipeline integration)

The release jar targets Windows x86_64.

## Installation

1. Install Fabric Loader for Minecraft 1.21.11 and put Fabric API in `mods/`.
2. Install **VulkanMod 0.6.8+**. Voxel does not run on the vanilla OpenGL renderer.
3. Put the Voxel jar in `mods/`.
4. Optional: Beryl for shader-pipeline integration, Mod Menu for the config
   entry, and LambdaBetterGrass for connected grass sides at distance.

If distant terrain looks wrong after changing mods or Minecraft versions,
delete the per-world `voxel/` cache directory described below and let it
rebuild.

## Build

```bash
git clone https://github.com/ferrenine/voxelvulkan.git
cd voxelvulkan
./gradlew build
```

The build product is located at `build/libs/voxel-*.jar`. The prebuilt
`libs/` jars are development-only dependencies and are not shipped inside the
mod jar.

## Acknowledgements

Special thanks to **ANOUSIT Sysouphakhone (LING)** and **Cortex (MCRcortex)** for their research and implementation in the field of voxel LOD. This project is an independent continuation of the maintenance based on the Vulkan/Fabric port of **LING Horizons**, maintained and released by **ferrenine**.

## Staff List and Licensing

- **LING Horizons** was written by **ANOUSIT Sysouphakhone** (LING).
- The inspiration comes from the foundational voxel LOD concept and research pioneered by **Cortex (MCRcortex)**. Please note that the copyright of **Voxy** belongs to **MCRcortex**, and it is licensed under the **All Rights Reserved (ARR)** agreement.
- The independent code repository is released under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**. The full text can be found in [LICENSE](LICENSE).

Thanks to the original work and open-source contributions of ANOUSIT Sysouphakhone (LING) and MCRcortex.
