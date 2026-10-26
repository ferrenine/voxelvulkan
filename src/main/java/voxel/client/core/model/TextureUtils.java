package voxel.client.core.model;

//Texturing utils to manipulate data from the model bakery
public class TextureUtils {
    private static final float[] SRGB_TO_LINEAR = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255.0f;
            SRGB_TO_LINEAR[i] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
        }
    }

    private static float srgbToLinear(int channel) {
        return SRGB_TO_LINEAR[channel & 0xFF];
    }

    private static int linearToSrgb(float r, float g, float b, int a) {
        int ri = linearFloatToSrgbByte(r);
        int gi = linearFloatToSrgbByte(g);
        int bi = linearFloatToSrgbByte(b);
        return ((a & 0xFF) << 24) | (bi << 16) | (gi << 8) | ri;
    }

    private static int linearFloatToSrgbByte(float v) {
        if (v <= 0.0f) return 0;
        if (v >= 1.0f) return 255;
        float s = v <= 0.0031308f ? v * 12.92f : (float) (1.055 * Math.pow(v, 1.0 / 2.4) - 0.055);
        return Math.clamp(Math.round(s * 255.0f), 0, 255);
    }
    //Returns the number of non pixels not written to
    public static int getWrittenPixelCount(ColourDepthTextureData texture, int checkMode) {
        int count = 0;
        for (int i = 0; i < texture.colour().length; i++) {
            count += wasPixelWritten(texture, checkMode, i) ? 1 : 0;
        }
        return count;
    }

    public static boolean isSolid(ColourDepthTextureData texture) {
        for (int pixel : texture.colour()) {
            if (((pixel >> 24) & 0xFF) != 255) {
                return false;
            }
        }
        return true;
    }

    public static final int WRITE_CHECK_STENCIL = 1;
    public static final int WRITE_CHECK_DEPTH = 2;
    public static final int WRITE_CHECK_ALPHA = 3;

    private static boolean wasPixelWritten(ColourDepthTextureData data, int mode, int index) {
        if (mode == WRITE_CHECK_STENCIL) {
            return (data.depth()[index] & 0xFF) != 0;
        } else if (mode == WRITE_CHECK_DEPTH) {
            return (data.depth()[index] >>> 8) != ((1 << 24) - 1);
        } else if (mode == WRITE_CHECK_ALPHA) {
            //TODO:FIXME: for some reason it has an alpha of 1 even if its ment to be 0
            return ((data.colour()[index] >>> 24) & 0xff) > 1;
        }
        throw new IllegalArgumentException();
    }


    //0: nothing written
    //1: none tinted
    //2: some tinted
    //3: all tinted
    public static int computeFaceTint(ColourDepthTextureData texture, int checkMode) {
        boolean allTinted = true;
        boolean someTinted = false;
        boolean wasWriten = false;

        final var colourData = texture.colour();
        final var depthData = texture.depth();
        for (int i = 0; i < colourData.length; i++) {
            if (!wasPixelWritten(texture, checkMode, i)) {
                continue;
            }
            if ((colourData[i] & 0xFFFFFF) == 0 || (colourData[i] >>> 24) == 0) {//If the pixel is fully black (or translucent)
                continue;
            }
            boolean pixelTinited = (depthData[i] & (1 << 7)) != 0;
            wasWriten |= true;
            allTinted &= pixelTinited;
            someTinted |= pixelTinited;

        }
        if (!wasWriten) {
            return 0;
        }
        return someTinted ? (allTinted ? 3 : 2) : 1;
    }

    public static final int DEPTH_MODE_AVG = 1;
    public static final int DEPTH_MODE_MAX = 2;
    public static final int DEPTH_MODE_MIN = 3;


    //Computes depth info based on written pixel data
    public static float computeDepth(ColourDepthTextureData texture, int mode, int checkMode) {
        final var colourData = texture.colour();
        final var depthData = texture.depth();
        long a = 0;
        long b = 0;
        if (mode == DEPTH_MODE_MIN) {
            a = Long.MAX_VALUE;
        }
        if (mode == DEPTH_MODE_MAX) {
            a = Long.MIN_VALUE;
        }
        for (int i = 0; i < colourData.length; i++) {
            if (!wasPixelWritten(texture, checkMode, i)) {
                continue;
            }
            int depth = depthData[i] >>> 8;
            if (mode == DEPTH_MODE_AVG) {
                a++;
                b += depth;
            } else if (mode == DEPTH_MODE_MAX) {
                a = Math.max(a, depth);
            } else if (mode == DEPTH_MODE_MIN) {
                a = Math.min(a, depth);
            }
        }

        if (mode == DEPTH_MODE_AVG) {
            if (a == 0) {
                return -1;
            }
            return u2fdepth((int) (b / a));
        } else if (mode == DEPTH_MODE_MAX) {
            if (a == Long.MIN_VALUE) {
                return -1;
            }
            return u2fdepth((int) a);
        } else if (mode == DEPTH_MODE_MIN) {
            if (a == Long.MAX_VALUE) {
                return -1;
            }
            return u2fdepth((int) a);
        }
        throw new IllegalArgumentException();
    }

    /**
     * Least-squares plane through the written texels' depth:
     * {@code depth = dMean + b*(u - uMean) + c*(v - vMean)}, with u, v the texel centres in block units.
     * Returns {@code {uMean, vMean, dMean, b, c, maxAbsResidual, minDepth, maxDepth}}, or null when fewer
     * than 3 texels were written.
     */
    public static float[] fitDepthPlane(ColourDepthTextureData texture, int checkMode) {
        final int w = texture.width();
        final int h = texture.height();
        final int[] depthData = texture.depth();
        int n = 0;
        double su = 0, sv = 0, sd = 0;
        float minD = Float.MAX_VALUE;
        float maxD = -Float.MAX_VALUE;
        for (int i = 0; i < depthData.length; i++) {
            if (!wasPixelWritten(texture, checkMode, i)) {
                continue;
            }
            float d = u2fdepth(depthData[i] >>> 8);
            n++;
            su += ((i % w) + 0.5) / w;
            sv += ((i / w) + 0.5) / h;
            sd += d;
            minD = Math.min(minD, d);
            maxD = Math.max(maxD, d);
        }
        if (n < 3) {
            return null;
        }
        double mu = su / n, mv = sv / n, md = sd / n;
        double suu = 0, svv = 0, suv = 0, sud = 0, svd = 0;
        for (int i = 0; i < depthData.length; i++) {
            if (!wasPixelWritten(texture, checkMode, i)) {
                continue;
            }
            double du = ((i % w) + 0.5) / w - mu;
            double dv = ((i / w) + 0.5) / h - mv;
            double dd = u2fdepth(depthData[i] >>> 8) - md;
            suu += du * du;
            svv += dv * dv;
            suv += du * dv;
            sud += du * dd;
            svd += dv * dd;
        }
        //Small ridge term: for line-like texel sets the slope across the line goes to 0
        double ridge = n * 1e-6;
        suu += ridge;
        svv += ridge;
        double det = suu * svv - suv * suv;
        double b = (sud * svv - svd * suv) / det;
        double c = (svd * suu - sud * suv) / det;
        double maxResidual = 0;
        for (int i = 0; i < depthData.length; i++) {
            if (!wasPixelWritten(texture, checkMode, i)) {
                continue;
            }
            double du = ((i % w) + 0.5) / w - mu;
            double dv = ((i / w) + 0.5) / h - mv;
            double r = u2fdepth(depthData[i] >>> 8) - (md + b * du + c * dv);
            maxResidual = Math.max(maxResidual, Math.abs(r));
        }
        return new float[]{(float) mu, (float) mv, (float) md, (float) b, (float) c, (float) maxResidual, minD, maxD};
    }

    private static float u2fdepth(int depth) {
        float depthF = (float) ((double) depth / ((1 << 24) - 1));
        //AUDIT-FIXME: depth doubled on a guess ("unsigned bullshit" comment) —
        // unverified; only masked by the >1.00001f guard below.

        //Shouldent be needed due to the compute bake copy
        depthF *= 2;
        if (depthF > 1.00001f) {//Basicly only happens when a model goes out of bounds (thing)
            //System.err.println("Warning: Depth greater than 1");
            depthF = 1.0f;
        }
        return depthF;
    }


    //NOTE: data goes from bottom left to top right (x first then y)
    public static int[] computeBounds(ColourDepthTextureData data, int checkMode) {
        //Compute x bounds first
        int minX = 0;
        minXCheck:
        do {
            for (int y = 0; y < data.height(); y++) {
                int idx = minX + (y * data.width());
                if (wasPixelWritten(data, checkMode, idx)) {
                    break minXCheck;//pixel was written too so break from loop
                }
            }
            minX++;
        } while (minX != data.width());

        int maxX = data.width() - 1;
        maxXCheck:
        do {
            for (int y = data.height() - 1; y != -1; y--) {
                int idx = maxX + (y * data.width());
                if (wasPixelWritten(data, checkMode, idx)) {
                    break maxXCheck;//pixel was written too so break from loop
                }
            }
            maxX--;
        } while (maxX != -1);
        //maxX++;


        //Compute y bounds
        int minY = 0;
        minYCheck:
        do {
            for (int x = 0; x < data.width(); x++) {
                int idx = (minY * data.height()) + x;
                if (wasPixelWritten(data, checkMode, idx)) {
                    break minYCheck;//pixel was written too
                }
            }
            minY++;
        } while (minY != data.height());


        int maxY = data.height() - 1;
        maxYCheck:
        do {
            for (int x = data.width() - 1; x != -1; x--) {
                int idx = (maxY * data.height()) + x;
                if (wasPixelWritten(data, checkMode, idx)) {
                    break maxYCheck;//pixel was written too so break from loop
                }
            }
            maxY--;
        } while (maxY != -1);
        //maxY++;

        return new int[]{minX, maxX, minY, maxY};
    }

    /**
     * Extends every non-empty column of a captured face to the full 0..height
     * span by replicating its nearest written texel (colour and depth/stencil).
     *
     * <p>Vanilla's fluid capture only paints the fluid's physical height
     * (8/9 for a source block, less for flowing levels). The LOD displaces lava
     * side-face vertices between 0 and 1 so neighbouring flow terraces stay
     * watertight, so the unpainted strip in the tile has to be filled.</p>
     */
    public static ColourDepthTextureData extendToFullHeight(ColourDepthTextureData texture, int checkMode) {
        final int width = texture.width();
        final int height = texture.height();
        final int[] srcColour = texture.colour();
        final int[] srcDepth = texture.depth();
        int[] colour = null;
        int[] depth = null;
        for (int x = 0; x < width; x++) {
            int first = -1;
            int last = -1;
            for (int y = 0; y < height; y++) {
                if (wasPixelWritten(texture, checkMode, x + y * width)) {
                    if (first < 0) {
                        first = y;
                    }
                    last = y;
                }
            }
            if (first < 0 || (first == 0 && last == height - 1)) {
                continue;
            }
            if (colour == null) {
                colour = srcColour.clone();
                depth = srcDepth.clone();
            }
            int firstIdx = x + first * width;
            int lastIdx = x + last * width;
            for (int y = 0; y < first; y++) {
                int i = x + y * width;
                colour[i] = srcColour[firstIdx];
                depth[i] = srcDepth[firstIdx];
            }
            for (int y = last + 1; y < height; y++) {
                int i = x + y * width;
                colour[i] = srcColour[lastIdx];
                depth[i] = srcDepth[lastIdx];
            }
        }
        if (colour == null) {
            return texture;
        }
        return new ColourDepthTextureData(colour, depth, width, height);
    }


    public static int mipColours(boolean darkend, int C00, int C01, int C10, int C11) {
        darkend = !darkend;//Invert to make it easier
        float r = 0.0f;
        float g = 0.0f;
        float b = 0.0f;
        float a = 0.0f;
        if (darkend || (C00 >>> 24) != 0) {
            r += srgbToLinear((C00 >> 0) & 0xFF);
            g += srgbToLinear((C00 >> 8) & 0xFF);
            b += srgbToLinear((C00 >> 16) & 0xFF);
            a += darkend ? (C00 >>> 24) : srgbToLinear(C00 >>> 24);
        }
        if (darkend || (C01 >>> 24) != 0) {
            r += srgbToLinear((C01 >> 0) & 0xFF);
            g += srgbToLinear((C01 >> 8) & 0xFF);
            b += srgbToLinear((C01 >> 16) & 0xFF);
            a += darkend ? (C01 >>> 24) : srgbToLinear(C01 >>> 24);
        }
        if (darkend || (C10 >>> 24) != 0) {
            r += srgbToLinear((C10 >> 0) & 0xFF);
            g += srgbToLinear((C10 >> 8) & 0xFF);
            b += srgbToLinear((C10 >> 16) & 0xFF);
            a += darkend ? (C10 >>> 24) : srgbToLinear(C10 >>> 24);
        }
        if (darkend || (C11 >>> 24) != 0) {
            r += srgbToLinear((C11 >> 0) & 0xFF);
            g += srgbToLinear((C11 >> 8) & 0xFF);
            b += srgbToLinear((C11 >> 16) & 0xFF);
            a += darkend ? (C11 >>> 24) : srgbToLinear(C11 >>> 24);
        }

        int alphaValue = darkend ? ((int) a) / 4 : linearToSrgbChannel(a / 4);
        return linearToSrgb(r / 4, g / 4, b / 4, alphaValue);
    }

    /**
     * Convert linear alpha value to sRGB using gamma correction.
     * Replacement for ARGB.linearToSrgbChannel() which doesn't exist in MC 1.21.1
     */
    private static int linearToSrgbChannel(float linear) {
        return Math.clamp((int) (Math.pow(linear, 1.0 / 2.2) * 255), 0, 255);
    }
}