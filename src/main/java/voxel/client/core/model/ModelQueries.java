package voxel.client.core.model;

/**
 * Bit-level accessors for the packed {@code metadata} long baked per model by
 * ModelFactory and consumed by the mesher (RenderDataFactory) and shaders.
 *
 * <h3>Layout</h3>
 * <pre>
 * bits  0..47 : six per-face bytes, face index f reads (metadata &gt;&gt;&gt; (8*f)) &amp; 0xFF
 *               byte = 0xFF means the face does not exist; otherwise:
 *                 bit0 occludes            - face fully hides the neighbour face behind it
 *                 bit1 (unused)
 *                 bit2 canBeOccluded       - neighbour can hide THIS face
 *                 bit3 usesSelfLighting    - face keeps the block's own light byte
 *                 bit4 occludesFluid       - face also hides fluid faces
 *               (faceOccludesFluid tests bits 0|4: a fully-occluding face
 *                implies fluid occlusion; bit4 alone = fluid-only occlusion)
 * bits 48..55 : block-level flags:
 *                 bit0 biomeColoured  bit1 translucent   bit2 doubleSided
 *                 bit3 containsFluid  bit4 isFluid       bit5 cullsSame
 *                 bit6 fullyOpaque    bit7 needsPerBlockQuads
 * bit  56     : isLeaves (cutout leaf geometry)
 * bit  57     : isLava   (lava-family fluid)
 * </pre>
 */
public abstract class ModelQueries {
    public static boolean faceExists(long metadata, int face) {
        return ((metadata>>(8*face))&0xFF)!=0xFF;
    }

    public static boolean faceCanBeOccluded(long metadata, int face) {
        return ((metadata>>(8*face))&0b100)==0b100;
    }

    public static boolean faceOccludes(long metadata, int face) {
        return faceExists(metadata, face) && ((metadata>>(8*face))&0b1)==0b1;
    }

    public static boolean faceOccludesFluid(long metadata, int face) {
        return faceExists(metadata, face) && ((metadata>>(8*face))&0b10001)!=0;
    }

    public static boolean faceUsesSelfLighting(long metadata, int face) {
        return ((metadata>>(8*face))&0b1000) != 0;
    }

    public static boolean isDoubleSided(long metadata) {
        return ((metadata>>(8*6))&4) != 0;
    }

    public static boolean isTranslucent(long metadata) {
        return ((metadata>>(8*6))&2) != 0;
    }

    public static boolean containsFluid(long metadata) {
        return ((metadata>>(8*6))&8) != 0;
    }

    public static boolean isFluid(long metadata) {
        return ((metadata>>(8*6))&16) != 0;
    }

    public static boolean isBiomeColoured(long metadata) {
        return ((metadata>>(8*6))&1) != 0;
    }

    //NOTE: this might need to be moved to per face
    public static boolean cullsSame(long metadata) {
        return ((metadata>>(8*6))&32) != 0;
    }

    public static boolean isFullyOpaque(long metadata) {
        return ((metadata>>(8*6))&64) != 0;
    }

    /** Cutout leaves flag (metadata bit 56), set by ModelFactory. */
    public static boolean isLeaves(long metadata) {
        return ((metadata>>56)&1) != 0;
    }

    /** Lava-family fluid flag (metadata bit 57), set by ModelFactory. */
    public static boolean isLava(long metadata) {
        return ((metadata>>57)&1) != 0;
    }

    //Per-block geometry (vanilla model offset or slanted faces), needs one quad per block
    public static boolean needsPerBlockQuads(long metadata) {
        return ((metadata>>(8*6))&128) != 0;
    }
}
