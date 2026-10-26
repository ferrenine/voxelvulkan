package voxel.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import voxel.common.Logger;
import voxel.commonimpl.VoxelCommon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxelConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxelConfig CONFIG = loadOrCreate();

    /**
     * Conservative worker count for a machine of this size. The LOD service
     * competes with the render thread, Beryl, and vanilla chunk meshing, so
     * use a quarter of the logical CPUs for the LOD pool and leave the rest
     * for the engine.
     */
    public static int recommendedWorkerThreads() {
        int logical = Runtime.getRuntime().availableProcessors();
        if (logical <= 2) {
            return 1;
        }
        return Math.max(2, Math.min(12, logical / 4));
    }

    /** Upper bound exposed by the settings slider. */
    public static int maxWorkerThreads() {
        int logical = Runtime.getRuntime().availableProcessors();
        return Math.max(recommendedWorkerThreads(), Math.min(12, Math.max(1, logical / 2)));
    }

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public int sectionRenderDistance = 16;
    public int serviceThreads = recommendedWorkerThreads();
    // Minimum on-screen size (pixels) a node must have before the traversal
    // subdivides it. Larger = coarser far LOD and fewer quads. At 1600p / 70掳
    // FOV the default 64 gives roughly: LOD 0 to 1.1 km, LOD 1 to 2.3 km,
    // LOD 2 to 4.6 km, LOD 3 to 9.1 km. 28 is the old auto-balancer floor: it
    // keeps subdividing out to several kilometres, so the view collapses to
    // LOD 0 and the far ring is never used.
    public float subDivisionSize = 64.0f;

    // Far LOD is rendered at this fraction of the window size and upscaled by
    // the final blit. 1.0 = native; 0.75 keeps 56% of the pixels.
    public float lodRenderScale = 0.75f;

    public boolean useEnvironmentalFog = true;
    //AUDIT-DEAD: never read 鈥?Sodium is not present on this build.
    public boolean dontUseSodiumBuilderThreads = false;

    // LOD boundary buffer: controls the safety margin between vanilla chunks and LOD rendering
    // Higher values = more overlap, prevents pop-in at chunk boundaries when flying
    // Range: 0-4 blocks, default 1 (standard overlap behavior)
    public int lodBoundaryBuffer = 1;

    // Per-buffer geometry VRAM size in MB (0 = Auto detection based on GPU VRAM,
    // e.g. 512, 768, 1024, 1536). A second light/AO buffer of the same size is
    // always allocated, so total usage is 2x this value. Prevents VRAM overflow
    // on 4GB / 6GB / 8GB GPUs.
    public int geometryBufferSizeMB = 0;

    // LOD Water & Translucent Reflection (Screen-Space Reflection & wave normal enhancement)
    public boolean enableWaterSSR = true;

    // Distant Shader Shadows: lets Voxel LOD terrain cast into Beryl's shadow map.
    // This is a compatibility feature (far trees/hills shadow far terrain), not a
    // performance optimisation; it costs one extra LOD draw into the shadow pass.
    public boolean enableDistantShaderShadows = true;

    // Debug settings
    public boolean renderStatistics = false;
    // GPU pass timestamps (VkQueryPool). Read back without vkDeviceWaitIdle;
    // costs one query-pool read every 15 frames plus 8 timestamp writes, and
    // the BOTTOM_OF_PIPE timestamps can serialise passes on some GPUs. Turn
    // off when measuring the absolute maximum FPS.
    public boolean gpuTiming = false;

    private static VoxelConfig loadOrCreate() {
        //NOTE: always try the file first 鈥?returning the disabled fallback just
        // because VoxelCommon isn't available yet permanently poisons CONFIG
        // (this is a static initializer, it only runs once).
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                var conf = GSON.fromJson(reader, VoxelConfig.class);
                if (conf != null) {
                    conf.validate();
                    if (VoxelCommon.isAvailable()) {
                        conf.save();
                    }
                    return conf;
                }
                Logger.error("Failed to load Voxel config, resetting");
            } catch (IOException e) {
                Logger.error("Could not parse config", e);
            }
        }
        var config = new VoxelConfig();
        if (!VoxelCommon.isAvailable()) {
            config.enabled = false;
            config.enableRendering = false;
        } else {
            config.save();
        }
        return config;
    }

    private void validate() {
        // 28 px is the floor of the old FPS auto-balancer (not wired in this
        // port). At that size shouldDecend() stays true almost everywhere in
        // the render ring, so every node walks down to LOD 0: multi-million
        // quad frames and no coarse LOD at all. Treat it as a stale value and
        // restore the intended default; >= 32 is accepted as deliberate.
        if (this.subDivisionSize < 32.0f) {
            Logger.warn("sub_division_size=" + this.subDivisionSize
                    + " is below the supported minimum (32); resetting to 64. "
                    + "Very small values force the traversal down to LOD 0 across the whole render distance.");
            this.subDivisionSize = 64.0f;
        }
        this.subDivisionSize = Math.clamp(this.subDivisionSize, 32.0f, 256.0f);
        this.lodRenderScale = Math.clamp(this.lodRenderScale, 0.5f, 1.0f);
        // Older builds allowed up to availableProcessors()/1.5 workers. Clamp
        // stale configs to the conservative budget shown by the UI.
        int maxWorkers = maxWorkerThreads();
        if (this.serviceThreads < 1) {
            this.serviceThreads = recommendedWorkerThreads();
        } else if (this.serviceThreads > maxWorkers) {
            Logger.warn("service_threads=" + this.serviceThreads + " exceeds the supported maximum ("
                    + maxWorkers + "); clamping.");
            this.serviceThreads = maxWorkers;
        }
    }

    public void save() {
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("voxel-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxelCommon.isAvailable() && this.enabled && this.enableRendering;
    }

    /** Master switch combined with the per-feature ingest toggle. */
    public boolean isIngestEnabled() {
        return this.enabled && this.ingestEnabled;
    }
}

