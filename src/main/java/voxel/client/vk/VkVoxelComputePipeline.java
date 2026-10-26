package voxel.client.vk;

import voxel.common.Logger;
import voxel.common.util.TrackedObject;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A compute pipeline owned by Voxel, compiled from GLSL at runtime via
 * shaderc (the same path VulkanMod uses for its own shaders).
 *
 * <p>The pipeline owns a single descriptor set layout (set 0). Bindings must be
 * declared up-front with {@code storageBuffer()/storageImage()/sampledImage()};
 * values are assigned with the matching {@code set*} methods. One descriptor set
 * is allocated per frame in flight, so updating bindings while previous frames
 * are still executing is safe.</p>
 */
public class VkVoxelComputePipeline extends TrackedObject implements VkVoxelPipelineBindings {

    public static final int STORAGE_BUFFER = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    public static final int UNIFORM_BUFFER = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    public static final int STORAGE_IMAGE = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    public static final int SAMPLED_IMAGE = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

    private record BindingValue(long handle, long sampler, long offset, long size, int layout) {
        static BindingValue buffer(long buffer, long offset, long size) {
            return new BindingValue(buffer, 0L, offset, size, 0);
        }

        static BindingValue image(long view, long sampler, int layout) {
            return new BindingValue(view, sampler, 0L, VK_WHOLE_SIZE, layout);
        }
    }

    private final String name;
    private final String glslSource;

    private final Map<Integer, Integer> bindings = new LinkedHashMap<>();
    private final Map<Integer, BindingValue> values = new LinkedHashMap<>();

    private static final int SETS_PER_FRAME = 256;

    private long shaderModule = VK_NULL_HANDLE;
    private long descriptorSetLayout = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private long descriptorPool = VK_NULL_HANDLE;
    private long[] descriptorSets;
    private long[] setVersion;
    private long[] slotLastEpoch;
    private long[] frameLastEpoch;
    private int[] frameCursor;
    private boolean built;

    /** Incremented whenever any binding value changes. */
    private long version = 1L;

    private ByteBuffer pushConstants;
    private int pushConstantsBytes;

    public VkVoxelComputePipeline(String name, String glslSource) {
        this.name = name;
        this.glslSource = glslSource;
    }

    public String name() {
        return this.name;
    }

    // ------------------------------------------------------------------
    // Binding declaration
    // ------------------------------------------------------------------

    public VkVoxelComputePipeline storageBuffer(int binding) {
        return this.declare(binding, STORAGE_BUFFER);
    }

    public VkVoxelComputePipeline uniformBuffer(int binding) {
        return this.declare(binding, UNIFORM_BUFFER);
    }

    public VkVoxelComputePipeline storageImage(int binding) {
        return this.declare(binding, STORAGE_IMAGE);
    }

    public VkVoxelComputePipeline sampledImage(int binding) {
        return this.declare(binding, SAMPLED_IMAGE);
    }

    private VkVoxelComputePipeline declare(int binding, int type) {
        if (this.built) {
            throw new IllegalStateException("Cannot declare bindings after pipeline '" + this.name + "' was built");
        }
        Integer previous = this.bindings.put(binding, type);
        if (previous != null) {
            throw new IllegalStateException("Binding " + binding + " declared twice on '" + this.name + "'");
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Binding values
    // ------------------------------------------------------------------

    public void setStorageBuffer(int binding, VkVoxelBuffer buffer) {
        setStorageBuffer(binding, buffer.handle(), 0L, VK_WHOLE_SIZE);
    }

    public void setStorageBuffer(int binding, VkVoxelBuffer buffer, long offset, long size) {
        setStorageBuffer(binding, buffer.handle(), offset, size);
    }

    public void setStorageBuffer(int binding, long bufferHandle, long offset, long size) {
        checkType(binding, STORAGE_BUFFER);
        putBinding(binding, BindingValue.buffer(bufferHandle, offset, size <= 0 ? VK_WHOLE_SIZE : size));
    }

    public void setUniformBuffer(int binding, long bufferHandle, long offset, long size) {
        checkType(binding, UNIFORM_BUFFER);
        putBinding(binding, BindingValue.buffer(bufferHandle, offset, size <= 0 ? VK_WHOLE_SIZE : size));
    }

    public void setStorageImage(int binding, VkVoxelImage image) {
        setStorageImageView(binding, image.view());
    }

    @Override
    public void setStorageImageView(int binding, long viewHandle) {
        checkType(binding, STORAGE_IMAGE);
        putBinding(binding, BindingValue.image(viewHandle, 0L, VK_IMAGE_LAYOUT_GENERAL));
    }

    public void setSampledImage(int binding, VkVoxelImage image) {
        setSampledImage(binding, image.view(), image.image.getSampler());
    }

    @Override
    public void setSampledImage(int binding, long viewHandle, long samplerHandle) {
        checkType(binding, SAMPLED_IMAGE);
        putBinding(binding, BindingValue.image(viewHandle, samplerHandle,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
    }

    @Override
    public void setSampledImage(int binding, net.vulkanmod.vulkan.texture.VulkanImage image) {
        checkType(binding, SAMPLED_IMAGE);
        putBinding(binding, BindingValue.image(image.getImageView(), image.getSampler(),
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
    }

    private void putBinding(int binding, BindingValue value) {
        BindingValue prev = this.values.put(binding, value);
        if (!value.equals(prev)) {
            this.version++;
        }
    }

    public void clearBinding(int binding) {
        if (this.values.remove(binding) != null) {
            this.version++;
        }
    }

    @Override
    public void setPushConstants(ByteBuffer data, int bytes) {
        this.pushConstants = data;
        this.pushConstantsBytes = bytes;
    }

    private void checkType(int binding, int expected) {
        Integer declared = this.bindings.get(binding);
        if (declared == null) {
            throw new IllegalStateException("Binding " + binding + " was not declared on pipeline '" + this.name + "'");
        }
        if (declared != expected) {
            throw new IllegalStateException("Binding " + binding + " on '" + this.name + "' is type "
                    + declared + ", not " + expected);
        }
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    public void dispatch(VkCommandBuffer cb, int groupCountX, int groupCountY, int groupCountZ) {
        bind(cb);
        vkCmdDispatch(cb, groupCountX, groupCountY, groupCountZ);
    }

    public void dispatchIndirect(VkCommandBuffer cb, long indirectBuffer, long indirectOffset) {
        bind(cb);
        vkCmdDispatchIndirect(cb, indirectBuffer, indirectOffset);
    }

    public void bind(VkCommandBuffer cb) {
        this.ensureBuilt();
        long descriptorSet = this.resolveDescriptorSet();
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
        }
        if (this.pushConstants != null && this.pushConstantsBytes > 0) {
            ByteBuffer slice = this.pushConstants.duplicate().position(0).limit(Math.min(128, this.pushConstantsBytes));
            vkCmdPushConstants(cb, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, slice);
        }
    }

    private long resolveDescriptorSet() {
        int frame = VkVoxel.currentFrame();
        if (frame < 0 || frame >= this.frameLastEpoch.length) {
            throw new IllegalStateException("frame " + frame + " outside the descriptor pool (frames="
                    + this.frameLastEpoch.length + ") for pipeline '" + this.name + "'");
        }
        long epoch = VkVoxel.frameEpoch();
        int base = frame * SETS_PER_FRAME;

        if (this.frameLastEpoch[frame] != epoch) {
            this.frameLastEpoch[frame] = epoch;
            this.frameCursor[frame] = 0;
        }

        int cursor = this.frameCursor[frame];
        int currentIdx = base + cursor;

        if (this.setVersion[currentIdx] == this.version) {
            // Record the epoch even on a cache hit: the slot is live in this
            // frame, so a later binding change must not treat it as free.
            this.slotLastEpoch[currentIdx] = epoch;
            return this.descriptorSets[currentIdx];
        }

        if (this.slotLastEpoch[currentIdx] != epoch) {
            this.writeDescriptorSet(currentIdx);
            this.setVersion[currentIdx] = this.version;
            this.slotLastEpoch[currentIdx] = epoch;
            return this.descriptorSets[currentIdx];
        }

        // The cursor slot is already referenced by a command recorded in this
        // epoch. Find a slot with the same binding version or one that has not
        // been used yet; never overwrite a live descriptor set.
        for (int step = 1; step <= SETS_PER_FRAME; step++) {
            int candidateCursor = (cursor + step) % SETS_PER_FRAME;
            int candidate = base + candidateCursor;
            if (this.setVersion[candidate] == this.version) {
                this.frameCursor[frame] = candidateCursor;
                this.slotLastEpoch[candidate] = epoch;
                return this.descriptorSets[candidate];
            }
            if (this.slotLastEpoch[candidate] != epoch) {
                this.writeDescriptorSet(candidate);
                this.setVersion[candidate] = this.version;
                this.slotLastEpoch[candidate] = epoch;
                this.frameCursor[frame] = candidateCursor;
                return this.descriptorSets[candidate];
            }
        }
        throw new IllegalStateException("Descriptor ring exhausted for pipeline '" + this.name
                + "': more than " + SETS_PER_FRAME
                + " distinct binding states in one frame (frame=" + frame + ", epoch=" + epoch + ")");
    }

    public long handle() {
        this.ensureBuilt();
        return this.pipeline;
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    private void ensureBuilt() {
        if (this.built) {
            return;
        }
        if (this.bindings.isEmpty() && !this.values.isEmpty()) {
            throw new IllegalStateException("Pipeline '" + this.name + "' has values but no declared bindings");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.createShaderModule(stack);
            this.createDescriptorSetLayout(stack);
            this.createPipelineLayout(stack);
            this.createPipeline(stack);
            this.createDescriptorPoolAndSets(stack);
        }
        this.built = true;
    }

    private void createShaderModule(MemoryStack stack) {
        SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(this.name, this.glslSource, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv.bytecode());
            LongBuffer pModule = stack.mallocLong(1);
            int res = vkCreateShaderModule(VkVoxel.device(), info, null, pModule);
            if (res != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateShaderModule failed (" + res + ") for compute shader '" + this.name + "'");
            }
            this.shaderModule = pModule.get(0);
        } finally {
            spirv.free();
        }
    }

    private void createDescriptorSetLayout(MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(this.bindings.size(), stack);
        int i = 0;
        for (Map.Entry<Integer, Integer> entry : this.bindings.entrySet()) {
            layoutBindings.get(i++)
                    .binding(entry.getKey())
                    .descriptorType(entry.getValue())
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(layoutBindings);
        LongBuffer pLayout = stack.mallocLong(1);
        int res = vkCreateDescriptorSetLayout(VkVoxel.device(), info, null, pLayout);
        if (res != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDescriptorSetLayout failed (" + res + ") for '" + this.name + "'");
        }
        this.descriptorSetLayout = pLayout.get(0);
    }

    private void createPipelineLayout(MemoryStack stack) {
        VkPushConstantRange.Buffer pcRanges = VkPushConstantRange.calloc(1, stack);
        pcRanges.get(0)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                .offset(0)
                .size(128);
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(this.descriptorSetLayout))
                .pPushConstantRanges(pcRanges);
        LongBuffer pLayout = stack.mallocLong(1);
        int res = vkCreatePipelineLayout(VkVoxel.device(), info, null, pLayout);
        if (res != VK_SUCCESS) {
            throw new IllegalStateException("vkCreatePipelineLayout failed (" + res + ") for '" + this.name + "'");
        }
        this.pipelineLayout = pLayout.get(0);
    }

    private void createPipeline(MemoryStack stack) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(this.shaderModule)
                .pName(stack.UTF8("main"));

        VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
        info.get(0)
                .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(stage)
                .layout(this.pipelineLayout)
                .basePipelineHandle(VK_NULL_HANDLE)
                .basePipelineIndex(-1);

        LongBuffer pPipeline = stack.mallocLong(1);
        int res = vkCreateComputePipelines(VkVoxel.device(), VK_NULL_HANDLE, info, null, pPipeline);
        if (res != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateComputePipelines failed (" + res + ") for '" + this.name + "'");
        }
        this.pipeline = pPipeline.get(0);
    }

    private void createDescriptorPoolAndSets(MemoryStack stack) {
        int frames = Math.max(VkVoxel.MAX_FRAME_SLOTS, VkVoxel.framesNum());
        int totalSets = frames * SETS_PER_FRAME;

        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(this.bindings.size(), stack);
        int i = 0;
        for (int type : this.bindings.values()) {
            sizes.get(i++).type(type).descriptorCount(totalSets);
        }

        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(totalSets)
                .pPoolSizes(sizes);
        LongBuffer pPool = stack.mallocLong(1);
        int res = vkCreateDescriptorPool(VkVoxel.device(), poolInfo, null, pPool);
        if (res != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDescriptorPool failed (" + res + ") for '" + this.name + "'");
        }
        this.descriptorPool = pPool.get(0);

        LongBuffer layouts = stack.mallocLong(totalSets);
        for (int f = 0; f < totalSets; f++) {
            layouts.put(f, this.descriptorSetLayout);
        }
        VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(this.descriptorPool)
                .pSetLayouts(layouts);
        LongBuffer pSets = stack.mallocLong(totalSets);
        res = vkAllocateDescriptorSets(VkVoxel.device(), allocInfo, pSets);
        if (res != VK_SUCCESS) {
            throw new IllegalStateException("vkAllocateDescriptorSets failed (" + res + ") for '" + this.name + "'");
        }
        this.descriptorSets = new long[totalSets];
        this.setVersion = new long[totalSets];
        this.slotLastEpoch = new long[totalSets];
        this.frameLastEpoch = new long[frames];
        this.frameCursor = new int[frames];
        for (int f = 0; f < totalSets; f++) {
            this.descriptorSets[f] = pSets.get(f);
            this.setVersion[f] = 0L;
            this.slotLastEpoch[f] = -1L;
        }
        for (int f = 0; f < frames; f++) {
            this.frameLastEpoch[f] = -1L;
        }
    }

    private void writeDescriptorSet(int setIdx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(this.bindings.size(), stack);
            int i = 0;
            for (Map.Entry<Integer, Integer> entry : this.bindings.entrySet()) {
                int bindingIndex = entry.getKey();
                int type = entry.getValue();
                BindingValue value = this.values.get(bindingIndex);
                if (value == null) {
                    throw new IllegalStateException("Binding " + bindingIndex + " of compute pipeline '"
                            + this.name + "' has no value assigned");
                }

                VkWriteDescriptorSet write = writes.get(i++);
                write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(this.descriptorSets[setIdx])
                        .dstBinding(bindingIndex)
                        .descriptorCount(1)
                        .descriptorType(type);

                if (type == STORAGE_BUFFER || type == UNIFORM_BUFFER) {
                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                    bufferInfo.get(0)
                            .buffer(value.handle())
                            .offset(value.offset())
                            .range(value.size() <= 0 ? VK_WHOLE_SIZE : value.size());
                    write.pBufferInfo(bufferInfo);
                } else {
                    VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                    imageInfo.get(0)
                            .sampler(value.sampler())
                            .imageView(value.handle())
                            .imageLayout(value.layout());
                    write.pImageInfo(imageInfo);
                }
            }
            vkUpdateDescriptorSets(VkVoxel.device(), writes, null);
        }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    @Override
    public void free() {
        this.free0();
        org.lwjgl.vulkan.VkDevice device = VkVoxel.device();
        try {
            if (this.pipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(device, this.pipeline, null);
            }
            if (this.pipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(device, this.pipelineLayout, null);
            }
            if (this.descriptorPool != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(device, this.descriptorPool, null);
            }
            if (this.descriptorSetLayout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(device, this.descriptorSetLayout, null);
            }
            if (this.shaderModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device, this.shaderModule, null);
            }
        } catch (Throwable t) {
            Logger.error("Failed to free compute pipeline '" + this.name + "'", t);
        }
    }

    @Override
    public String toString() {
        return "VkVoxelComputePipeline[" + this.name + "]";
    }
}
