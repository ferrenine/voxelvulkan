# VoxelVulkan

A GPU-driven voxel LOD renderer for **Minecraft 1.21.11 (Fabric)**, running on **VulkanMod**, and optionally integrating **Beryl** shader pipeline.

## Features

- **GPU-driven hierarchical LOD**: Node traversal, occlusion culling, and draw command generation are completed in Vulkan compute, reducing CPU submission overhead.
- **MDIC indirect drawing**: Use `vkCmdDrawIndexedIndirectCount` with GPU-side counting, batch submission of large sections.
- **Vulkan native resources and synchronization**: Persistent mapping upload/download streams, explicit pipeline barriers, descriptor ring, and deferred destruction, adapted to the frame queue of VulkanMod.
- **Optional Beryl integration**: Access Beryl's Vulkan shader pipeline and shadow rendering.

## System Requirements

- **Minecraft**: 1.21.11
- **Mod Loader**: Fabric Loader + Fabric API
- **Renderer**: VulkanMod 0.6.8+ (the build is compiled and tested against 0.6.8)
- **Java**: 21
- **GPU**: Vulkan 1.1+ with compute and `shaderInt64`; 4 GB of VRAM is the practical minimum, 8 GB+ recommended
- **Platforms**: Windows x86_64, Linux x86_64/aarch64 (glibc or musl), macOS x86_64/arm64
- **Optional**: Beryl 0.2.1-alpha (shader pipeline integration)

The release jar contains desktop-only storage natives; 32-bit and other
architectures are not packaged.

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

## Storage and Cache

LOD data is stored per world:

- Singleplayer: `<world>/voxel/`
- Multiplayer: `.minecraft/.voxel/saves/<server-address>/`

Deleting that directory is safe; it is rebuilt from the world the next time
the area is loaded. The default backend is RocksDB with ZSTD compression.

## Known Limitations

- Server-side LOD streaming is not implemented in this client-only build. The
  client registers the protocol channels so a companion server mod can be
  added later, but no payloads are sent or accepted without one.
- Translucent blocks (for example stained glass) can show seams at section
  borders in some configurations.
- Beryl integration targets `0.2.1-alpha`; other Beryl builds may expose
  different internals and are not tested.
- SSAO is currently disabled; the pass is kept for the colour copy used by the
  final blit and SSR.
- Temporal antialiasing is not implemented yet, so distant water and thin
  geometry can shimmer while moving.
- The model atlas has a 65,535-ID budget. Vanilla and normal modpacks fit, but
  extremely large block-state sets can exhaust it.

## Acknowledgements

Special thanks to **LING** and **Cortex (MCRcortex)** for their research and implementation in the field of voxel LOD. This project is an independent continuation of the maintenance based on the Vulkan/Fabric port of **LING Horizons**, maintained and released by **ferrenine**.

## Staff List and Licensing

- **LING Horizons** was written by **LING**.
- The inspiration comes from the foundational voxel LOD concept and research pioneered by **Cortex (MCRcortex)**. Please note that the copyright of **Voxy** belongs to **MCRcortex**, and it is licensed under the **All Rights Reserved (ARR)** agreement.
- The independent code repository is released under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**. The full text can be found in [LICENSE](LICENSE).

Thanks to the original work and open-source contributions of LING and MCRcortex.
