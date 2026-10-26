package voxel.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional Mod Menu bridge. Voxel's settings live in VulkanMod's settings
 * registry, so without this entrypoint Mod Menu has no config button for the
 * mod. The factory opens the same VulkanMod options screen and selects the
 * Voxel page when the screen initializes.
 */
public class VoxelModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return VoxelConfigScreen::new;
    }
}
