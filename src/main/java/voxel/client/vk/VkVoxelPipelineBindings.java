package voxel.client.vk;

import net.vulkanmod.vulkan.texture.VulkanImage;

import java.nio.ByteBuffer;

/**
 * Common descriptor-binding surface of {@link VkVoxelComputePipeline} and
 * {@link VkVoxelGraphicsPipeline}; lets {@code AutoBindingShader} write its
 * collected bindings without caring which kind of pipeline backs the shader.
 */
public interface VkVoxelPipelineBindings {

    void setStorageBuffer(int binding, long bufferHandle, long offset, long size);

    void setUniformBuffer(int binding, long bufferHandle, long offset, long size);

    void setStorageImage(int binding, VkVoxelImage image);

    void setStorageImageView(int binding, long viewHandle);

    void setSampledImage(int binding, VkVoxelImage image);

    void setSampledImage(int binding, long viewHandle, long samplerHandle);

    void setSampledImage(int binding, VulkanImage image);

    void setPushConstants(ByteBuffer data, int bytes);

    void clearBinding(int binding);
}
