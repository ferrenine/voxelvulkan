package voxel.client;

import voxel.client.core.vk.Capabilities;
import voxel.client.config.VoxelModSettings;
import voxel.client.core.model.bakery.BudgetBufferRenderer;
import voxel.client.core.rendering.util.SharedIndexBuffer;
import voxel.common.Logger;
import voxel.commonimpl.VoxelCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

import java.util.HashSet;

/**
 * Client initialization for Voxel on Fabric + VulkanMod.
 */
public class VoxelClient implements ClientModInitializer {
    private static final HashSet<String> SYNC_CAPTURE_LOCKS = new HashSet<>();

    @Override
    public void onInitializeClient() {
        VoxelKeyBindings.register();
        VoxelClientEvents.register();
        VoxelModSettings.register();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(VoxelCommands.register());
            dispatcher.register(VoxelCommands.registerFull());
        });
    }

    /** Called from {@code MixinVkRenderer} once VulkanMod's device & renderer are initialised. */
    public static void initVoxelClient() {
        Capabilities.init();
        //NOTE: VoxelConfig.CONFIG must NOT be touched here — isAvailable() is
        // still false before setInstanceFactory below, so loading the config
        // this early permanently caches the disabled fallback instance.

        // AUDIT-FIXME: hasBrokenDepthSampler is hardcoded false in Capabilities —
        // this branch and its part of systemSupported are currently dead.
        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, Voxel does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        // AUDIT-FIXME: INT64_t checked because quads3.vert, cmdgen.comp and
        // buildtranslucents.comp unconditionally `#extension GL_ARB_gpu_shader_int64
        // : require` — without it shader compilation hard-fails at runtime.
        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters
                && Capabilities.INSTANCE.INT64_t && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (systemSupported) {
            SharedIndexBuffer.INSTANCE.id();
            BudgetBufferRenderer.init();

            VoxelCommon.setInstanceFactory(VoxelClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }
        } else {
            Logger.error("Voxel is unsupported on your system.");
        }
    }

    // AUDIT-DEAD: SYNC_CAPTURE_LOCKS is never populated — always false;
    // branches keyed on this (VoxelRenderSystem ~269,271,323) never run.
    public static boolean isSyncCaptureActive() {
        return !SYNC_CAPTURE_LOCKS.isEmpty();
    }

    // AUDIT-DEAD: hardwired 0 — the occlusion-debug branches it gates
    // (VoxelRenderSystem ~172, AbstractRenderPipeline ~89, MDICSectionRenderer ~240) are dead.
    public static int getOcclusionDebugState() {
        return 0;
    }

    // AUDIT-DEAD: hardwired false — callers' "disabled" branches
    // (VoxelRenderSystem ~308, MixinVkWorldRenderer ~56) are dead.
    public static boolean disableVanillaChunkRender() {
        return false;
    }
}
