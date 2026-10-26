package voxel.client.core.vk;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GL-style render state facade for the Vulkan port.
 *
 * <p>Vulkan bakes depth/cull/blend state into the pipeline, so the ported
 * rendering code writes its state here (instead of calling the GL state
 * machine); {@link voxel.client.vk.VkVoxelGraphicsPipeline} turns the
 * combined state into pipeline variants at draw time.</p>
 */
public final class VkState {

    public static boolean depthTest = false;
    public static boolean depthWrite = true;
    public static int depthCompare = VK_COMPARE_OP_LESS_OR_EQUAL;

    public static boolean cullFace = false;
    public static int cullMode = VK_CULL_MODE_BACK_BIT;
    public static boolean frontFaceCw = false;

    public static boolean blend = false;
    public static int srcColorBlend = VK_BLEND_FACTOR_SRC_ALPHA;
    public static int dstColorBlend = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    public static int srcAlphaBlend = VK_BLEND_FACTOR_ONE;
    public static int dstAlphaBlend = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    public static int blendOp = VK_BLEND_OP_ADD;

    public static int colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    public static int polygonMode = VK_POLYGON_MODE_FILL;

    public static boolean stencilTest = false;
    public static int stencilFailOp = VK_STENCIL_OP_KEEP;
    public static int stencilDepthFailOp = VK_STENCIL_OP_KEEP;
    public static int stencilPassOp = VK_STENCIL_OP_KEEP;
    public static int stencilCompareOp = VK_COMPARE_OP_ALWAYS;
    public static int stencilCompareMask = 0xFF;
    public static int stencilWriteMask = 0xFF;
    public static int stencilReference = 0;

    private VkState() {
    }

    public static void setDepthTest(boolean enabled) {
        depthTest = enabled;
    }

    public static void setDepthWrite(boolean enabled) {
        depthWrite = enabled;
    }

    /** Accepts the GL compare enums (GL_LESS, GL_GREATER, ...). */
    public static void setDepthFunc(int glFunc) {
        depthCompare = mapCompareOp(glFunc);
    }

    public static void setCullFace(boolean enabled) {
        cullFace = enabled;
    }

    public static void setCullMode(boolean cullFront) {
        cullMode = cullFront ? VK_CULL_MODE_FRONT_BIT : VK_CULL_MODE_BACK_BIT;
    }

    /** {@code true} selects GL_CW (which matches Vulkan clockwise). */
    public static void setFrontFace(boolean clockwise) {
        frontFaceCw = clockwise;
    }

    public static void setBlend(boolean enabled) {
        blend = enabled;
    }

    /** Accepts the GL blend factor enums ({@code glBlendFuncSeparate} order). */
    public static void setBlendFuncSeparate(int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
        srcColorBlend = mapBlendFactor(srcColor);
        dstColorBlend = mapBlendFactor(dstColor);
        srcAlphaBlend = mapBlendFactor(srcAlpha);
        dstAlphaBlend = mapBlendFactor(dstAlpha);
    }

    public static void setColorMask(boolean r, boolean g, boolean b, boolean a) {
        colorWriteMask = (r ? VK_COLOR_COMPONENT_R_BIT : 0)
                | (g ? VK_COLOR_COMPONENT_G_BIT : 0)
                | (b ? VK_COLOR_COMPONENT_B_BIT : 0)
                | (a ? VK_COLOR_COMPONENT_A_BIT : 0);
    }

    public static void setPolygonMode(boolean line) {
        polygonMode = line ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL;
    }

    public static void setStencilTest(boolean enabled) {
        stencilTest = enabled;
    }

    public static void setStencilOp(int sfail, int dpfail, int dppass) {
        stencilFailOp = mapStencilOp(sfail);
        stencilDepthFailOp = mapStencilOp(dpfail);
        stencilPassOp = mapStencilOp(dppass);
    }

    public static void setStencilFunc(int func, int ref, int mask) {
        stencilCompareOp = mapCompareOp(func);
        stencilReference = ref;
        stencilCompareMask = mask;
    }

    public static void setStencilMask(int mask) {
        stencilWriteMask = mask;
    }

    public static int mapStencilOp(int glOp) {
        return switch (glOp) {
            case GL_KEEP -> VK_STENCIL_OP_KEEP;
            case GL_ZERO -> VK_STENCIL_OP_ZERO;
            case GL_REPLACE -> VK_STENCIL_OP_REPLACE;
            case GL_INCR -> VK_STENCIL_OP_INCREMENT_AND_CLAMP;
            case GL_DECR -> VK_STENCIL_OP_DECREMENT_AND_CLAMP;
            case GL_INVERT -> VK_STENCIL_OP_INVERT;
            case 0x8507 /* GL_INCR_WRAP */ -> VK_STENCIL_OP_INCREMENT_AND_WRAP;
            case 0x8508 /* GL_DECR_WRAP */ -> VK_STENCIL_OP_DECREMENT_AND_WRAP;
            default -> VK_STENCIL_OP_KEEP;
        };
    }

    // ------------------------------------------------------------------

    public static int mapCompareOp(int glFunc) {
        return switch (glFunc) {
            case GL_NEVER -> VK_COMPARE_OP_NEVER;
            case GL_LESS -> VK_COMPARE_OP_LESS;
            case GL_EQUAL -> VK_COMPARE_OP_EQUAL;
            case GL_LEQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case GL_GREATER -> VK_COMPARE_OP_GREATER;
            case GL_NOTEQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case GL_GEQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GL_ALWAYS -> VK_COMPARE_OP_ALWAYS;
            default -> throw new IllegalArgumentException("Unsupported GL depth func 0x" + Integer.toHexString(glFunc));
        };
    }

    public static int mapBlendFactor(int glFactor) {
        return switch (glFactor) {
            case GL_ZERO -> VK_BLEND_FACTOR_ZERO;
            case GL_ONE -> VK_BLEND_FACTOR_ONE;
            case GL_SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case GL_ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GL_DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case GL_ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case GL_SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case GL_ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GL_DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case GL_ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case GL_SRC_ALPHA_SATURATE -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default -> throw new IllegalArgumentException("Unsupported GL blend factor 0x" + Integer.toHexString(glFactor));
        };
    }

    /** Mixed hash of the whole state; used as (part of) the pipeline variant key. */
    public static long hash() {
        long h = 1125899906842597L;
        h = 31 * h + (depthTest ? 1 : 0);
        h = 31 * h + (depthWrite ? 1 : 0);
        h = 31 * h + depthCompare;
        h = 31 * h + (cullFace ? cullMode : 0);
        h = 31 * h + (frontFaceCw ? 1 : 0);
        h = 31 * h + (blend ? 1 : 0);
        if (blend) {
            h = 31 * h + srcColorBlend;
            h = 31 * h + dstColorBlend;
            h = 31 * h + srcAlphaBlend;
            h = 31 * h + dstAlphaBlend;
            h = 31 * h + blendOp;
        }
        h = 31 * h + colorWriteMask;
        h = 31 * h + polygonMode;
        h = 31 * h + (stencilTest ? 1 : 0);
        if (stencilTest) {
            h = 31 * h + stencilFailOp;
            h = 31 * h + stencilDepthFailOp;
            h = 31 * h + stencilPassOp;
            h = 31 * h + stencilCompareOp;
            h = 31 * h + stencilCompareMask;
            h = 31 * h + stencilWriteMask;
            h = 31 * h + stencilReference;
        }
        return h;
    }
}
