package voxel.common.config.compressors;

import voxel.common.config.ConfigBuildCtx;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.ThreadLocalMemoryBuffer;
import voxel.common.world.SectionSerializationUtil;
import net.jpountz.lz4.LZ4Factory;
import org.lwjgl.system.MemoryUtil;

//LZ4 codec with a 4-byte uncompressed-size header on the wire format.
public class LZ4Compressor implements StorageCompressor {
    private static final ThreadLocalMemoryBuffer SCRATCH = new ThreadLocalMemoryBuffer(SectionSerializationUtil.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);
    private static final LZ4Factory CODEC = LZ4Factory.nativeInstance();

    private final net.jpountz.lz4.LZ4Compressor enc = CODEC.fastCompressor();
    private final net.jpountz.lz4.LZ4FastDecompressor dec = CODEC.fastDecompressor();

    @Override
    public MemoryBuffer compress(MemoryBuffer saveData) {
        var out = new MemoryBuffer(this.enc.maxCompressedLength((int) saveData.size) + 4);
        MemoryUtil.memPutInt(out.address, (int) saveData.size);
        int written = this.enc.compress(saveData.asByteBuffer(), 0, (int) saveData.size, out.asByteBuffer(), 4, (int) out.size - 4);
        return out.subSize(written + 4);
    }

    @Override
    public MemoryBuffer decompress(MemoryBuffer saveData) {
        var out = SCRATCH.get().createUntrackedUnfreeableReference();
        int outLen = MemoryUtil.memGetInt(saveData.address);
        int consumed = this.dec.decompress(saveData.asByteBuffer(), 4, out.asByteBuffer(), 0, outLen);
        if (consumed != saveData.size - 4) {
            throw new IllegalStateException("LZ4 stream truncated: consumed " + consumed + " of " + (saveData.size - 4));
        }
        return out.subSize(outLen);
    }

    @Override
    public void close() {
    }

    public static class Config extends CompressorConfig {
        @Override
        public StorageCompressor build(ConfigBuildCtx ctx) {
            return new LZ4Compressor();
        }

        public static String getConfigTypeName() {
            return "LZ4";
        }
    }
}
