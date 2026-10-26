package voxel.client.core.vk.shader;

import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkDebug;
import voxel.client.core.vk.VkTexture;
import voxel.client.vk.VkVoxel;
import voxel.client.vk.VkVoxelComputePipeline;
import voxel.client.vk.VkVoxelGraphicsPipeline;
import voxel.client.vk.VkVoxelPipelineBindings;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;

/**
 * Vulkan replacement for the auto-binding GL shader.
 *
 * <p>In GL the shader bound its SSBOs/UBOs/textures at bind time via
 * {@code glBindBufferBase}/{@code glBindTextureUnit}. In Vulkan those are
 * descriptor set bindings, so the collected bindings are turned into a
 * descriptor set layout when the pipeline is first used (one rebuild if the set
 * of bindings changes later).</p>
 *
 * <p>Works for both compute shaders and vertex/fragment (graphics) shaders.</p>
 */
public class AutoBindingShader extends Shader {

    private record BufferBinding(boolean uniform, int index, VkBuffer buffer, long rawHandle, long offset, long size) {
    }

    /** {@code view} overrides the texture's default view when non-zero. */
    private record TextureBinding(int unit, int sampler, VkTexture texture, long view) {
    }

    private record StorageImageBinding(int unit, VkTexture texture, int level) {
    }

    private record RawImageBinding(int unit, net.vulkanmod.vulkan.texture.VulkanImage image, long sampler) {
    }

    private final Map<String, String> defines;
    private final List<BufferBinding> bindings = new ArrayList<>();
    private final List<TextureBinding> textureBindings = new ArrayList<>();
    private final List<StorageImageBinding> storageImageBindings = new ArrayList<>();
    private final List<RawImageBinding> rawImageBindings = new ArrayList<>();

    private final ByteBuffer pushConstants = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
    private int pushConstantsBytes = 0;

    private VkVoxelPipelineBindings activePipeline;
    private String declaredSignature;

    AutoBindingShader(Builder<AutoBindingShader> builder, Map<ShaderType, String> sources) {
        super(sources);
        this.defines = builder.defines;
    }

    public AutoBindingShader name(String name) {
        this.debugName = name;
        return VkDebug.name(name, this);
    }

    public AutoBindingShader ssboIf(String define, VkBuffer buffer) {
        if (this.defines.containsKey(define)) {
            return this.ssbo(define, buffer);
        }
        return this;
    }

    public AutoBindingShader ssbo(int index, VkBuffer binding) {
        return this.ssbo(index, binding, 0, -1);
    }

    public AutoBindingShader ssbo(String define, VkBuffer binding) {
        //AUDIT-FIXME: missing define → NPE/NumberFormatException with zero
        // context about which name failed — wrap with the define name.
        return this.ssbo(Integer.parseInt(this.defines.get(define)), binding, 0, -1);
    }

    public AutoBindingShader ssbo(int index, VkBuffer buffer, long offset) {
        return this.ssbo(index, buffer, offset, -1);
    }

    public AutoBindingShader ssbo(int index, VkBuffer buffer, long offset, long size) {
        this.insertOrReplaceBinding(new BufferBinding(false, index, buffer, buffer.id, offset, size));
        return this;
    }

    /** Binds a raw {@code VkBuffer} handle (e.g. from {@code UploadStream}) as an SSBO range. */
    public AutoBindingShader ssboRaw(int index, long bufferHandle, long offset, long size) {
        this.insertOrReplaceBinding(new BufferBinding(false, index, null, bufferHandle, offset, size));
        return this;
    }

    public AutoBindingShader ubo(String define, VkBuffer buffer) {
        return this.ubo(Integer.parseInt(this.defines.get(define)), buffer);
    }

    public AutoBindingShader ubo(int index, VkBuffer buffer) {
        return this.ubo(index, buffer, 0);
    }

    public AutoBindingShader ubo(int index, VkBuffer buffer, long offset) {
        this.insertOrReplaceBinding(new BufferBinding(true, index, buffer, buffer.id, offset, -1));
        return this;
    }

    private void insertOrReplaceBinding(BufferBinding binding) {
        for (int i = 0; i < this.bindings.size(); i++) {
            var entry = this.bindings.get(i);
            if (entry.uniform() == binding.uniform() && entry.index() == binding.index()) {
                this.bindings.set(i, binding);
                return;
            }
        }
        this.bindings.add(binding);
    }

    public AutoBindingShader texture(String define, VkTexture texture) {
        return this.texture(define, -1, texture);
    }

    public AutoBindingShader texture(String define, int sampler, VkTexture texture) {
        return this.texture(Integer.parseInt(this.defines.get(define)), sampler, texture);
    }

    public AutoBindingShader texture(int unit, int sampler, VkTexture texture) {
        return this.texture(unit, sampler, texture, 0L);
    }

    /** Binds {@code texture} sampled through {@code view} (0 = the texture's default view). */
    public AutoBindingShader texture(int unit, int sampler, VkTexture texture, long view) {
        this.rawImageBindings.removeIf(entry -> entry.unit() == unit);
        for (int i = 0; i < this.textureBindings.size(); i++) {
            var entry = this.textureBindings.get(i);
            if (entry.unit() == unit) {
                this.textureBindings.set(i, new TextureBinding(unit, sampler, texture, view));
                return this;
            }
        }
        this.textureBindings.add(new TextureBinding(unit, sampler, texture, view));
        return this;
    }

    /** Binds a {@link VkTexture} as a storage image ({@code image2D} in GLSL). */
    public AutoBindingShader storageImage(int unit, VkTexture texture) {
        return this.storageImageLevel(unit, texture, -1);
    }

    /** Binds a specific mip level of a {@link VkTexture} as a storage image ({@code image2D} in GLSL). */
    public AutoBindingShader storageImageLevel(int unit, VkTexture texture, int level) {
        for (int i = 0; i < this.storageImageBindings.size(); i++) {
            var entry = this.storageImageBindings.get(i);
            if (entry.unit() == unit) {
                this.storageImageBindings.set(i, new StorageImageBinding(unit, texture, level));
                return this;
            }
        }
        this.storageImageBindings.add(new StorageImageBinding(unit, texture, level));
        return this;
    }

    /** Binds a raw VulkanMod image (e.g. the vanilla lightmap) to a descriptor binding. */
    public AutoBindingShader image(int unit, net.vulkanmod.vulkan.texture.VulkanImage image) {
        long sampler = image != null ? image.getSampler() : 0L;
        return this.imageSampled(unit, image, sampler);
    }

    /** Binds a raw VulkanMod image with an explicit sampler (e.g. the depth shadow map). */
    public AutoBindingShader imageSampled(int unit, net.vulkanmod.vulkan.texture.VulkanImage image, long sampler) {
        this.textureBindings.removeIf(entry -> entry.unit() == unit);
        for (int i = 0; i < this.rawImageBindings.size(); i++) {
            var entry = this.rawImageBindings.get(i);
            if (entry.unit() == unit) {
                this.rawImageBindings.set(i, new RawImageBinding(unit, image, sampler));
                return this;
            }
        }
        this.rawImageBindings.add(new RawImageBinding(unit, image, sampler));
        return this;
    }

    // ------------------------------------------------------------------
    // Push constant helpers (up to 128 bytes total)
    // ------------------------------------------------------------------

    public AutoBindingShader pushUint(int byteOffset, int value) {
        this.pushConstants.putInt(byteOffset, value);
        this.pushConstantsBytes = Math.max(this.pushConstantsBytes, byteOffset + 4);
        return this;
    }

    public AutoBindingShader pushFloat2(int byteOffset, float x, float y) {
        this.pushConstants.putFloat(byteOffset, x);
        this.pushConstants.putFloat(byteOffset + 4, y);
        this.pushConstantsBytes = Math.max(this.pushConstantsBytes, byteOffset + 8);
        return this;
    }

    public AutoBindingShader pushFloat4(int byteOffset, float x, float y, float z, float w) {
        this.pushConstants.putFloat(byteOffset, x);
        this.pushConstants.putFloat(byteOffset + 4, y);
        this.pushConstants.putFloat(byteOffset + 8, z);
        this.pushConstants.putFloat(byteOffset + 12, w);
        this.pushConstantsBytes = Math.max(this.pushConstantsBytes, byteOffset + 16);
        return this;
    }

    public AutoBindingShader pushMat4(int byteOffset, Matrix4fc matrix) {
        matrix.getToAddress(MemoryUtil.memAddress(this.pushConstants) + byteOffset);
        this.pushConstantsBytes = Math.max(this.pushConstantsBytes, byteOffset + 64);
        return this;
    }

    @Override
    public void bind() {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        this.ensurePipeline();
        this.applyBindings();
        if (this.activePipeline instanceof VkVoxelGraphicsPipeline graphics) {
            graphics.prepare(cb);
        } else if (this.activePipeline instanceof VkVoxelComputePipeline compute) {
            compute.bind(cb);
        }
    }

    private void ensurePipeline() {
        String signature = this.bindingSignature();
        if (this.activePipeline != null && signature.equals(this.declaredSignature)) {
            return;
        }
        if (this.activePipeline != null) {
            // The pipeline is destroyed immediately below; do not keep it in a
            // "retired" list or free() would destroy it a second time.
            if (this.activePipeline instanceof VkVoxelComputePipeline) {
                this.destroyComputePipeline();
            } else {
                this.destroyGraphicsPipeline();
            }
            this.activePipeline = null;
            this.declaredSignature = null;
        }

        if (this.hasGraphicsStages()) {
            this.activePipeline = this.createGraphicsPipeline(this::declareBindings);
        } else if (this.hasComputeStage()) {
            this.activePipeline = this.createComputePipeline(this::declareBindings);
        } else {
            throw new IllegalStateException("Shader '" + this.debugName + "' has no usable stages");
        }
        this.declaredSignature = signature;
    }

    private void declareBindings(VkVoxelComputePipeline pipeline) {
        for (BufferBinding binding : this.bindings) {
            if (binding.uniform()) {
                pipeline.uniformBuffer(binding.index());
            } else {
                pipeline.storageBuffer(binding.index());
            }
        }
        for (TextureBinding binding : this.textureBindings) {
            pipeline.sampledImage(binding.unit());
        }
        for (StorageImageBinding binding : this.storageImageBindings) {
            pipeline.storageImage(binding.unit());
        }
        for (RawImageBinding binding : this.rawImageBindings) {
            pipeline.sampledImage(binding.unit());
        }
    }

    private void declareBindings(VkVoxelGraphicsPipeline pipeline) {
        for (BufferBinding binding : this.bindings) {
            if (binding.uniform()) {
                pipeline.uniformBuffer(binding.index());
            } else {
                pipeline.storageBuffer(binding.index());
            }
        }
        for (TextureBinding binding : this.textureBindings) {
            pipeline.sampledImage(binding.unit());
        }
        for (StorageImageBinding binding : this.storageImageBindings) {
            pipeline.storageImage(binding.unit());
        }
        for (RawImageBinding binding : this.rawImageBindings) {
            pipeline.sampledImage(binding.unit());
        }
    }

    private String bindingSignature() {
        StringBuilder sb = new StringBuilder();
        for (BufferBinding binding : this.bindings) {
            sb.append(binding.uniform() ? 'u' : 's').append(binding.index()).append(',');
        }
        for (StorageImageBinding binding : this.storageImageBindings) {
            sb.append('w').append(binding.unit()).append(',');
        }
        int sampledMask = 0;
        for (TextureBinding binding : this.textureBindings) {
            sampledMask |= (1 << binding.unit());
        }
        for (RawImageBinding binding : this.rawImageBindings) {
            sampledMask |= (1 << binding.unit());
        }
        for (int m = sampledMask; m != 0; m &= (m - 1)) {
            sb.append('t').append(Integer.numberOfTrailingZeros(m)).append(',');
        }
        return sb.toString();
    }

    private void applyBindings() {
        VkVoxelPipelineBindings pipeline = this.activePipeline;
        for (var binding : this.bindings) {
            if (binding.buffer() != null) {
                binding.buffer().assertNotFreed();
            }
            long handle = binding.buffer() != null ? binding.buffer().id : binding.rawHandle();
            long size = binding.size() > 0 ? binding.size() : VK_WHOLE_SIZE;
            if (binding.uniform()) {
                pipeline.setUniformBuffer(binding.index(), handle, binding.offset(), size);
            } else {
                pipeline.setStorageBuffer(binding.index(), handle, binding.offset(), size);
            }
        }
        for (var binding : this.textureBindings) {
            if (binding.texture() != null) {
                binding.texture().assertNotFreed();
                long view = binding.view() != 0L ? binding.view() : binding.texture().view();
                pipeline.setSampledImage(binding.unit(), view,
                        binding.texture().image().image.getSampler());
            }
        }
        for (var binding : this.storageImageBindings) {
            if (binding.texture() != null) {
                binding.texture().assertNotFreed();
                long view = binding.level() >= 0
                        ? binding.texture().levelView(binding.level())
                        : binding.texture().view();
                pipeline.setStorageImageView(binding.unit(), view);
            }
        }
        for (var binding : this.rawImageBindings) {
            if (binding.image() != null) {
                pipeline.setSampledImage(binding.unit(), binding.image().getImageView(), binding.sampler());
            }
        }
        if (this.pushConstantsBytes > 0) {
            pipeline.setPushConstants(this.pushConstants, this.pushConstantsBytes);
        }
    }

    @Override
    public void free() {
        this.activePipeline = null;
        super.free();
    }
}
