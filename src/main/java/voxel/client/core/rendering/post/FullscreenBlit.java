package voxel.client.core.rendering.post;

import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.core.rendering.util.SharedIndexBuffer;

import java.util.function.Function;

/**
 * Vulkan port note: the fullscreen triangle pair comes from the shared cube
 * index buffer (first 6 indices); no vertex array is needed since the vertex
 * shader generates positions from {@code gl_VertexIndex}.
 */
public class FullscreenBlit {
    private final AutoBindingShader shader;

    public FullscreenBlit(String fragId) {
        this(fragId, (a)->a);
    }

    public FullscreenBlit(String vertId, String fragId) {
        this(vertId, fragId, (a)->a);
    }

    public <T extends AutoBindingShader> FullscreenBlit(String fragId, Function<Shader.Builder<T>, Shader.Builder<T>> builder) {
        this("voxel:post/fullscreen.vert", fragId, builder);
    }

    public <T extends AutoBindingShader> FullscreenBlit(String vertId, String fragId, Function<Shader.Builder<T>, Shader.Builder<T>> builder) {
        this.shader = builder.apply((Shader.Builder<T>) Shader.makeAuto()
                .add(ShaderType.VERTEX, vertId)
                .add(ShaderType.FRAGMENT, fragId))
                .compile();
    }

    /** The underlying shader; bind textures/buffers on it before {@link #blit()}. */
    public AutoBindingShader shader() {
        return this.shader;
    }

    public void bind() {
        this.shader.bind();
    }

    public void blit() {
        boolean prevCull = voxel.client.core.vk.VkState.cullFace;
        voxel.client.core.vk.VkState.setCullFace(false);
        this.shader.indexBuffer(SharedIndexBuffer.INSTANCE_BYTE.id(), Shader.INDEX_TYPE_UINT16);
        this.shader.drawIndexed(6, 1, 0, 0, 0);
        voxel.client.core.vk.VkState.setCullFace(prevCull);
    }

    public void delete() {
        this.shader.free();
    }
}
