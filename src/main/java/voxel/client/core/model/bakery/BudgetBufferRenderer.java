package voxel.client.core.model.bakery;

import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.core.rendering.util.SharedIndexBuffer;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.vk.VkVoxel;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

public class BudgetBufferRenderer {
    public static final int VERTEX_FORMAT_SIZE = 24;
    private static final int STRIDE = 24;

    private static final AutoBindingShader bakeryShader = Shader.makeAuto()
            .add(ShaderType.VERTEX, "voxel:bakery/position_tex.vsh")
            .add(ShaderType.FRAGMENT, "voxel:bakery/position_tex.fsh")
            .compile();

    public static void init() {
    }

    private static boolean quadOverflowLogged;

    // The bakery has its own index order (0,1,2 / 2,3,0) and its own vertex
    // layout; do not share the terrain index buffer, whose provoking vertex is
    // deliberately 1 for quads3.vert's flat attributes.
    private static final VkBuffer indexBuffer;
    static {
        final int maxQuads = SharedIndexBuffer.MAX_QUADS;
        indexBuffer = new VkBuffer((long) maxQuads * 6L * 4L);
        long ptr = UploadStream.INSTANCE.upload(indexBuffer, 0, indexBuffer.size());
        for (int i = 0; i < maxQuads; i++) {
            int base = i * 4;
            MemoryUtil.memPutInt(ptr, base + 0); ptr += 4;
            MemoryUtil.memPutInt(ptr, base + 1); ptr += 4;
            MemoryUtil.memPutInt(ptr, base + 2); ptr += 4;
            MemoryUtil.memPutInt(ptr, base + 2); ptr += 4;
            MemoryUtil.memPutInt(ptr, base + 3); ptr += 4;
            MemoryUtil.memPutInt(ptr, base + 0); ptr += 4;
        }
        UploadStream.INSTANCE.commit();
    }

    private static final long RING_SIZE = 1024L * 1024L;
    private static VkBuffer immediateBuffer;
    private static long ringOffset = 0;
    private static int quadCount;

    public static void setup(long dataPtr, int quads, VulkanImage tex) {
        if (quads == 0) {
            throw new IllegalStateException();
        }
        if (quads > SharedIndexBuffer.MAX_QUADS) {
            if (!quadOverflowLogged) {
                quadOverflowLogged = true;
                voxel.common.Logger.error("Model bake produced " + quads
                        + " quads, more than the shared index buffer supports ("
                        + SharedIndexBuffer.MAX_QUADS + "); truncating the model");
            }
            quads = SharedIndexBuffer.MAX_QUADS;
        }

        quadCount = quads;

        long size = quads * 4L * STRIDE;
        long alignedSize = (size + 255L) & ~255L;
        if (immediateBuffer == null) {
            immediateBuffer = new VkBuffer(Math.max(RING_SIZE, alignedSize * 4L));
            ringOffset = 0;
        }
        if (ringOffset + alignedSize > immediateBuffer.size()) {
            ringOffset = 0;
        }
        long writeOffset = ringOffset;
        ringOffset += alignedSize;

        long ptr = UploadStream.INSTANCE.upload(immediateBuffer, writeOffset, size);
        MemoryUtil.memCopy(dataPtr, ptr, size);
        UploadStream.INSTANCE.commit();

        long prevSampler = tex.getSampler();
        tex.setSampler(net.vulkanmod.vulkan.texture.SamplerManager.getSampler(false, false, 0));
        bakeryShader
                .image(0, tex)
                .ssboRaw(1, immediateBuffer.id, writeOffset, size)
                .indexBuffer(indexBuffer.id, Shader.INDEX_TYPE_UINT32);
        tex.setSampler(prevSampler);
    }

    public static void render(Matrix4f matrix) {
        bakeryShader.pushMat4(0, matrix);
        bakeryShader.drawIndexed(quadCount * 2 * 3, 1, 0, 0, 0);
    }
}
