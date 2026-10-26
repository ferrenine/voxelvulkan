package voxel.client.core.rendering.util;

import voxel.client.core.vk.VkBuffer;
import voxel.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;


//Base index buffer for terrain quads: uint32 indices covering 65,536 quads.
//The byte variants below are still uint16 (they only need a few hundred indices).
public class SharedIndexBuffer {
    /** Quads covered by {@link #INSTANCE}: 65,536 quads = 262,144 uint32 vertices. */
    public static final int MAX_QUADS = 1 << 16;
    public static final SharedIndexBuffer INSTANCE = new SharedIndexBuffer();
    public static final SharedIndexBuffer INSTANCE_BYTE = new SharedIndexBuffer(true);
    public static final SharedIndexBuffer INSTANCE_BB_BYTE = new SharedIndexBuffer(true, true);

    private final VkBuffer indexBuffer;

    public SharedIndexBuffer() {
        // uint32 indices: the section metadata can describe up to 65,535 quads
        // (65,536 quads need 262,144 vertices). The old uint16 buffer only had
        // 16,380 quads of valid indices, so any bucket above that read zeros.
        this.indexBuffer = new VkBuffer((long)MAX_QUADS * 6L * 4L);
        var quadIndexBuff = generateQuadIndicesInt(MAX_QUADS);

        long ptr = UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size());
        quadIndexBuff.cpyTo(ptr);

        quadIndexBuff.free();
        UploadStream.INSTANCE.commit();
    }

    //Vulkan has no uint8 index type, so the "byte" variants are promoted to
    //uint16: the index values and element counts are unchanged, only the stride
    //doubles. Use Shader.INDEX_TYPE_UINT16 with them.
    private SharedIndexBuffer(boolean type2) {
        this.indexBuffer = new VkBuffer(((1<<8)*6 + 6*2*3) * 2L);
        var quadIndexBuff = generateQuadIndicesShort(63);
        var cubeBuff = generateCubeIndexBufferShort();

        long ptr = UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size());
        quadIndexBuff.cpyTo(ptr);
        cubeBuff.cpyTo(((1<<8)*6)*2L + ptr);

        quadIndexBuff.free();
        cubeBuff.free();
        UploadStream.INSTANCE.commit();
    }

    private SharedIndexBuffer(boolean type2, boolean type3) {
        this.indexBuffer = new VkBuffer(6L *2*3*(256/8) * 2L);
        var cubeBuff = generateByteCubesIndexBufferShort(256/8);

        cubeBuff.cpyTo(UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size()));
        UploadStream.INSTANCE.commit();
        cubeBuff.free();
    }

    private static MemoryBuffer generateCubeIndexBufferShort() {
        var buffer = new MemoryBuffer(6*2*3*2L);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, 6*2*3*2L);

        //Bottom face
        MemoryUtil.memPutShort(ptr, (short) 0); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 1); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 2); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 3); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 2); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 1); ptr += 2;

        //top face
        MemoryUtil.memPutShort(ptr, (short) 6); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 5); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 4); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 5); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 6); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 7); ptr += 2;

        //north face
        MemoryUtil.memPutShort(ptr, (short) 0); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 4); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 1); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 5); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 1); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 4); ptr += 2;

        //south face
        MemoryUtil.memPutShort(ptr, (short) 3); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 6); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 2); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 6); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 3); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 7); ptr += 2;

        //west face
        MemoryUtil.memPutShort(ptr, (short) 2); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 4); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 0); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 4); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 2); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 6); ptr += 2;

        //east face
        MemoryUtil.memPutShort(ptr, (short) 1); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 5); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 3); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 7); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 3); ptr += 2;
        MemoryUtil.memPutShort(ptr, (short) 5); ptr += 2;

        return buffer;
    }

    private static MemoryBuffer generateByteCubesIndexBufferShort(int cnt) {
        var buffer = new MemoryBuffer((long) cnt *6*2*3*2L);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, buffer.size);

        for (int i = 0; i < cnt; i++) {
            int j = i*8;

            //Bottom face
            MemoryUtil.memPutShort(ptr, (short) (0+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (1+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (2+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (3+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (2+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (1+j)); ptr += 2;

            //top face
            MemoryUtil.memPutShort(ptr, (short) (6+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (5+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (4+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (5+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (6+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (7+j)); ptr += 2;

            //north face
            MemoryUtil.memPutShort(ptr, (short) (0+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (4+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (1+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (5+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (1+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (4+j)); ptr += 2;

            //south face
            MemoryUtil.memPutShort(ptr, (short) (3+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (6+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (2+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (6+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (3+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (7+j)); ptr += 2;

            //west face
            MemoryUtil.memPutShort(ptr, (short) (2+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (4+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (0+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (4+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (2+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (6+j)); ptr += 2;

            //east face
            MemoryUtil.memPutShort(ptr, (short) (1+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (5+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (3+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (7+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (3+j)); ptr += 2;
            MemoryUtil.memPutShort(ptr, (short) (5+j)); ptr += 2;
        }

        return buffer;
    }

    private static MemoryBuffer generateCubeIndexBuffer() {
        var buffer = new MemoryBuffer(6*2*3);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, 6*2*3 );

        //Bottom face
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 1);

        //top face
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 7);

        //north face
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 4);

        //south face
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 7);

        //west face
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 6);

        //east face
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 7);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 5);

        return buffer;
    }

    private static MemoryBuffer generateByteCubesIndexBuffer(int cnt) {
        var buffer = new MemoryBuffer((long) cnt *6*2*3);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, buffer.size);

        for (int i = 0; i < cnt; i++) {
            int j = i*8;

            //Bottom face
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));

            //top face
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));

            //north face
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));

            //south face
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));

            //west face
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));

            //east face
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
        }

        return buffer;
    }

    public static MemoryBuffer generateQuadIndicesByte(int quadCount) {
        if ((quadCount*4) >= 1<<8) {
            throw new IllegalArgumentException("Quad count to large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutByte(ptr + (0), (byte) (i + 1));
            MemoryUtil.memPutByte(ptr + (1), (byte) (i + 2));
            MemoryUtil.memPutByte(ptr + (2), (byte) (i + 0));
            MemoryUtil.memPutByte(ptr + (3), (byte) (i + 1));
            MemoryUtil.memPutByte(ptr + (4), (byte) (i + 3));
            MemoryUtil.memPutByte(ptr + (5), (byte) (i + 2));

            ptr += 6;
        }

        return buffer;
    }
    public static MemoryBuffer generateQuadIndicesShort(int quadCount) {
        if ((quadCount*4) >= 1<<16) {
            throw new IllegalArgumentException("Quad count to large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutShort(ptr + (0*2), (short) (i + 1));
            MemoryUtil.memPutShort(ptr + (1*2), (short) (i + 2));
            MemoryUtil.memPutShort(ptr + (2*2), (short) (i + 0));
            MemoryUtil.memPutShort(ptr + (3*2), (short) (i + 1));
            MemoryUtil.memPutShort(ptr + (4*2), (short) (i + 3));
            MemoryUtil.memPutShort(ptr + (5*2), (short) (i + 2));

            ptr += 6 * 2;
        }

        return buffer;
    }

    public static MemoryBuffer generateQuadIndicesInt(int quadCount) {
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 4);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutInt(ptr + (0*4), (i + 1));
            MemoryUtil.memPutInt(ptr + (1*4), (i + 2));
            MemoryUtil.memPutInt(ptr + (2*4), (i + 0));
            MemoryUtil.memPutInt(ptr + (3*4), (i + 1));
            MemoryUtil.memPutInt(ptr + (4*4), (i + 3));
            MemoryUtil.memPutInt(ptr + (5*4), (i + 2));
            ptr += 6 * 4;
        }
        return buffer;
    }

    public long id() {
        return this.indexBuffer.id;
    }
}
