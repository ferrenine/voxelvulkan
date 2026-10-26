package voxel.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class VoxelKeyBindings {

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("voxel", "main"));

    // Ctrl+R in-game (NeoForge had KeyModifier.CONTROL; on Fabric the modifier is
    // checked in the tick handler in VoxelClientEvents).
    public static final KeyMapping RELOAD_KEY = new KeyMapping(
            "key.voxel.reload",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY
    );

    public static void register() {
        KeyBindingHelper.registerKeyBinding(RELOAD_KEY);
    }
}
