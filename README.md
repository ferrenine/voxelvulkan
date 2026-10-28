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

## Performance Metrics

The renderer can record a frame-time distribution and the counters needed to
diagnose hitches:

```text
/vx perf start
/vx perf stop
/vx perf reset
/vx perf dump
```

`dump` writes `voxel-perf/<timestamp>.json` and `.csv` under the game
directory. The JSON includes P50/P95/P99/P99.9 frame times, 1% / 0.1% lows,
spike reasons (`DEVICE_WAIT`, `UPLOAD_OVERFLOW`, `MESH_BURST`,
`PIPELINE_CREATE`, `DISK_READ`, `DISK_WRITE`, `HEAP_GROW`, `GC_PAUSE`),
barrier/transfer counters, mesh and geometry queues, native/VRAM usage and GC
time. The CSV contains one row per frame with its spike mask.

The same session can be enabled from the VulkanMod settings page
(`Diagnostics → Performance Metrics`). Recording is off by default; the hot
path is one ring-buffer write per frame.

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

Special thanks to **ANOUSIT Sysouphakhone (LING)** and **Cortex (MCRcortex)** for their research and implementation in the field of voxel LOD. This project is an independent continuation of the maintenance based on the Vulkan/Fabric port of **LING Horizons**, maintained and released by **ferrenine**.

## Staff List and Licensing

- **LING Horizons** was written by **ANOUSIT Sysouphakhone** (LING).
- The inspiration comes from the foundational voxel LOD concept and research pioneered by **Cortex (MCRcortex)**. Please note that the copyright of **Voxy** belongs to **MCRcortex**, and it is licensed under the **All Rights Reserved (ARR)** agreement.
- The independent code repository is released under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**. The full text can be found in [LICENSE](LICENSE).

Thanks to the original work and open-source contributions of ANOUSIT Sysouphakhone (LING) and MCRcortex.
