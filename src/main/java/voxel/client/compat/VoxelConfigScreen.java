package voxel.client.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.option.OptionPage;
import voxel.client.config.VoxelModSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Opens VulkanMod's native options screen directly on the Voxel page.
 *
 * <p>VulkanMod builds a flat list of pages from every registered mod entry.
 * Voxel already registers through that API, so reusing {@link VOptionScreen}
 * keeps Apply/Undo, tooltips and the VulkanMod look. The reflective step only
 * selects the page that this mod just created; if a future VulkanMod changes
 * its internals, the screen still opens on the default page.</p>
 */
public class VoxelConfigScreen extends VOptionScreen {

    public VoxelConfigScreen(Screen parent) {
        super(Component.translatable("voxel.title"), parent);
    }

    @Override
    protected void init() {
        super.init();
        try {
            OptionPage first = VoxelModSettings.firstPage();
            if (first == null) {
                return;
            }

            Field pagesField = VOptionScreen.class.getDeclaredField("optionPages");
            pagesField.setAccessible(true);
            Object value = pagesField.get(this);
            if (!(value instanceof List<?> pages)) {
                return;
            }

            int index = pages.indexOf(first);
            if (index < 0) {
                return;
            }

            Method select = VOptionScreen.class.getDeclaredMethod("setOptionList", int.class);
            select.setAccessible(true);
            select.invoke(this, index);
        } catch (Throwable ignored) {
            // Fall back to VulkanMod's default page; the entry is still usable.
        }
    }
}
