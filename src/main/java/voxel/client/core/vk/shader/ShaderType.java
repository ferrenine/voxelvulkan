package voxel.client.core.vk.shader;

import net.vulkanmod.vulkan.shader.SPIRVUtils;

/**
 * Shader stages. Vulkan compiles each stage on its own (no GL program object),
 * so the old GL enum value is replaced by the shaderc shader kind.
 */
public enum ShaderType {
    VERTEX(SPIRVUtils.ShaderKind.VERTEX_SHADER),
    FRAGMENT(SPIRVUtils.ShaderKind.FRAGMENT_SHADER),
    COMPUTE(SPIRVUtils.ShaderKind.COMPUTE_SHADER),
    MESH(null),
    TASK(null);

    public final SPIRVUtils.ShaderKind kind;

    ShaderType(SPIRVUtils.ShaderKind kind) {
        this.kind = kind;
    }
}
