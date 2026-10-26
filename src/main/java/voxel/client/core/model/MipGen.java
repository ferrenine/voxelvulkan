package voxel.client.core.model;

import voxel.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static voxel.client.core.model.ModelFactory.LAYERS;
import static voxel.client.core.model.ModelFactory.MODEL_TEXTURE_SIZE;

//CPU mip-chain generation for the model atlas. Each model tile is
// MODEL_TEXTURE_SIZE² texels × 6 faces laid out in a 3×2 face grid; the
// mip chain packs each level back-to-back (each level is ¼ the previous).
public class MipGen {
    static {
        if (MODEL_TEXTURE_SIZE>16) throw new IllegalStateException("seed indices are stored in shorts — requires size ≤ 16");
    }

    private static final int N = MODEL_TEXTURE_SIZE;
    private static final int W = N * 3;             // face-grid row stride in texels
    private static final int FACE_PIXELS = N * N;
    private static final short[] SEED = new short[FACE_PIXELS];   // nearest opaque texel index per texel, -1 = unfilled

    //Fill transparent texels of every flagged face with the colour of their
    // nearest opaque texel so downsampling doesn't bleed in black/alpha.
    // Two-pass chamfer propagation of the seed index — exact Manhattan
    // nearest-seed for a 4-connected grid, no flood queue.
    private static void solidify(long baseAddr, byte msk) {
        for (int idx = 0; idx < 6; idx++) {
            if (((msk >> idx) & 1) == 0) continue;
            long cAddr = baseAddr + ((idx >> 1) * N + (idx & 1) * N * W) * 4L;

            //Face-local index i maps to a strided buffer offset: the face is
            // N wide inside a W=3N row, so offset = (i/N)*W + i%N.
            for (int i = 0; i < FACE_PIXELS; i++) {
                int colour = MemoryUtil.memGetInt(cAddr + ((i / N) * W + (i & (N - 1))) * 4L);
                SEED[i] = (colour & 0xFF000000) != 0 ? (short) i : -1;
            }

            //Forward pass: prefer left/top seeds; ties go to the lower seed
            // index so results match wavefront order.
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    int i = y * N + x;
                    int best = SEED[i];
                    int bestD = dist(i, best, x, y);
                    if (x > 0) {
                        int s = SEED[i - 1];
                        int d = dist(i, s, x, y);
                        if (d < bestD || (d == bestD && s >= 0 && s < best)) { bestD = d; best = s; }
                    }
                    if (y > 0) {
                        int s = SEED[i - N];
                        int d = dist(i, s, x, y);
                        if (d < bestD || (d == bestD && s >= 0 && s < best)) { bestD = d; best = s; }
                    }
                    SEED[i] = (short) best;
                }
            }
            //Backward pass: right/bottom seeds, same tie-break.
            for (int y = N - 1; y >= 0; y--) {
                for (int x = N - 1; x >= 0; x--) {
                    int i = y * N + x;
                    int best = SEED[i];
                    int bestD = dist(i, best, x, y);
                    if (x < N - 1) {
                        int s = SEED[i + 1];
                        int d = dist(i, s, x, y);
                        if (d < bestD || (d == bestD && s >= 0 && s < best)) { bestD = d; best = s; }
                    }
                    if (y < N - 1) {
                        int s = SEED[i + N];
                        int d = dist(i, s, x, y);
                        if (d < bestD || (d == bestD && s >= 0 && s < best)) { bestD = d; best = s; }
                    }
                    SEED[i] = (short) best;
                }
            }

            for (int i = 0; i < FACE_PIXELS; i++) {
                int off = (i / N) * W + (i & (N - 1));
                if ((MemoryUtil.memGetInt(cAddr + off * 4L) & 0xFF000000) == 0 && SEED[i] >= 0) {
                    int s = SEED[i];
                    int c = MemoryUtil.memGetInt(cAddr + ((s / N) * W + (s & (N - 1))) * 4L) & 0x00FFFFFF;
                    MemoryUtil.memPutInt(cAddr + off * 4L, c);
                }
            }
        }
    }

    //Manhattan distance between texel i=(x,y) and seed index s (−1 → infinite).
    private static int dist(int i, int s, int x, int y) {
        if (s < 0) return Integer.MAX_VALUE;
        return Math.abs((s & (N - 1)) - x) + Math.abs((s / N) - y);
    }

    public static void putTextures(boolean darkened, ColourDepthTextureData[] textures, MemoryBuffer into) {
        //TODO: need to use a write mask to see what pixels must be used to contribute to mipping
        // as in, using the depth/stencil info, check if pixel was written to, if so, use that pixel when blending, else dont
        final long addr = into.address;
        byte solidMsk = 0;
        for (int i = 0; i < 6; i++) {
            int x = (i >> 1) * N;
            int y = (i & 1) * N;
            boolean anyTransparent = false;
            int j = 0;
            for (int t : textures[i].colour()) {
                int o = ((y + (j / N)) * W + ((j & (N - 1)) + x)) * 4;
                j++;
                MemoryUtil.memPutInt(addr + o, t);
                anyTransparent |= (t & 0xFF000000) == 0;
            }
            solidMsk |= (anyTransparent ? 1 : 0) << i;
        }

        // Always solid-fill transparent texels. The shader's cutout discard
        // uses mip 0 alpha, so the RGB of a hole only contributes to the mip
        // colour; leaving it black/white is what produced the dark speckle on
        // distant leaves. Keeping mip 0 alpha at zero still discards the hole.
        solidify(addr, solidMsk);

        //Downsample level by level; each level reads the previous.
        long sAddr = addr;
        for (int i = 0; i < LAYERS - 1; i++) {
            long dAddr = sAddr + ((long) (N * N * 3 * 2 * 4) >> (i << 1));
            int width = W >> (i + 1);
            int sWidth = W >> i;
            int height = (N * 2) >> (i + 1);
            long rowStep = sWidth * 4L;
            for (int py = 0; py < height; py++) {
                long bp = sAddr + py * 2L * rowStep;
                long dp = dAddr + py * (long) width * 4L;
                for (int px = 0; px < width; px++) {
                    int C00 = MemoryUtil.memGetInt(bp);
                    int C01 = MemoryUtil.memGetInt(bp + rowStep);
                    int C10 = MemoryUtil.memGetInt(bp + 4);
                    int C11 = MemoryUtil.memGetInt(bp + rowStep + 4);
                    MemoryUtil.memPutInt(dp, TextureUtils.mipColours(darkened, C00, C01, C10, C11));
                    bp += 8;
                    dp += 4;
                }
            }
            sAddr = dAddr;
        }
    }
}
