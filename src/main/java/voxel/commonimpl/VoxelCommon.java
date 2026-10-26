package voxel.commonimpl;

import voxel.common.Logger;
import voxel.common.config.Serialization;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Common initialization for Voxel on Fabric.
 *
 * IMPORTANT: This class may be loaded very early via mixin class loading.
 * FabricLoader is available at that point (it is initialized by knot before
 * any mixins are applied), so it is safe to use it here.
 */
public class VoxelCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    static {
        boolean inMinecraft = false;
        String version = "<UNKNOWN>";
        boolean dedicatedServer = false;
        try {
            var loader = FabricLoader.getInstance();
            inMinecraft = loader.isModLoaded("minecraft");
            var mod = loader.getModContainer("voxel");
            if (mod.isPresent()) {
                version = mod.get().getMetadata().getVersion().getFriendlyString();
            }
            dedicatedServer = loader.getEnvironmentType() == EnvType.SERVER;
        } catch (Throwable t) {
            inMinecraft = false;
        }
        if (inMinecraft) {
            IS_IN_MINECRAFT = true;
            String commit = "unknown";
            MOD_VERSION = version + "-" + commit;
            IS_DEDICATED_SERVER = dedicatedServer;
            Serialization.init();
        } else {
            IS_IN_MINECRAFT = false;
            Logger.error("Running Voxel without minecraft");
            MOD_VERSION = "<UNKNOWN>";
            IS_DEDICATED_SERVER = false;
        }
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        String val = System.getProperty("voxel." + name, defaultOn ? "true" : "false");
        return val.equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxelInstance create();}
    private static VoxelInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxelInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        INSTANCE = FACTORY.create();
    }

    // Is Voxel available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
