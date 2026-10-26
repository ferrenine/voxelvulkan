package voxel.client.vk;

import voxel.client.core.vk.VkFramebuffer;
import voxel.client.core.vk.VkState;
import voxel.client.core.vk.VkBuffer;
import voxel.common.Logger;
import voxel.common.util.TrackedObject;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/**
 * A graphics (vertex/fragment) pipeline owned by Voxel, built from GLSL
 * at runtime through the same shaderc path as VulkanMod's own shaders.
 *
 * <p>Vulkan bakes rasterisation/depth/blend state into the pipeline object, so
 * one pipeline is created per (state, render target format) combination and
 * cached. The state comes from {@link VkState} at draw time; the render target
 * formats come from the currently bound framebuffer (LING's own or VulkanMod's
 * swapchain framebuffer). Rendering uses dynamic rendering, matching
 * VulkanMod.</p>
 *
 * <p>Descriptor bindings (set 0) work exactly like
 * {@link VkVoxelComputePipeline}: bindings are declared up-front and one
 * descriptor set per frame in flight is written on demand.</p>
 */
public class VkVoxelGraphicsPipeline extends TrackedObject implements VkVoxelPipelineBindings {

    public static final int STORAGE_BUFFER = VkVoxelComputePipeline.STORAGE_BUFFER;
    public static final int UNIFORM_BUFFER = VkVoxelComputePipeline.UNIFORM_BUFFER;
    public static final int STORAGE_IMAGE = VkVoxelComputePipeline.STORAGE_IMAGE;
    public static final int SAMPLED_IMAGE = VkVoxelComputePipeline.SAMPLED_IMAGE;

    private record BindingValue(long handle, long sampler, long offset, long size, int layout) {
        static BindingValue buffer(long buffer, long offset, long size) {
            return new BindingValue(buffer, 0L, offset, size, 0);
        }

        static BindingValue image(long view, long sampler, int layout) {
            return new BindingValue(view, sampler, 0L, VK_WHOLE_SIZE, layout);
        }
    }

    private final String name;
    private final String vertexSource;
    private final String fragmentSource;

    private final Map<Integer, Integer> bindings = new LinkedHashMap<>();
    private final Map<Integer, BindingValue> values = new LinkedHashMap<>();
    private final Map<Long, Long> variants = new HashMap<>();

    private static final int SETS_PER_FRAME = 256;

    private long vertexModule = VK_NULL_HANDLE;
    private long fragmentModule = VK_NULL_HANDLE;
    private long descriptorSetLayout = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long descriptorPool = VK_NULL_HANDLE;
    private long[] descriptorSets;
    private long[] setVersion;
    private long[] slotLastEpoch;
    private long[] frameLastEpoch;
    private int[] frameCursor;
    private boolean built;

    private long version = 1L;

    private ByteBuffer pushConstants;
    private int pushConstantsBytes;

    public VkVoxelGraphicsPipeline(String name, String vertexSource, String fragmentSource) {
        this.name = name;
        this.vertexSource = vertexSource;
        this.fragmentSource = fragmentSource;
    }

    public String name() {
        return this.name;
    }

    // ------------------------------------------------------------------
    // Binding declaration / values (same contract as the compute pipeline)
    // ------------------------------------------------------------------

    public VkVoxelGraphicsPipeline storageBuffer(int binding) {
        return this.declare(binding, STORAGE_BUFFER);
    }

    public VkVoxelGraphicsPipeline uniformBuffer(int binding) {
        return this.declare(binding, UNIFORM_BUFFER);
    }

    public VkVoxelGraphicsPipeline storageImage(int binding) {
        return this.declare(binding, STORAGE_IMAGE);
    }

    public VkVoxelGraphicsPipeline sampledImage(int binding) {
        return this.declare(binding, SAMPLED_IMAGE);
    }

    private VkVoxelGraphicsPipeline declare(int binding, int type) {
        if (this.built) {
            throw new IllegalStateException("Cannot declare bindings after pipeline '" + this.name + "' was built");
        }
        Integer previous = this.bindings.put(binding, type);
        if (previous != null) {
            throw new IllegalStateException("Binding " + binding + " declared twice on '" + this.name + "'");
        }
        return this;
    }

    public void setStorageBuffer(int binding, VkBuffer buffer) {
        this.setStorageBuffer(binding, buffer.id, 0L, VK_WHOLE_SIZE);
    }

    public void setStorageBuffer(int binding, long bufferHandle, long offset, long size) {
        this.checkType(binding, STORAGE_BUFFER);
        this.putBinding(binding, BindingValue.buffer(bufferHandle, offset, size <= 0 ? VK_WHOLE_SIZE : size));
    }

    public void setUniformBuffer(int binding, VkBuffer buffer) {
        this.setUniformBuffer(binding, buffer.id, 0L, VK_WHOLE_SIZE);
    }

    public void setUniformBuffer(int binding, long bufferHandle, long offset, long size) {
        this.checkType(binding, UNIFORM_BUFFER);
        this.putBinding(binding, BindingValue.buffer(bufferHandle, offset, size <= 0 ? VK_WHOLE_SIZE : size));
    }

    public void setSampledImage(int binding, VkVoxelImage image) {
        this.setSampledImage(binding, image.view(), image.image.getSampler());
    }

    @Override
    public void setSampledImage(int binding, long viewHandle, long samplerHandle) {
        this.checkType(binding, SAMPLED_IMAGE);
        this.putBinding(binding, BindingValue.image(viewHandle, samplerHandle,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
    }

    public void setSampledImage(int binding, VulkanImage image) {
        this.checkType(binding, SAMPLED_IMAGE);
        this.putBinding(binding, BindingValue.image(image.getImageView(), image.getSampler(),
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
    }

    public void setStorageImage(int binding, VkVoxelImage image) {
        this.setStorageImageView(binding, image.view());
    }

    @Override
    public void setStorageImageView(int binding, long viewHandle) {
        this.checkType(binding, STORAGE_IMAGE);
        this.putBinding(binding, BindingValue.image(viewHandle, 0L, VK_IMAGE_LAYOUT_GENERAL));
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
    // Draw recording
    // ------------------------------------------------------------------

    public void bindAndDraw(VkCommandBuffer cb,
                            int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        this.prepare(cb);
        vkCmdDraw(cb, vertexCount, instanceCount, firstVertex, firstInstance);
    }

    public void bindIndexBuffer(VkCommandBuffer cb, long buffer, int indexType) {
        vkCmdBindIndexBuffer(cb, buffer, 0L, indexType);
    }

    public void bindAndDrawIndexed(VkCommandBuffer cb, long indexBuffer, int indexType,
                                   int indexCount, int instanceCount, int firstIndex,
                                   int vertexOffset, int firstInstance) {
        this.bindIndexBuffer(cb, indexBuffer, indexType);
        this.prepare(cb);
        vkCmdDrawIndexed(cb, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    public void bindAndDrawIndexedIndirect(VkCommandBuffer cb, long indexBuffer, int indexType,
                                           long indirectBuffer, long indirectOffset) {
        this.bindIndexBuffer(cb, indexBuffer, indexType);
        this.prepare(cb);
        vkCmdDrawIndexedIndirect(cb, indirectBuffer, indirectOffset, 1, VkDrawIndexedIndirectCommand.SIZEOF);
    }

    public void bindAndDrawIndexedIndirectCount(VkCommandBuffer cb, long indexBuffer, int indexType,
                                                long indirectBuffer, long indirectOffset,
                                                long countBuffer, long countOffset,
                                                int maxDrawCount, int stride) {
        this.bindIndexBuffer(cb, indexBuffer, indexType);
        this.prepare(cb);
        vkCmdDrawIndexedIndirectCount(cb, indirectBuffer, indirectOffset,
                countBuffer, countOffset, maxDrawCount, stride <= 0 ? VkDrawIndexedIndirectCommand.SIZEOF : stride);
    }

    /** Binds the pipeline variant matching {@link VkState} and the current target. */
    public void prepare(VkCommandBuffer cb) {
        VkFramebuffer.ensureRenderPassActive();
        this.ensureBuilt();
        if (this.bindings.size() != this.values.size()) {
            for (Integer key : this.bindings.keySet()) {
                if (!this.values.containsKey(key)) {
                    throw new IllegalStateException("Binding " + key + " of graphics pipeline '"
                            + this.name + "' has no value assigned");
                }
            }
        }

        int[] colorFormats = new int[0];
        long depthFormat = VK_FORMAT_UNDEFINED;
        int depthAspect = 0;

        VkFramebuffer voxelTarget = VkFramebuffer.current();
        if (voxelTarget != null) {
            colorFormats = voxelTarget.colorFormats();
            voxel.client.vk.VkVoxelImage depth = voxelTarget.depthImage();
            if (depth != null) {
                depthFormat = depth.image.format;
                depthAspect = depth.image.aspect;
            }
        } else {
            Renderer renderer = Renderer.getInstance();
            Framebuffer fb = renderer != null ? renderer.getBoundFramebuffer() : null;
            if (fb != null) {
                VulkanImage color = fb.getColorAttachment();
                VulkanImage depth = fb.getDepthAttachment();
                if (color != null) {
                    colorFormats = new int[]{color.format};
                }
                if (depth != null) {
                    depthFormat = depth.format;
                    depthAspect = depth.aspect;
                }
            }
        }

        long key = VkState.hash();
        for (int cf : colorFormats) {
            key = key * 31 + cf;
        }
        key = key * 31 + depthFormat;
        key = key * 31 + (depthAspect & VK_IMAGE_ASPECT_STENCIL_BIT);

        Long existing = this.variants.get(key);
        long variant;
        if (existing != null) {
            variant = existing;
        } else {
            variant = this.createVariant(colorFormats, depthFormat, depthAspect);
            this.variants.put(key, variant);
        }

        long descriptorSet = this.resolveDescriptorSet();

        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, variant);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
        }
        if (this.pushConstants != null && this.pushConstantsBytes > 0) {
            ByteBuffer slice = this.pushConstants.duplicate().position(0).limit(Math.min(128, this.pushConstantsBytes));
            vkCmdPushConstants(cb, this.pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, slice);
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

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    private void ensureBuilt() {
        if (this.built) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.vertexModule = createShaderModule(stack, this.name + ".vert", this.vertexSource,
                    SPIRVUtils.ShaderKind.VERTEX_SHADER);
            this.fragmentModule = createShaderModule(stack, this.name + ".frag", this.fragmentSource,
                    SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
            this.createDescriptorSetLayout(stack);
            this.createPipelineLayout(stack);
            this.createDescriptorPoolAndSets(stack);
        }
        this.built = true;
    }

    private static long createShaderModule(MemoryStack stack, String name, String source, SPIRVUtils.ShaderKind kind) {
        SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, source, kind);
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv.bytecode());
            LongBuffer pModule = stack.mallocLong(1);
            int res = vkCreateShaderModule(VkVoxel.device(), info, null, pModule);
            if (res != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateShaderModule failed (" + res + ") for '" + name + "'");
            }
            return pModule.get(0);
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
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
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
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
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

    private long createVariant(int[] colorFormats, long depthFormat, int depthAspect) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(this.vertexModule)
                    .pName(stack.UTF8("main"));
            stages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(this.fragmentModule)
                    .pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            int cullMode = VkState.cullFace ? VkState.cullMode : VK_CULL_MODE_NONE;

            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VkState.polygonMode)
                    .cullMode(cullMode)
                    .frontFace(VkState.frontFaceCw ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                    .sampleShadingEnable(false);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(VkState.depthTest)
                    .depthWriteEnable(VkState.depthWrite)
                    .depthCompareOp(VkState.depthCompare)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(VkState.stencilTest)
                    .minDepthBounds(0.0f)
                    .maxDepthBounds(1.0f);
            if (VkState.stencilTest) {
                VkStencilOpState stencilOp = VkStencilOpState.calloc(stack)
                        .failOp(VkState.stencilFailOp)
                        .passOp(VkState.stencilPassOp)
                        .depthFailOp(VkState.stencilDepthFailOp)
                        .compareOp(VkState.stencilCompareOp)
                        .compareMask(VkState.stencilCompareMask)
                        .writeMask(VkState.stencilWriteMask)
                        .reference(VkState.stencilReference);
                depthStencil.front(stencilOp).back(stencilOp);
            }

            int attachmentCount = colorFormats.length;
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(
                    Math.max(1, attachmentCount), stack);
            for (int i = 0; i < Math.max(1, attachmentCount); i++) {
                boolean isIntFormat = i < attachmentCount && (colorFormats[i] == VK_FORMAT_R32_UINT
                        || colorFormats[i] == VK_FORMAT_R8_UINT);
                blendAttachment.get(i)
                        .blendEnable(VkState.blend && !isIntFormat)
                        .srcColorBlendFactor(VkState.srcColorBlend)
                        .dstColorBlendFactor(VkState.dstColorBlend)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VkState.srcAlphaBlend)
                        .dstAlphaBlendFactor(VkState.dstAlphaBlend)
                        .alphaBlendOp(VK_BLEND_OP_ADD)
                        .colorWriteMask(VkState.colorWriteMask);
            }

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .logicOp(VK_LOGIC_OP_COPY)
                    .attachmentCount(attachmentCount)
                    .pAttachments(blendAttachment);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .viewMask(0)
                    .colorAttachmentCount(attachmentCount)
                    .pColorAttachmentFormats(attachmentCount > 0 ? stack.ints(colorFormats) : null)
                    .depthAttachmentFormat((int) depthFormat)
                    .stencilAttachmentFormat((depthAspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0
                            ? (int) depthFormat : VK_FORMAT_UNDEFINED);

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pNext(renderingInfo.address())
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterization)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(this.pipelineLayout)
                    .renderPass(VK_NULL_HANDLE)
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);

            LongBuffer pPipeline = stack.mallocLong(1);
            int res = vkCreateGraphicsPipelines(VkVoxel.device(), VK_NULL_HANDLE, info, null, pPipeline);
            if (res != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateGraphicsPipelines failed (" + res + ") for '" + this.name
                        + "' (color=" + java.util.Arrays.toString(colorFormats) + ", depth=" + depthFormat + ")");
            }
            return pPipeline.get(0);
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
                    throw new IllegalStateException("Binding " + bindingIndex + " of graphics pipeline '"
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

    @Override
    public void free() {
        this.free0();
        VkDevice device = VkVoxel.device();
        try {
            for (long variant : this.variants.values()) {
                vkDestroyPipeline(device, variant, null);
            }
            this.variants.clear();
            if (this.pipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(device, this.pipelineLayout, null);
            }
            if (this.descriptorPool != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(device, this.descriptorPool, null);
            }
            if (this.descriptorSetLayout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(device, this.descriptorSetLayout, null);
            }
            if (this.vertexModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device, this.vertexModule, null);
            }
            if (this.fragmentModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device, this.fragmentModule, null);
            }
        } catch (Throwable t) {
            Logger.error("Failed to free graphics pipeline '" + this.name + "'", t);
        }
    }

    @Override
    public String toString() {
        return "VkVoxelGraphicsPipeline[" + this.name + "]";
    }
}
