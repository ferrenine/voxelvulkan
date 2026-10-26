# VoxelVulkan

一个面向 **Minecraft 1.21.11（Fabric）** 的 GPU 驱动体素 LOD 渲染器，运行在 **VulkanMod** 上，并可选集成 **Beryl** 着色器管线。

## 特性

- **GPU 驱动的层级 LOD**：节点遍历、遮挡剔除与绘制命令生成在 Vulkan compute 中完成，减少 CPU 提交开销。
- **MDIC 间接绘制**：使用 `vkCmdDrawIndexedIndirectCount` 与 GPU 端计数，批量提交大量 section。
- **Vulkan 原生资源与同步**：持久映射上传/下载流、显式 pipeline barrier、descriptor ring 和延迟销毁，适配 VulkanMod 的帧队列。
- **可选 Beryl 集成**：接入 Beryl 的 Vulkan 着色器管线与阴影渲染。

## 环境要求

- **Minecraft**：1.21.11
- **Mod Loader**：Fabric Loader + Fabric API
- **渲染器**：VulkanMod 0.6.7+
- **Java**：21
- **可选**：Beryl 0.2.0+

## 构建

```bash
git clone https://github.com/ferrenine/voxelvulkan.git
cd voxelvulkan
./gradlew build
```

构建产物位于 `build/libs/voxel-*.jar`。

## 致谢

特别感谢 **LING** 与 **Cortex（MCRcortex）** 在体素 LOD 领域的研究和实现。本项目是在 **LING Horizons** 的 Vulkan/Fabric 移植基础上继续维护的独立项目，由 **ferrenine** 维护与发布。

## 制作人员名单与授权

- **LING Horizons** 由 **LING** 撰写。
- 灵感来源于 **Cortex（MCRcortex）** 开创的基础体素 LOD 概念和研究。请注意，**Voxy** 版权归 **MCRcortex** 所有，采用了**所有权利保留（ARR）**许可协议。
- 独立代码库以 **GNU 宽泛公共许可证 v3.0（LGPL-3.0）** 发布。完整文本见 [LICENSE](LICENSE)。

感谢 LING 和 MCRcortex 的原创工作与开源贡献。
