package voxel.client.compat;

// TODO: Re-enable Flashback integration when a Fabric 1.21.11 build is available
// import com.moulberry.flashback.Flashback;
// import com.moulberry.flashback.playback.ReplayServer;
// import com.moulberry.flashback.record.FlashbackMeta;
import voxel.common.Logger;
import voxel.common.config.section.SectionStorageConfig;
// Fabric: FabricLoader is available again (unused - Flashback integration disabled)
// import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class FlashbackCompat {
    // Disabled for Fabric 1.21.11 port - Flashback not available
    public static final boolean FLASHBACK_INSTALLED = false; // FabricLoader.getInstance().isModLoaded("flashback");

    //AUDIT-DEAD: always returns null — noIngestOverride can never be true;
    // kept as a stub for a future Flashback port.
    public static Path getReplayStoragePath() {
        // Stubbed out - Flashback integration disabled for Fabric port
        return null;
        /*
        if (!FLASHBACK_INSTALLED) {
            return null;
        }
        return getReplayStoragePath0();
        */
    }

    /*
    private static Path getReplayStoragePath0() {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null) {
            FlashbackMeta meta = replayServer.getMetadata();
            if (meta != null) {
                var path = ((IFlashbackMeta)meta).getLingPath();
                if (path != null) {
                    Logger.info("Flashback replay server exists and meta exists");
                    if (path.exists()) {
                        Logger.info("Flashback Voxel path exists in filesystem, using this as lod data source");
                        return path.toPath();
                    } else {
                        Logger.warn("Flashback meta had Voxel path saved but path doesnt exist");
                    }
                }
            }
        }
        return null;
    }
    */
}
