package voxel.client.core.rendering;

import voxel.client.core.util.BerylUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ViewportSelector <T extends Viewport<?>> {
    public static final boolean VIVECRAFT_INSTALLED = FabricLoader.getInstance().isModLoaded("vivecraft");

    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();//TODO should maybe be a weak hashmap with value cleanup queue thing?

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
    }

    private T getOrCreate(Object holder) {
        return this.extraViewports.computeIfAbsent(holder, a->this.creator.get());
    }

    // MC 1.21.1 NeoForge: Vivecraft VR rendering not available
    // private T getVivecraftViewport() {
    //     var pass = VRRenderingAPI.instance().getCurrentRenderPass();
    //     if (pass == null || pass == VANILLA) {
    //         return null;
    //     }
    //     return this.getOrCreate(pass);
    // }

    private static final Object BERYL_SHADOW_OBJECT = new Object();
    public T getViewport() {
        if (BerylUtil.berylShadowActive()) {
            return this.getOrCreate(BERYL_SHADOW_OBJECT);
        }
        return this.defaultViewport;
    }

    /** The main camera viewport, also while Beryl renders its shadow map. */
    public T getDefaultViewport() {
        return this.defaultViewport;
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(Viewport::delete);
        this.extraViewports.clear();
    }
}
