package voxel.client;

import voxel.client.compat.FlashbackCompat;
import voxel.client.config.VoxelConfig;
import voxel.common.Logger;
import voxel.common.StorageConfigUtil;
import voxel.common.config.ConfigBuildCtx;
import voxel.common.config.Serialization;
import voxel.common.config.compressors.ZSTDCompressor;
import voxel.common.config.section.SectionSerializationStorage;
import voxel.common.config.section.SectionStorage;
import voxel.common.config.section.SectionStorageConfig;
import voxel.common.config.storage.other.CompressionStorageAdaptor;
import voxel.common.config.storage.rocksdb.RocksDBStorageBackend;
import voxel.commonimpl.ImportManager;
import voxel.commonimpl.VoxelInstance;
import voxel.commonimpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxelClientInstance extends VoxelInstance {
    public static boolean isInGame = false;

    private final SectionStorageConfig storageConfig;
    private final Path basePath;
    private final boolean noIngestOverride;
    public VoxelClientInstance() {
        super();
        var path = FlashbackCompat.getReplayStoragePath();
        this.noIngestOverride = path != null;
        if (path == null) {
            path = getBasePath();
        }
        this.basePath = path;
        this.storageConfig = StorageConfigUtil.getCreateStorageConfig(Config.class, c->c.version==1&&c.sectionStorageConfig!=null, ()->DEFAULT_STORAGE_CONFIG, path).sectionStorageConfig;
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        int target = Math.max(1, Math.min(VoxelConfig.CONFIG.serviceThreads, VoxelConfig.maxWorkerThreads()));
        this.setNumThreads(target);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.storageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return (!this.noIngestOverride) && VoxelConfig.CONFIG.isIngestEnabled();
    }

    private static class Config {
        public int version = 1;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxel").resolve("saves");
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            return iserver.getWorldPath(LevelResource.ROOT).resolve("voxel").toAbsolutePath();
        }

        // 1. Current server from server list or packet listener
        net.minecraft.client.multiplayer.ServerData serverData = Minecraft.getInstance().getCurrentServer();
        if (serverData == null && Minecraft.getInstance().getConnection() != null) {
            serverData = Minecraft.getInstance().getConnection().getServerData();
        }
        if (serverData == null && Minecraft.getInstance().gameMode != null && Minecraft.getInstance().gameMode.connection != null) {
            serverData = Minecraft.getInstance().gameMode.connection.getServerData();
        }

        if (serverData != null) {
            if (serverData.isRealm()) {
                return basePath.resolve("realms").toAbsolutePath();
            } else if (serverData.ip != null && !serverData.ip.isBlank()) {
                String cleanIp = serverData.ip.replaceAll("[^a-zA-Z0-9._-]", "_");
                return basePath.resolve(cleanIp).toAbsolutePath();
            }
        }

        // 2. Direct connection socket remote address
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null && connection.getConnection() != null) {
            var address = connection.getConnection().getRemoteAddress();
            if (address != null) {
                String cleanAddr = address.toString().replace("/", "").replaceAll("[^a-zA-Z0-9._-]", "_");
                if (!cleanAddr.isBlank()) {
                    return basePath.resolve(cleanAddr).toAbsolutePath();
                }
            }
        }

        return basePath.resolve("UNKNOWN").toAbsolutePath();
    }
}
