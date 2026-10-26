package voxel.client.core.model.bakery;


import voxel.common.util.MemoryBuffer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import org.lwjgl.system.MemoryUtil;

import static voxel.client.core.model.bakery.BudgetBufferRenderer.VERTEX_FORMAT_SIZE;

import com.mojang.blaze3d.vertex.VertexConsumer;

public final class ReuseVertexConsumer implements VertexConsumer {
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;

    public boolean anyShaded;
    public boolean anyDarkendTex;
    /** Any captured quad used an animated sprite strip (seagrass, kelp, fire, ...). */
    public boolean anyAnimated;

    public ReuseVertexConsumer() {
        this.reset();
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    @Override
    public ReuseVertexConsumer addVertex(float x, float y, float z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta);
        MemoryUtil.memPutFloat(this.ptr, x);
        MemoryUtil.memPutFloat(this.ptr + 4, y);
        MemoryUtil.memPutFloat(this.ptr + 8, z);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public ReuseVertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int i) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    // MC 1.21.1: setLineWidth() removed from VertexConsumer interface
    public VertexConsumer setLineWidth(float f) {
        return null;
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.shade();
        this.anyDarkendTex |= quad.sprite().contents().mipmapStrategy == MipmapStrategy.DARK_CUTOUT;
        this.anyAnimated |= quad.sprite().contents().isAnimated();
        this.ensureCanPut();

        for (int i = 0; i < 4; i++) {
            var pos = quad.position(i);
            long packedUv = quad.packedUV(i);
            float u = UVPair.unpackU(packedUv);
            float v = UVPair.unpackV(packedUv);

            this.addVertex(pos.x(), pos.y(), pos.z());
            this.setUv(u, v);
            this.meta(metadata);
        }
        return this;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr-this.buffer.address;
        //1.5x the size
        var newBuffer = new MemoryBuffer((((int)(this.buffer.size*2)+VERTEX_FORMAT_SIZE-1)/VERTEX_FORMAT_SIZE)*VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.anyAnimated = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;//the thing is first time this gets incremented by FORMAT_STRIDE
        return this;
    }

    public void free() {
        this.ptr = 0;
        this.count = 0;
        this.buffer.free();
        this.buffer = null;
    }

    /**
     * Scales the UVs of every captured quad about that quad's UV centroid.
     * The lava flow-top capture uses it to widen vanilla's half-size flowing
     * sprite window (centred on the sprite) to the whole sprite.
     */
    public void scaleQuadUvs(float scale) {
        for (int q = 0; q + 4 <= this.count; q += 4) {
            long base = this.buffer.address + (long) q * VERTEX_FORMAT_SIZE;
            float cu = 0.0f;
            float cv = 0.0f;
            for (int i = 0; i < 4; i++) {
                long p = base + (long) i * VERTEX_FORMAT_SIZE;
                cu += MemoryUtil.memGetFloat(p + 16);
                cv += MemoryUtil.memGetFloat(p + 20);
            }
            cu *= 0.25f;
            cv *= 0.25f;
            for (int i = 0; i < 4; i++) {
                long p = base + (long) i * VERTEX_FORMAT_SIZE;
                MemoryUtil.memPutFloat(p + 16, cu + (MemoryUtil.memGetFloat(p + 16) - cu) * scale);
                MemoryUtil.memPutFloat(p + 20, cv + (MemoryUtil.memGetFloat(p + 20) - cv) * scale);
            }
        }
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public int quadCount() {
        if (this.count%4 != 0) throw new IllegalStateException();
        return this.count/4;
    }

    public long getAddress() {
        return this.buffer.address;
    }
}
