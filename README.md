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
- **Renderer**: VulkanMod 0.6.7+
- **Java**: 21
- **Optional**: Beryl 0.2.0+

## Build

```bash
git clone https://github.com/ferrenine/voxelvulkan.git
cd voxelvulkan
./gradlew build
```

The build product is located at `build/libs/voxel-*.jar`.

## Acknowledgements

Special thanks to **LING** and **Cortex (MCRcortex)** for their research and implementation in the field of voxel LOD. This project is an independent continuation of the maintenance based on the Vulkan/Fabric port of **LING Horizons**, maintained and released by **ferrenine**.

## Staff List and Licensing

- **LING Horizons** was written by **LING**.
- The inspiration comes from the foundational voxel LOD concept and research pioneered by **Cortex (MCRcortex)**. Please note that the copyright of **Voxy** belongs to **MCRcortex**, and it is licensed under the **All Rights Reserved (ARR)** agreement.
- The independent code repository is released under the **GNU General Public License v3.0 (LGPL-3.0)**. The full text can be found in [LICENSE](LICENSE).

Thanks to the original work and open-source contributions of LING and MCRcortex.
