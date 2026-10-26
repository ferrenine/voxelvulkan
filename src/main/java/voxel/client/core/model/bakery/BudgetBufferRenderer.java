package voxel.client.core.model.bakery;

import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
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

    private static final VkBuffer indexBuffer;
    static {
        final int maxQuads = 4096;
        indexBuffer = new VkBuffer(maxQuads * 6L * 2L);
        long ptr = UploadStream.INSTANCE.upload(indexBuffer, 0, indexBuffer.size());
        for (int i = 0; i < maxQuads; i++) {
            int base = i * 4;
            MemoryUtil.memPutShort(ptr, (short) (base + 0)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (base + 1)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (base + 2)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (base + 2)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (base + 3)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (base + 0)); ptr += 2;
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
                .indexBuffer(indexBuffer.id, Shader.INDEX_TYPE_UINT16);
        tex.setSampler(prevSampler);
    }

    public static void render(Matrix4f matrix) {
        bakeryShader.pushMat4(0, matrix);
        bakeryShader.drawIndexed(quadCount * 2 * 3, 1, 0, 0, 0);
    }
}
