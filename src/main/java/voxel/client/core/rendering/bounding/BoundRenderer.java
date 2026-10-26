package voxel.client.core.rendering.bounding;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;
import voxel.client.config.VoxelConfig;
import voxel.client.core.AbstractRenderPipeline;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.util.SharedIndexBuffer;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkState;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderLoader;
import voxel.client.core.vk.shader.ShaderType;

import static org.lwjgl.opengl.GL11C.GL_GREATER;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;

/**
 * Renders the AABB mask of loaded vanilla sections into
 * {@link Viewport#depthBoundingBuffer}, so LOD terrain can be clipped where
 * vanilla chunks already cover the screen.
 *
 * <p>The storage policy is supplied by an {@link IBoundStore}; this class is
 * the shared renderer for all stores. It is the Vulkan port of Voxy's
 * {@code BoundRenderer}.</p>
 */
public class BoundRenderer {
    private final VkBuffer uniformBuffer = new VkBuffer(128);
    private final AutoBindingShader rasterShader;
    private final AbstractRenderPipeline pipeline;

    public BoundRenderer(AbstractRenderPipeline pipeline) {
        this.pipeline = pipeline;

        String vert = ShaderLoader.parse("voxel:chunkoutline/outline.vsh");
        //AUDIT-DEAD: taaFunction() always returns null — TAA never defined.
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            vert = vert + "\n\n\n" + taa;
        }
        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .add(ShaderType.FRAGMENT, "voxel:chunkoutline/outline.fsh")
                .compile()
                .ubo(0, this.uniformBuffer);
    }

    public void render(Viewport<?> viewport, IBoundStore store) {
        store.preRender(viewport);
        int count = store.getCount();

        if (count == 0) {
            viewport.depthBoundingBuffer.clear(0);
            // A ChunkBoundStore starts with an empty map and queues additions;
            // run postRender even on the first frame so those additions reach
            // the buffer for the next frame's draw.
            store.postRender(viewport);
            return;
        }

        viewport.depthBoundingBuffer.clear(0);

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr;
        ptr += 4 * 4 * 4;

        final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;

        {
            int sx = (int) viewport.cameraX;
            int sy = (int) viewport.cameraY;
            int sz = (int) viewport.cameraZ;
            new Vector3i(sx, sy, sz).getToAddress(ptr);
            ptr += 4 * 4;

            var negInnerSec = new Vector3f(
                    (float) (viewport.cameraX - sx),
                    (float) (viewport.cameraY - sy),
                    (float) (viewport.cameraZ - sz));

            negInnerSec.getToAddress(ptr);
            ptr += 4 * 3;
            viewport.MVP.translate(negInnerSec.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistance);
            ptr += 4;

            // Configurable overlap to prevent pop-in at the vanilla/LOD seam.
            MemoryUtil.memPutInt(ptr, VoxelConfig.CONFIG.lodBoundaryBuffer);
            ptr += 4;
        }
        UploadStream.INSTANCE.commit();

        {
            VkState.setFrontFace(true); // CW: reverse winding for AABB back faces
            VkState.setCullFace(true);
            VkState.setDepthTest(true);
            VkState.setDepthFunc(GL_GREATER);
        }

        this.rasterShader.ssbo(1, store.getBuffer());
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.indexBuffer(SharedIndexBuffer.INSTANCE_BB_BYTE.id(), Shader.INDEX_TYPE_UINT16);
        this.pipeline.bindUniforms();

        // Batch the draws into groups of size 32 (matches the outline shader's
        // gl_InstanceID/gl_BaseInstance indexing).
        if (count >= 32) {
            this.rasterShader.drawIndexed(6 * 2 * 3 * 32, count / 32, 0, 0, 0);
        }
        if (count % 32 != 0) {
            this.rasterShader.drawIndexed(6 * 2 * 3 * (count % 32), 1, 0, 0, (count / 32) * 32);
        }

        {
            VkState.setFrontFace(false); // CCW
            VkState.setDepthFunc(GL_LEQUAL);
            VkState.setCullFace(true);
            VkState.setDepthTest(true);
        }

        store.postRender(viewport);
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
    }
}
