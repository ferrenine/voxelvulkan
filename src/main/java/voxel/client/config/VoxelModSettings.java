package voxel.client.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.PerformanceImpact;
import net.vulkanmod.config.option.RangeOption;
import net.vulkanmod.config.option.SwitchOption;
import voxel.client.core.IGetVoxelRenderSystem;
import voxel.client.core.VoxelRenderSystem;
import voxel.client.VoxelCommands;
import voxel.common.Logger;
import voxel.commonimpl.VoxelCommon;
import voxel.commonimpl.VoxelInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Voxel's page in VulkanMod's Mod Settings screen.
 *
 * <p>Registered through VulkanMod's own {@link ModSettingsRegistry}, the same
 * hook Beryl uses. No mixin, no reflection and no extra dependency: the options
 * are ordinary {@link Option}s rendered by VulkanMod's existing widgets.</p>
 *
 * <p>Values are written straight into {@link VoxelConfig}; {@code Apply} runs
 * {@link #apply()} which persists the JSON and forwards the handful of settings
 * that can change at runtime to the live engine.</p>
 */
public final class VoxelModSettings {

    // Options whose effect is baked into shader defines or allocations at renderer
    // creation time. Apply compares against this snapshot and reloads the renderer
    // when one of them changed, so every control in the screen actually takes effect.
    private static boolean snapshotValid;
    private static int snapGeometryMB;
    private static boolean snapEnvFog;
    private static boolean snapWaterSsr;
    private static boolean snapStatistics;
    private static boolean snapEnabled;
    private static boolean snapRendering;

    private VoxelModSettings() {
    }

    public static void register() {
        try {
            ModSettingsEntry entry = new ModSettingsEntry(
                    settingsTitle(),
                    () -> Identifier.fromNamespaceAndPath("voxel", "icon.png"),
                    VoxelModSettings::pages,
                    VoxelModSettings::apply);
            ModSettingsRegistry.INSTANCE.addModEntry(entry);
            Logger.info("Registered Voxel page in VulkanMod settings");
        } catch (Throwable t) {
            // A VulkanMod update moving its config GUI must never break the game.
            Logger.warn("VulkanMod settings page unavailable: " + t);
        }
    }

    /** "Voxel 1.1.0-alpha" in the VulkanMod settings sidebar. */
    private static Component settingsTitle() {
        String version = FabricLoader.getInstance().getModContainer("voxel")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("1.1.0-alpha");
        return Component.literal("Voxel ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(version).withStyle(ChatFormatting.GRAY));
    }

    private static List<OptionPage> pages() {
        VoxelConfig cfg = VoxelConfig.CONFIG;
        List<OptionPage> pages = new ArrayList<>();

        int recommendedThreads = VoxelConfig.recommendedWorkerThreads();
        int logicalThreads = Runtime.getRuntime().availableProcessors();
        int maxThreads = VoxelConfig.maxWorkerThreads();
        RangeOption workerThreads = range("voxel.option.worker_threads", 1, maxThreads, 1,
                v -> cfg.serviceThreads = v, () -> cfg.serviceThreads);
        workerThreads.setImpact(PerformanceImpact.MEDIUM);
        workerThreads.setTooltip(v -> Component.translatable("voxel.option.worker_threads.tooltip",
                recommendedThreads, logicalThreads, maxThreads));

        pages.add(new OptionPage(tr("voxel.page.engine"), new OptionBlock[]{
                block("voxel.block.core",
                        toggle("voxel.option.enabled", v -> cfg.enabled = v, () -> cfg.enabled),
                        toggle("voxel.option.rendering", v -> cfg.enableRendering = v, () -> cfg.enableRendering),
                        toggle("voxel.option.world_sampling", v -> cfg.ingestEnabled = v, () -> cfg.ingestEnabled)),
                block("voxel.block.threads", workerThreads),
                block("voxel.block.diagnostics",
                        toggle("voxel.option.f3_diagnostics", v -> cfg.renderStatistics = v, () -> cfg.renderStatistics),
                        toggle("voxel.option.gpu_timing", v -> cfg.gpuTiming = v, () -> cfg.gpuTiming))
        }));

        pages.add(new OptionPage(tr("voxel.page.display"), new OptionBlock[]{
                block("voxel.block.geometry",
                        range("voxel.option.horizon_distance", 1, 64, 1,
                                v -> Component.literal((v * 32) + " chunks"),
                                v -> cfg.sectionRenderDistance = v, () -> cfg.sectionRenderDistance)
                                .setImpact(PerformanceImpact.MEDIUM),
                        range("voxel.option.sub_division", 32, 256, 4,
                                v -> Component.literal(v + " blk"),
                                v -> cfg.subDivisionSize = v, () -> Math.round(cfg.subDivisionSize))
                                .setImpact(PerformanceImpact.HIGH),
                        range("voxel.option.lod_scale", 50, 100, 5,
                                v -> Component.literal(v + "%"),
                                v -> cfg.lodRenderScale = v / 100.0f, () -> Math.round(cfg.lodRenderScale * 100))
                                .setImpact(PerformanceImpact.HIGH),
                        range("voxel.option.seam_overlap", 0, 2, 1,
                                v -> cfg.lodBoundaryBuffer = v, () -> cfg.lodBoundaryBuffer),
                        range("voxel.option.vram_budget", 0, 4096, 256,
                                v -> Component.literal(v == 0 ? "Auto" : v + " MB/buffer (" + (v * 2) + " MB total)"),
                                v -> cfg.geometryBufferSizeMB = v, () -> cfg.geometryBufferSizeMB)
                                .setImpact(PerformanceImpact.MEDIUM))
        }));

        pages.add(new OptionPage(tr("voxel.page.atmosphere"), new OptionBlock[]{
                block("voxel.block.shading",
                        toggle("voxel.option.atmospheric_fog", v -> cfg.useEnvironmentalFog = v, () -> cfg.useEnvironmentalFog),
                        toggle("voxel.option.water_ssr", v -> cfg.enableWaterSSR = v, () -> cfg.enableWaterSSR),
                        toggle("voxel.option.shader_shadows", v -> cfg.enableDistantShaderShadows = v, () -> cfg.enableDistantShaderShadows))
        }));

        takeSnapshot();
        return pages;
    }

    /**
     * Runs on Apply: persist the JSON, then either push the settings that are read
     * live by the engine, or reload the renderer when a value is baked into shader
     * defines / allocations at creation time.
     */
    private static void apply() {
        VoxelConfig cfg = VoxelConfig.CONFIG;
        cfg.save();

        boolean rebuild = needsRebuild();
        takeSnapshot();

        if (rebuild && Minecraft.getInstance().level != null && VoxelCommon.getInstance() != null) {
            // Defer: tearing the renderer down inside the settings screen's own
            // tick has bitten us before (see VoxelCommands.reloadInstanceDirect).
            Minecraft.getInstance().execute(VoxelCommands::reloadInstanceDirect);
            return;
        }

        VoxelRenderSystem system = IGetVoxelRenderSystem.getNullable();
        if (system != null) {
            system.setRenderDistance(cfg.sectionRenderDistance);
        }

        VoxelInstance instance = VoxelCommon.getInstance();
        if (instance != null) {
            instance.updateDedicatedThreads();
        }
    }

    private static void takeSnapshot() {
        VoxelConfig cfg = VoxelConfig.CONFIG;
        snapGeometryMB = cfg.geometryBufferSizeMB;
        snapEnvFog = cfg.useEnvironmentalFog;
        snapWaterSsr = cfg.enableWaterSSR;
        snapStatistics = cfg.renderStatistics;
        snapEnabled = cfg.enabled;
        snapRendering = cfg.enableRendering;
        snapshotValid = true;
    }

    private static boolean needsRebuild() {
        if (!snapshotValid) {
            return false;
        }
        VoxelConfig cfg = VoxelConfig.CONFIG;
        return snapGeometryMB != cfg.geometryBufferSizeMB
                || snapEnvFog != cfg.useEnvironmentalFog
                || snapWaterSsr != cfg.enableWaterSSR
                || snapStatistics != cfg.renderStatistics
                || snapEnabled != cfg.enabled
                || snapRendering != cfg.enableRendering;
    }

    private static OptionBlock block(String titleKey, Option<?>... options) {
        return new OptionBlock(tr(titleKey), options);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static SwitchOption toggle(String key, Consumer<Boolean> setter, Supplier<Boolean> getter) {
        SwitchOption option = new SwitchOption(Component.translatable(key), setter, getter);
        option.setTooltip(v -> Component.translatable(key + ".tooltip"));
        return option;
    }

    private static RangeOption range(String key, int min, int max, int step,
                                     Consumer<Integer> setter, Supplier<Integer> getter) {
        return range(key, min, max, step, v -> Component.literal(String.valueOf(v)), setter, getter);
    }

    private static RangeOption range(String key, int min, int max, int step,
                                     Function<Integer, Component> formatter,
                                     Consumer<Integer> setter, Supplier<Integer> getter) {
        RangeOption option = new RangeOption(Component.translatable(key), min, max, step, formatter, setter, getter);
        option.setTooltip(v -> Component.translatable(key + ".tooltip"));
        return option;
    }
}
