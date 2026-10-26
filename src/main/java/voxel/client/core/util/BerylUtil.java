package voxel.client.core.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import voxel.common.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Beryl bridge (replaces the original Iris bridge).
 *
 * <p>Caches reflective handles to {@code net.beryl.render.RenderingPipeline} once
 * at class-load time so per-frame queries and lighting uniform uploads have zero
 * lookup overhead.</p>
 */
public class BerylUtil {

    public static final boolean BERYL_INSTALLED = FabricLoader.getInstance().isModLoaded("beryl");
    public static final boolean SHADER_SUPPORT = true;

    public static Object CAPTURED_VIEWPORT_PARAMETERS;

    private static final Method GET_STAGE;
    private static final Method IS_USING_PIPELINE;
    private static final Method SET_USE_PIPELINE;

    private static final Field F_LIGHT_DIR_WS;
    private static final Field F_LIGHT_COLOR_BUF;
    private static final Field F_AMBIENT_COLOR_BUF;
    private static final Field F_SKY_COLOR_BUF;
    private static final Field F_LIGHT_INTENSITY;
    private static final Field F_LIGHT_VISIBILITY;
    private static final Field F_AMBIENT_FACTOR;
    private static final Field F_NIGHT_MULTIPLIER;
    private static final Field F_MIN_AMBIENT;
    private static final Field F_FOG_FACTOR;
    private static final Field F_SHADOW_BIAS;
    private static final Field F_SHADOW_DISTORTION;
    private static final Field F_SHADOW_FRAMEBUFFER;
    private static final Field F_SHADOW_TEXEL_SIZE;
    private static final Field F_ORTHO_HALF_LENGTH;
    private static final Field F_LIGHT_PROJECTION;
    private static final Field F_LIGHT_VIEW;
    private static final Field F_LIGHT_VP;
    private static final Field F_LIGHT_SPACE_MAT_BUF;
    private static final Field F_LIGHT_SPACE_OFFSET_BUF;
    private static final Field F_TEMP_FRAMEBUFFER;
    private static final Object BERYL_CONFIG_OBJ;
    private static final Field F_SHADOW_RENDER_DISTANCE;
    private static boolean SHADOW_MAP_WARNED = false;
    private static int LAST_LOGGED_SHADOW_DISTANCE = -1;

    static {
        Method getStage = null;
        Method isUsing = null;
        Method setUse = null;
        Field lightDirWs = null;
        Field lightColorBuf = null;
        Field ambientColorBuf = null;
        Field skyColorBuf = null;
        Field lightIntensity = null;
        Field lightVisibility = null;
        Field ambientFactor = null;
        Field nightMultiplier = null;
        Field minAmbient = null;
        Field fogFactor = null;
        Field shadowBias = null;
        Field shadowDistortion = null;
        Field shadowFramebuffer = null;
        Field shadowTexelSize = null;
        Field orthoHalfLength = null;
        Field lightProjection = null;
        Field lightView = null;
        Field lightVp = null;
        Field lightSpaceMatBuf = null;
        Field lightSpaceOffsetBuf = null;
        Field tempFramebuffer = null;
        Object berylConfigObj = null;
        Field shadowRenderDistance = null;

        if (BERYL_INSTALLED) {
            try {
                Class<?> cls = Class.forName("net.beryl.render.RenderingPipeline");
                getStage = findMethod(cls, "getRenderingStage");
                isUsing = findMethod(cls, "isUsingShaderPipeline");
                setUse = findMethod(cls, "setUseShaderPipeline", boolean.class);
                lightDirWs = findField(cls, "lightDirWS");
                lightColorBuf = findField(cls, "LightColorBuffer");
                ambientColorBuf = findField(cls, "AmbientLightColorBuffer");
                skyColorBuf = findField(cls, "SkyColorBuffer");
                lightIntensity = findField(cls, "LightIntensity");
                lightVisibility = findField(cls, "LightVisibility");
                ambientFactor = findField(cls, "AmbientLightFactor");
                nightMultiplier = findField(cls, "NightMultiplier");
                minAmbient = findField(cls, "MinAmbientLight");
                fogFactor = findField(cls, "FogFactor");
                shadowBias = findField(cls, "shadowBias");
                shadowDistortion = findField(cls, "shadowDistortion");
                shadowFramebuffer = findField(cls, "shadowFramebuffer");
                shadowTexelSize = findField(cls, "ShadowTexelSize");
                orthoHalfLength = findField(cls, "orthoMatrixHalfLength");
                lightProjection = findField(cls, "lightProjection");
                lightView = findField(cls, "lightView");
                lightVp = findField(cls, "lightVP");
                lightSpaceMatBuf = findField(cls, "LightSpaceMatrixBuffer");
                lightSpaceOffsetBuf = findField(cls, "LightSpaceOffsetBuffer");
                tempFramebuffer = findField(cls, "tempFramebuffer");
                Class<?> modCls = Class.forName("net.beryl.BerylMod");
                Field cfgField = findField(modCls, "CONFIG");
                if (cfgField != null) {
                    berylConfigObj = cfgField.get(null);
                    if (berylConfigObj != null) {
                        shadowRenderDistance = findField(berylConfigObj.getClass(), "shadowRenderDistance");
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        GET_STAGE = getStage;
        IS_USING_PIPELINE = isUsing;
        SET_USE_PIPELINE = setUse;
        F_LIGHT_DIR_WS = lightDirWs;
        F_LIGHT_COLOR_BUF = lightColorBuf;
        F_AMBIENT_COLOR_BUF = ambientColorBuf;
        F_SKY_COLOR_BUF = skyColorBuf;
        F_LIGHT_INTENSITY = lightIntensity;
        F_LIGHT_VISIBILITY = lightVisibility;
        F_AMBIENT_FACTOR = ambientFactor;
        F_NIGHT_MULTIPLIER = nightMultiplier;
        F_MIN_AMBIENT = minAmbient;
        F_FOG_FACTOR = fogFactor;
        F_SHADOW_BIAS = shadowBias;
        F_SHADOW_DISTORTION = shadowDistortion;
        F_SHADOW_FRAMEBUFFER = shadowFramebuffer;
        F_SHADOW_TEXEL_SIZE = shadowTexelSize;
        F_ORTHO_HALF_LENGTH = orthoHalfLength;
        F_LIGHT_PROJECTION = lightProjection;
        F_LIGHT_VIEW = lightView;
        F_LIGHT_VP = lightVp;
        F_LIGHT_SPACE_MAT_BUF = lightSpaceMatBuf;
        F_LIGHT_SPACE_OFFSET_BUF = lightSpaceOffsetBuf;
        F_TEMP_FRAMEBUFFER = tempFramebuffer;
        BERYL_CONFIG_OBJ = berylConfigObj;
        F_SHADOW_RENDER_DISTANCE = shadowRenderDistance;
    }

    private BerylUtil() {
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean berylShadowActive() {
        if (!BERYL_INSTALLED || GET_STAGE == null) {
            return false;
        }
        try {
            Object stage = GET_STAGE.invoke(null);
            return stage != null && "SHADOW_MAP".equals(stage.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean berylShaderEnabled() {
        if (!BERYL_INSTALLED || IS_USING_PIPELINE == null) {
            return false;
        }
        try {
            return (boolean) IS_USING_PIPELINE.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Beryl's shadow framebuffer, or {@code null} while shadows are unavailable. */
    public static Framebuffer getShadowFramebuffer() {
        try {
            return F_SHADOW_FRAMEBUFFER != null ? (Framebuffer) F_SHADOW_FRAMEBUFFER.get(null) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Beryl's shadow depth image (already transitioned to shader-read layout). */
    public static VulkanImage getShadowMapImage() {
        Framebuffer fb = getShadowFramebuffer();
        VulkanImage image = fb != null ? fb.getDepthAttachment() : null;
        if (image == null && !SHADOW_MAP_WARNED && berylShaderEnabled()) {
            SHADOW_MAP_WARNED = true;
            Logger.warn("Beryl shadow map is unavailable; far LOD keeps the per-voxel sun-shadow fallback.");
        }
        return image;
    }

    public static boolean isShadowMapActive() {
        return berylShaderEnabled() && getShadowMapImage() != null && getShadowTexelSize() > 0.0f;
    }

    public static float getShadowBias() {
        try {
            return F_SHADOW_BIAS != null ? F_SHADOW_BIAS.getFloat(null) : 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    public static float getShadowDistortion() {
        try {
            return F_SHADOW_DISTORTION != null ? F_SHADOW_DISTORTION.getFloat(null) : 1.0f;
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    public static float getShadowTexelSize() {
        try {
            return F_SHADOW_TEXEL_SIZE != null ? F_SHADOW_TEXEL_SIZE.getFloat(null) : 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    /** Copies Beryl's current camera-relative light-space matrix into {@code dest}. */
    public static Matrix4f getLightSpaceMatrix(Matrix4f dest) {
        if (dest == null) {
            dest = new Matrix4f();
        }
        try {
            MappedBuffer buf = F_LIGHT_SPACE_MAT_BUF != null ? (MappedBuffer) F_LIGHT_SPACE_MAT_BUF.get(null) : null;
            if (buf != null) {
                dest.set(
                        buf.getFloat(0), buf.getFloat(4), buf.getFloat(8), buf.getFloat(12),
                        buf.getFloat(16), buf.getFloat(20), buf.getFloat(24), buf.getFloat(28),
                        buf.getFloat(32), buf.getFloat(36), buf.getFloat(40), buf.getFloat(44),
                        buf.getFloat(48), buf.getFloat(52), buf.getFloat(56), buf.getFloat(60));
                return dest;
            }
        } catch (Throwable ignored) {
        }
        return dest.identity();
    }

    /** Copies Beryl's current light-space normal offset into {@code dest}. */
    public static Vector3f getLightSpaceOffset(Vector3f dest) {
        if (dest == null) {
            dest = new Vector3f();
        }
        try {
            MappedBuffer buf = F_LIGHT_SPACE_OFFSET_BUF != null ? (MappedBuffer) F_LIGHT_SPACE_OFFSET_BUF.get(null) : null;
            if (buf != null) {
                dest.set(buf.getFloat(0), buf.getFloat(4), buf.getFloat(8));
                return dest;
            }
        } catch (Throwable ignored) {
        }
        return dest.zero();
    }

    public static void disableBerylShaders() {
        if (!BERYL_INSTALLED || SET_USE_PIPELINE == null) {
            return;
        }
        try {
            SET_USE_PIPELINE.invoke(null, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Writes 80 bytes (5 x vec4) of Beryl lighting & sky uniforms to {@code ptr}:
     * <ul>
     *   <li>{@code +0}: {@code vec4(lightDirWS.xyz, berylActive ? 1.0 : 0.0)}</li>
     *   <li>{@code +16}: {@code vec4(LightColor.rgb, LightIntensity)}</li>
     *   <li>{@code +32}: {@code vec4(AmbientLight.rgb, LightVisibility)}</li>
     *   <li>{@code +48}: {@code vec4(SkyColor.rgba)}</li>
     *   <li>{@code +64}: {@code vec4(AmbientLightFactor, NightMultiplier, MinAmbientLight, FogFactor)}</li>
     * </ul>
     */
    public static void writeBerylUniforms(long ptr) {
        if (!berylShaderEnabled()) {
            MemoryUtil.memSet(ptr, 0, 80);
            return;
        }
        try {
            Vector3f dir = F_LIGHT_DIR_WS != null ? (Vector3f) F_LIGHT_DIR_WS.get(null) : null;
            MappedBuffer lightCol = F_LIGHT_COLOR_BUF != null ? (MappedBuffer) F_LIGHT_COLOR_BUF.get(null) : null;
            MappedBuffer ambCol = F_AMBIENT_COLOR_BUF != null ? (MappedBuffer) F_AMBIENT_COLOR_BUF.get(null) : null;
            MappedBuffer skyCol = F_SKY_COLOR_BUF != null ? (MappedBuffer) F_SKY_COLOR_BUF.get(null) : null;

            float lx = dir != null ? dir.x : 0.0f;
            float ly = dir != null ? dir.y : 1.0f;
            float lz = dir != null ? dir.z : 0.0f;

            float lcr = lightCol != null ? lightCol.getFloat(0) : 1.0f;
            float lcg = lightCol != null ? lightCol.getFloat(4) : 0.9f;
            float lcb = lightCol != null ? lightCol.getFloat(8) : 0.8f;
            float lInt = F_LIGHT_INTENSITY != null ? F_LIGHT_INTENSITY.getFloat(null) : 1.0f;

            float acr = ambCol != null ? ambCol.getFloat(0) : 0.6f;
            float acg = ambCol != null ? ambCol.getFloat(4) : 0.65f;
            float acb = ambCol != null ? ambCol.getFloat(8) : 0.75f;
            float lVis = F_LIGHT_VISIBILITY != null ? F_LIGHT_VISIBILITY.getFloat(null) : 1.0f;

            float scr = skyCol != null ? skyCol.getFloat(0) : 0.4f;
            float scg = skyCol != null ? skyCol.getFloat(4) : 0.6f;
            float scb = skyCol != null ? skyCol.getFloat(8) : 1.0f;
            float sca = skyCol != null ? skyCol.getFloat(12) : 1.0f;

            float ambFactor = F_AMBIENT_FACTOR != null ? F_AMBIENT_FACTOR.getFloat(null) : 0.3f;
            float night = F_NIGHT_MULTIPLIER != null ? F_NIGHT_MULTIPLIER.getFloat(null) : 0.0f;
            float minAmb = F_MIN_AMBIENT != null ? F_MIN_AMBIENT.getFloat(null) : 0.035f;
            float fogFactor = F_FOG_FACTOR != null ? F_FOG_FACTOR.getFloat(null) : 0.0003f;

            MemoryUtil.memPutFloat(ptr + 0, lx);
            MemoryUtil.memPutFloat(ptr + 4, ly);
            MemoryUtil.memPutFloat(ptr + 8, lz);
            MemoryUtil.memPutFloat(ptr + 12, 1.0f);

            MemoryUtil.memPutFloat(ptr + 16, lcr);
            MemoryUtil.memPutFloat(ptr + 20, lcg);
            MemoryUtil.memPutFloat(ptr + 24, lcb);
            MemoryUtil.memPutFloat(ptr + 28, lInt);

            MemoryUtil.memPutFloat(ptr + 32, acr);
            MemoryUtil.memPutFloat(ptr + 36, acg);
            MemoryUtil.memPutFloat(ptr + 40, acb);
            MemoryUtil.memPutFloat(ptr + 44, lVis);

            MemoryUtil.memPutFloat(ptr + 48, scr);
            MemoryUtil.memPutFloat(ptr + 52, scg);
            MemoryUtil.memPutFloat(ptr + 56, scb);
            MemoryUtil.memPutFloat(ptr + 60, sca);

            MemoryUtil.memPutFloat(ptr + 64, ambFactor);
            MemoryUtil.memPutFloat(ptr + 68, night);
            MemoryUtil.memPutFloat(ptr + 72, minAmb);
            MemoryUtil.memPutFloat(ptr + 76, fogFactor);
        } catch (Throwable t) {
            MemoryUtil.memSet(ptr, 0, 80);
        }
    }

    /** Returns {@code [lightDirWS.x, lightDirWS.y, lightDirWS.z, berylActive ? 1f : 0f]}. */
    public static float[] getBerylLightDirAndActive() {
        boolean active = berylShaderEnabled();
        try {
            Vector3f dir = F_LIGHT_DIR_WS != null ? (Vector3f) F_LIGHT_DIR_WS.get(null) : null;
            if (dir != null && dir.lengthSquared() > 1e-4f) {
                return new float[]{dir.x, dir.y, dir.z, active ? 1.0f : 0.0f};
            }
        } catch (Throwable ignored) {
        }
        // Fallback before the first frame has rendered: compute exact world-space sun vector from level time
        try {
            var mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                long dayTime = mc.level.getDayTime() % 24000L;
                if (dayTime < 0) dayTime += 24000L;
                float d = (float) dayTime / 24000.0f - 0.25f;
                if (d < 0.0f) d += 1.0f;
                float celestialAngle = (d * 2.0f + (0.5f - (float) Math.cos(d * Math.PI) * 0.5f)) / 3.0f;
                if (celestialAngle > 0.3f && celestialAngle < 0.7f) {
                    celestialAngle += 0.5f;
                }
                Matrix4f m = new Matrix4f()
                        .translate(0.0f, 0.0f, -100.0f)
                        .rotateX((float) Math.toRadians(90.0f))
                        .rotateY((float) Math.toRadians(90.0f))
                        .rotateZ((float) Math.toRadians(celestialAngle * -360.0f))
                        .rotateX((float) Math.toRadians(-20.0f));
                Vector3f v = new Vector3f(m.m02(), m.m12(), m.m22()).normalize();
                if (v.lengthSquared() > 1e-4f) {
                    return new float[]{v.x, v.y, v.z, active ? 1.0f : 0.0f};
                }
            }
        } catch (Throwable ignored) {
        }
        return new float[]{0.07186f, 0.93726f, 0.34114f, active ? 1.0f : 0.0f};
    }

    /**
     * Altitude-only compatibility shim for Beryl's shadow projection.
     *
     * <p>At ground level this returns immediately and leaves Beryl's state
     * completely untouched: we are a read-only consumer of Beryl in normal play.
     * The correction only runs above 6 blocks of altitude, where Beryl's
     * camera-centred fish-eye distortion ({@code ~0.925}) wastes 93% of
     * shadow-map resolution on empty mid-air and its {@code [-100, 192]} depth
     * range clips the ground into solid black shadow.</p>
     */
    public static void tuneBerylShadowParameters() {
        if (!berylShaderEnabled() || GET_STAGE == null) {
            return;
        }
        try {
            Object stage = GET_STAGE.invoke(null);
            if (stage == null || !"UNDEFINED".equals(stage.toString())) {
                return;
            }
            var mc = Minecraft.getInstance();
            // Read-only: report which distance Beryl will actually use. This never
            // writes to Beryl's config, so beryl_settings.json stays authoritative.
            if (BERYL_CONFIG_OBJ != null && F_SHADOW_RENDER_DISTANCE != null) {
                int configured = F_SHADOW_RENDER_DISTANCE.getInt(BERYL_CONFIG_OBJ);
                int targetShadowDist = Mth.clamp(configured, 2, 24);
                if (targetShadowDist != LAST_LOGGED_SHADOW_DISTANCE) {
                    LAST_LOGGED_SHADOW_DISTANCE = targetShadowDist;
                    int vanillaRd = mc.options != null ? mc.options.getEffectiveRenderDistance() : -1;
                    Logger.info("[Beryl] shadow render distance: " + targetShadowDist
                            + " chunks (config " + configured + ", vanilla " + vanillaRd + ")");
                }
            }
            float altitude = 0.0f;
            if (mc.level != null && mc.gameRenderer != null) {
                var camPos = mc.gameRenderer.getMainCamera().position();
                int surfaceY = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(camPos.x), Mth.floor(camPos.z));
                altitude = (float) Math.max(0.0, camPos.y - surfaceY);
            }

            // Ground level: Beryl's own projection is correct here, so leave its
            // shadow state completely untouched. The correction below only exists
            // because Beryl's fixed [-100, 192] depth range and camera-centred
            // fish-eye distortion break when the camera is far above the terrain.
            if (altitude <= 6.0f) {
                return;
            }

            if (F_ORTHO_HALF_LENGTH != null && BERYL_CONFIG_OBJ != null && F_SHADOW_RENDER_DISTANCE != null) {
                F_ORTHO_HALF_LENGTH.setFloat(null, Mth.clamp(
                        F_SHADOW_RENDER_DISTANCE.getInt(BERYL_CONFIG_OBJ), 2, 24) * 16.0f);
            }

            float altFactor = Math.clamp((altitude - 6.0f) / 100.0f, 0.0f, 1.0f);
            float zExtent = Math.max(256.0f, altitude + 128.0f);

            if (F_SHADOW_DISTORTION != null) {
                float baseDistortion = Math.min(F_SHADOW_DISTORTION.getFloat(null), 0.85f);
                float tunedDistortion = Mth.lerp(altFactor, baseDistortion, 0.25f);
                F_SHADOW_DISTORTION.setFloat(null, tunedDistortion);
            }

            if (F_SHADOW_BIAS != null) {
                float depthScale = zExtent / 146.0f;
                float tunedBias = (0.00045f + 0.0022f * altFactor) * depthScale;
                F_SHADOW_BIAS.setFloat(null, tunedBias);
            }

            if (F_LIGHT_SPACE_OFFSET_BUF != null && altFactor > 0.0f) {
                MappedBuffer offsetBuf = (MappedBuffer) F_LIGHT_SPACE_OFFSET_BUF.get(null);
                if (offsetBuf != null && offsetBuf.buffer != null) {
                    float scale = 1.0f + 1.5f * altFactor;
                    offsetBuf.buffer.putFloat(0, offsetBuf.buffer.getFloat(0) * scale);
                    offsetBuf.buffer.putFloat(4, offsetBuf.buffer.getFloat(4) * scale);
                    offsetBuf.buffer.putFloat(8, offsetBuf.buffer.getFloat(8) * scale);
                }
            }

            if (F_LIGHT_PROJECTION != null && F_LIGHT_VIEW != null && F_LIGHT_VP != null
                    && F_LIGHT_SPACE_MAT_BUF != null && F_ORTHO_HALF_LENGTH != null) {
                float halfLen = F_ORTHO_HALF_LENGTH.getFloat(null);
                Matrix4f lightProj = (Matrix4f) F_LIGHT_PROJECTION.get(null);
                Matrix4f lightView = (Matrix4f) F_LIGHT_VIEW.get(null);
                Matrix4f lightVp = (Matrix4f) F_LIGHT_VP.get(null);
                MappedBuffer matBuf = (MappedBuffer) F_LIGHT_SPACE_MAT_BUF.get(null);
                if (lightProj != null && lightView != null && lightVp != null && matBuf != null) {
                    lightProj.setOrthoSymmetric(halfLen * 2.0f, halfLen * 2.0f, -zExtent, zExtent, true);
                    lightVp.set(lightProj).mul(lightView);
                    lightVp.get(matBuf.buffer);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Returns Beryl's {@code tempFramebuffer} colour attachment (which holds the
     * combined near-field + far-field opaque HDR scene captured before the
     * translucent pass for SSR), or {@code null} when unavailable.
     */
    public static VulkanImage getBerylTempColorImage() {
        if (!berylShaderEnabled() || F_TEMP_FRAMEBUFFER == null) {
            return null;
        }
        try {
            Framebuffer fb = (Framebuffer) F_TEMP_FRAMEBUFFER.get(null);
            if (fb != null) {
                VulkanImage color = fb.getColorAttachment();
                if (color != null && color.getId() != 0L && color.getCurrentLayout() == 5) {
                    return color;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** No-op in Vulkan: sampler state lives inside descriptor sets. */
    public static void clearBerylSamplers() {
    }

    public static void reload() {
    }
}
