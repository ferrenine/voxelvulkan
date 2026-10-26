package voxel.client.mixin.vulkanmod;

import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Read-only access to the sidebar widgets and selected page for the mixins. */
@Mixin(VOptionScreen.class)
public interface VOptionScreenAccess {

    @Accessor("modSettingsEntries")
    List<ModSettingsEntry> voxel$getModSettingsEntries();

    @Accessor("iconWidgets")
    List<VAbstractWidget> voxel$getIconWidgets();

    @Accessor("pageButtons")
    List<VButtonWidget> voxel$getPageButtons();

    @Accessor("currentListIdx")
    int voxel$getCurrentListIdx();
}
