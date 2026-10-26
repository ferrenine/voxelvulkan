package voxel.client.core.vk.shader;

import voxel.client.core.vk.Capabilities;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkDebug;
import voxel.client.vk.VkVoxel;
import voxel.client.vk.VkVoxelComputePipeline;
import voxel.client.vk.VkVoxelGraphicsPipeline;
import voxel.common.util.ThreadUtils;
import voxel.common.util.TrackedObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan replacement for the original GL program object.
 *
 * <p>GL shader stages are compiled to SPIR-V on first use and turned into a
 * {@link VkVoxelComputePipeline} (compute stage) or a
 * {@link VkVoxelGraphicsPipeline} (vertex + fragment stages).</p>
 */
public class Shader extends TrackedObject {

    public static final int INDEX_TYPE_UINT16 = VK_INDEX_TYPE_UINT16;
    public static final int INDEX_TYPE_UINT32 = VK_INDEX_TYPE_UINT32;

    /** Per-stage GLSL sources with defines/replacements already applied. */
    protected final Map<ShaderType, String> sources;
    protected String debugName = "voxel-shader";

    private VkVoxelComputePipeline computePipeline;
    private VkVoxelGraphicsPipeline graphicsPipeline;

    // Graphics draw state
    private long indexBufferHandle;
    private int indexType = VK_INDEX_TYPE_UINT16;

    protected Shader(Map<ShaderType, String> sources) {
        this.sources = sources;
    }

    public long id() {
        if (this.graphicsPipeline != null) {
            return 0L; // graphics pipelines are cached per state variant
        }
        return this.computePipeline != null ? this.computePipeline.handle() : 0L;
    }

    public void bind() {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        if (this.hasGraphicsStages()) {
            VkVoxelGraphicsPipeline pipeline = this.graphicsPipeline;
            if (pipeline == null) {
                pipeline = this.createGraphicsPipeline(p -> {
                });
            }
            pipeline.prepare(cb);
            return;
        }
        if (!this.hasComputeStage()) {
            return;
        }
        VkVoxelComputePipeline pipeline = this.computePipeline;
        if (pipeline == null) {
            pipeline = this.createComputePipeline(p -> {
            });
        }
        pipeline.bind(cb);
    }

    protected boolean hasComputeStage() {
        return this.sources.containsKey(ShaderType.COMPUTE);
    }

    protected boolean hasGraphicsStages() {
        return this.sources.containsKey(ShaderType.VERTEX) && this.sources.containsKey(ShaderType.FRAGMENT);
    }

    /**
     * Creates the compute pipeline once, letting the subclass declare the
     * descriptor bindings it needs.
     */
    protected VkVoxelComputePipeline createComputePipeline(Consumer<VkVoxelComputePipeline> declarations) {
        if (this.computePipeline != null) {
            return this.computePipeline;
        }
        String source = this.sources.get(ShaderType.COMPUTE);
        if (source == null) {
            throw new IllegalStateException("Shader '" + this.debugName + "' has no compute stage");
        }
        VkVoxelComputePipeline pipeline = new VkVoxelComputePipeline(this.debugName, source);
        declarations.accept(pipeline);
        this.computePipeline = pipeline;
        return pipeline;
    }

    protected VkVoxelComputePipeline computePipeline() {
        return this.computePipeline;
    }

    protected void destroyComputePipeline() {
        if (this.computePipeline != null) {
            this.computePipeline.free();
            this.computePipeline = null;
        }
    }

    // ------------------------------------------------------------------
    // Graphics pipelines
    // ------------------------------------------------------------------

    protected VkVoxelGraphicsPipeline createGraphicsPipeline(Consumer<VkVoxelGraphicsPipeline> declarations) {
        if (this.graphicsPipeline != null) {
            return this.graphicsPipeline;
        }
        String vertex = this.sources.get(ShaderType.VERTEX);
        String fragment = this.sources.get(ShaderType.FRAGMENT);
        if (vertex == null || fragment == null) {
            throw new IllegalStateException("Shader '" + this.debugName + "' has no vertex/fragment stages");
        }
        VkVoxelGraphicsPipeline pipeline = new VkVoxelGraphicsPipeline(this.debugName, vertex, fragment);
        declarations.accept(pipeline);
        this.graphicsPipeline = pipeline;
        return pipeline;
    }

    protected VkVoxelGraphicsPipeline graphicsPipeline() {
        return this.graphicsPipeline;
    }

    protected void destroyGraphicsPipeline() {
        if (this.graphicsPipeline != null) {
            this.graphicsPipeline.free();
            this.graphicsPipeline = null;
        }
    }

    /** Sets the index buffer used by the indexed draw helpers. */
    public void indexBuffer(VkBuffer buffer, int indexType) {
        this.indexBufferHandle = buffer.id;
        this.indexType = indexType;
    }

    public void indexBuffer(long bufferHandle, int indexType) {
        this.indexBufferHandle = bufferHandle;
        this.indexType = indexType;
    }

    // ------------------------------------------------------------------
    // Draw helpers (recorded into the frame's main command buffer)
    // ------------------------------------------------------------------

    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        this.bind();
        VkVoxelGraphicsPipeline pipeline = this.graphicsPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("draw() on shader '" + this.debugName + "' without a graphics pipeline");
        }
        pipeline.bindAndDraw(cb, vertexCount, instanceCount, firstVertex, firstInstance);
    }

    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        this.bind();
        VkVoxelGraphicsPipeline pipeline = this.graphicsPipeline;
        pipeline.bindAndDrawIndexed(cb, this.indexBufferHandle, this.indexType,
                indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    /** Indirect indexed draw; the command is read from {@code indirectBuffer} (single draw). */
    public void drawIndexedIndirect(VkBuffer indirectBuffer, long indirectOffset) {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        this.bind();
        VkVoxelGraphicsPipeline pipeline = this.graphicsPipeline;
        pipeline.bindAndDrawIndexedIndirect(cb, this.indexBufferHandle, this.indexType,
                indirectBuffer.id, indirectOffset);
    }

    /** Multi-draw indirect with the draw count read from {@code countBuffer}. */
    public void drawIndexedIndirectCount(VkBuffer indirectBuffer, long indirectOffset,
                                         VkBuffer countBuffer, long countOffset,
                                         int maxDrawCount, int stride) {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        this.bind();
        VkVoxelGraphicsPipeline pipeline = this.graphicsPipeline;
        pipeline.bindAndDrawIndexedIndirectCount(cb, this.indexBufferHandle, this.indexType,
                indirectBuffer.id, indirectOffset, countBuffer.id, countOffset, maxDrawCount, stride);
    }

    // ------------------------------------------------------------------
    // Dispatch helpers (recorded into the frame's main command buffer)
    // ------------------------------------------------------------------

    public void dispatch(int groupCountX, int groupCountY, int groupCountZ) {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        VkVoxel.ensureOutsideRenderPass();
        this.bind();
        if (this.computePipeline == null) {
            throw new IllegalStateException("dispatch() on shader '" + this.debugName + "' without a compute pipeline");
        }
        vkCmdDispatch(cb, groupCountX, groupCountY, groupCountZ);
    }

    public void dispatchIndirect(long indirectBufferHandle, long indirectOffset) {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        VkVoxel.ensureOutsideRenderPass();
        this.bind();
        if (this.computePipeline == null) {
            throw new IllegalStateException("dispatchIndirect() on shader '" + this.debugName + "' without a compute pipeline");
        }
        vkCmdDispatchIndirect(cb, indirectBufferHandle, indirectOffset);
    }

    public void dispatchIndirect(VkBuffer indirectBuffer, long indirectOffset) {
        this.dispatchIndirect(indirectBuffer.id, indirectOffset);
    }

    /**
     * Compute-write visibility barrier: makes SSBO/indirect writes from this
     * dispatch visible to later compute dispatches and indirect draws in the
     * same command buffer. Equivalent of {@code glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)}.
     */
    public static void memoryBarrier() {
        var cb = VkVoxel.commandBufferOrNull();
        if (cb == null) {
            return;
        }
        VkVoxel.memoryBarrier(cb,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT);
    }

    // ------------------------------------------------------------------

    @Override
    public void free() {
        this.free0();
        this.destroyComputePipeline();
        this.destroyGraphicsPipeline();
    }

    public Shader name(String name) {
        this.debugName = name;
        return VkDebug.name(name, this);
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static Builder<Shader> make(IShaderProcessor... processor) {
        return makeInternal((builder, sources) -> new Shader(sources), processor);
    }

    public static Builder<AutoBindingShader> makeAuto(IShaderProcessor... processor) {
        return makeInternal(AutoBindingShader::new, processor);
    }

    static <T extends Shader> Builder<T> makeInternal(Builder.IShaderObjectConstructor<T> constructor, IShaderProcessor[] processors) {
        IShaderProcessor applicator = (type, source) -> source;
        for (IShaderProcessor processor : processors) {
            IShaderProcessor finalApplicator = applicator;
            applicator = (type, source) -> finalApplicator.process(type, processor.process(type, source));
        }
        return new Builder<>(constructor, applicator);
    }

    public static class Builder<T extends Shader> {
        protected interface IShaderObjectConstructor<J extends Shader> {
            J make(Builder<J> builder, Map<ShaderType, String> sources);
        }

        final Map<String, String> defines = new HashMap<>();
        private final Map<ShaderType, String> sources = new HashMap<>();
        private final IShaderProcessor processor;
        private final IShaderObjectConstructor<T> constructor;

        private Builder(IShaderObjectConstructor<T> constructor, IShaderProcessor processor) {
            this.constructor = constructor;
            this.processor = processor;
        }

        public Builder<T> clone() {
            var clone = new Builder<>(this.constructor, this.processor);
            clone.defines.putAll(this.defines);
            clone.sources.putAll(this.sources);
            return clone;
        }

        public Builder<T> define(String name) {
            this.defines.put(name, "");
            return this;
        }

        //Useful for inline setting (such as debug)
        public Builder<T> defineIf(String name, boolean condition) {
            if (condition) {
                this.defines.put(name, "");
            }
            return this;
        }

        public Builder<T> defineIf(String name, boolean condition, int value) {
            if (condition) {
                this.defines.put(name, Integer.toString(value));
            }
            return this;
        }

        public Builder<T> define(String name, int value) {
            this.defines.put(name, Integer.toString(value));
            return this;
        }

        public Builder<T> define(String name, float value) {
            this.defines.put(name, Float.toString(value) + "f");
            return this;
        }

        public Builder<T> define(String name, String value) {
            this.defines.put(name, value);
            return this;
        }

        //AUDIT-DEAD: the old replace() API was removed — it wrote into
        // `defines` (a bug: the replacements map it claimed to fill never got
        // populated) and had zero call sites.

        public Builder<T> add(ShaderType type, String id) {
            this.addSource(type, ShaderLoader.parse(id));
            return this;
        }

        public Builder<T> addSource(ShaderType type, String source) {
            this.sources.put(type, this.processor.process(type, source));
            return this;
        }

        /**
         * Applies defines and replacements, then hands the per-stage sources to
         * the shader object. Compilation to SPIR-V happens lazily on first bind.
         */
        public T compile() {
            this.defineIf("IS_INTEL", Capabilities.INSTANCE.isIntel);
            this.defineIf("IS_WINDOWS", ThreadUtils.isWindows);

            //GLSL builtins that only exist under Vulkan semantics get mapped here
            //(the shaders were written against GL 4.6).
            this.defines.putIfAbsent("gl_VertexID", "gl_VertexIndex");
            this.defines.putIfAbsent("gl_InstanceID", "(gl_InstanceIndex - gl_BaseInstance)");

            Map<ShaderType, String> processed = new LinkedHashMap<>();
            String defs = this.defines.entrySet().stream()
                    .map(a -> "#define " + a.getKey() + " " + a.getValue() + "\n")
                    .collect(Collectors.joining());
            for (var entry : this.sources.entrySet()) {
                String src = entry.getValue();
                int firstNewline = src.indexOf('\n');
                if (firstNewline < 0) {
                    processed.put(entry.getKey(), defs + src);
                    continue;
                }
                //Inject defines after the #version line (upgrading <450 to 450 for Vulkan SPIR-V)
                String header = src.substring(0, firstNewline + 1);
                if (header.startsWith("#version 330") || header.startsWith("#version 430") || header.startsWith("#version 440")) {
                    header = "#version 450\n";
                }
                src = header
                        + defs
                        + src.substring(firstNewline + 1);
                src = src.replace("gl_DepthRange.diff", "1.0f")
                        .replace("gl_DepthRange.near", "0.0f")
                        .replace("gl_DepthRange.far", "1.0f");
                processed.put(entry.getKey(), src);
            }
            return this.constructor.make(this, processed);
        }
    }
}
