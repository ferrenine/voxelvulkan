package voxel.client;

import com.mojang.blaze3d.platform.InputConstants;
import voxel.client.config.VoxelConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Client event handlers for Voxel on Fabric.
 *
 * Handles fog rendering to push fog to infinity so Voxel LODs render
 * without a fog wall at vanilla render distance.
 */
public class VoxelClientEvents {

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (VoxelKeyBindings.RELOAD_KEY.consumeClick()) {
                var window = client.getWindow();
                boolean ctrl = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
                if (ctrl) {
                    VoxelCommands.reloadInstanceDirect();
                }
            }
        });

        // TODO(port): terrain fog push-to-infinity.
        // NeoForge had ViewportEvent.RenderFog; Fabric API has no equivalent event.
        // Either add a Mixin into FogRenderer.setupFog (extend fog end distance when
        // LOD rendering is active), or push the fog uniforms from the Vulkan render
        // pipeline where LOD terrain is submitted. Tracked for the renderer milestone.
    }

    /**
     * Returns true when the terrain fog should be pushed to (near) infinity because
     * LOD terrain keeps rendering beyond the vanilla fog wall.
     */
    public static boolean shouldPushFogToInfinity() {
        return VoxelConfig.CONFIG.enabled && VoxelConfig.CONFIG.enableRendering;
    }
}
