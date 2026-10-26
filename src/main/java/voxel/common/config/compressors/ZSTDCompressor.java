package voxel.common.config.compressors;

import voxel.common.config.ConfigBuildCtx;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.ThreadLocalMemoryBuffer;
import voxel.common.world.SectionSerializationUtil;

import static voxel.common.util.GlobalCleaner.CLEANER;
import static org.lwjgl.util.zstd.Zstd.*;

public class ZSTDCompressor implements StorageCompressor {
    //Codec contexts are expensive — one per thread, freed via the global
    // cleaner when the owning record is collected.
    private record Ctx(long handle) implements AutoCloseable {
        @Override public void close() { ZSTD_freeCCtx(this.handle); }
    }
    private record DCtx(long handle) implements AutoCloseable {
        @Override public void close() { ZSTD_freeDCtx(this.handle); }
    }

    private static final ThreadLocal<Ctx> ENC_CTX = ThreadLocal.withInitial(() -> {
        var ctx = new Ctx(ZSTD_createCCtx());
        CLEANER.register(ctx, ctx::close);
        return ctx;
    });
    private static final ThreadLocal<DCtx> DEC_CTX = ThreadLocal.withInitial(() -> {
        long h = ZSTD_createDCtx();
        nZSTD_DCtx_setParameter(h, ZSTD_d_experimentalParam3, 1);//ZSTD_d_forceIgnoreChecksum
        var ctx = new DCtx(h);
        CLEANER.register(ctx, ctx::close);
        return ctx;
    });

    private static final ThreadLocalMemoryBuffer SCRATCH = new ThreadLocalMemoryBuffer(SectionSerializationUtil.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private final int level;

    public ZSTDCompressor(int level) {
        this.level = level;
    }

    @Override
    public MemoryBuffer compress(MemoryBuffer saveData) {
        var out = new MemoryBuffer((int) ZSTD_COMPRESSBOUND(saveData.size));
        long written = nZSTD_compressCCtx(ENC_CTX.get().handle, out.address, out.size, saveData.address, saveData.size, this.level);
        if (ZSTD_isError(written)) {
            throw new IllegalStateException("ZSTD_compress failed: " + ZSTD_getErrorName(written));
        }
        return out.subSize(written);
    }

    @Override
    public MemoryBuffer decompress(MemoryBuffer saveData) {
        var out = SCRATCH.get().createUntrackedUnfreeableReference();
        long written = nZSTD_decompressDCtx(DEC_CTX.get().handle, out.address, out.size, saveData.address, saveData.size);
        if (ZSTD_isError(written)) {
            throw new IllegalStateException("ZSTD_decompress failed: " + ZSTD_getErrorName(written));
        }
        return out.subSize(written);
    }

    @Override
    public void close() {
    }

    public static class Config extends CompressorConfig {
        public int compressionLevel;

        @Override
        public StorageCompressor build(ConfigBuildCtx ctx) {
            return new ZSTDCompressor(this.compressionLevel);
        }

        public static String getConfigTypeName() {
            return "ZSTD";
        }
    }
}
