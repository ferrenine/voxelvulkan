package voxel.common.world.service;

import voxel.common.Logger;
import voxel.common.thread.Service;
import voxel.common.thread.ServiceManager;
import voxel.common.voxelization.ILightingSupplier;
import voxel.common.voxelization.VoxelizedSection;
import voxel.common.voxelization.WorldConversionFactory;
import voxel.common.world.WorldEngine;
import voxel.common.world.WorldUpdater;
import voxel.commonimpl.VoxelCommon;
import voxel.commonimpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    private record IngestSection(int cx, int cy, int cz, WorldEngine world, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight){}
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.poll();
        if (task == null) {
            return;
        }
        task.world.markActive();

        var section = task.section;
        var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

        if (section.hasOnlyAir() && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
            WorldUpdater.insertUpdate(task.world, vs.zero());
        } else {
            VoxelizedSection csec = WorldConversionFactory.convert(
                    SECTION_CACHE.get(),
                    task.world.getMapper(),
                    section.getStates(),
                    section.hasOnlyAir() ? null : section.getBiomes(),
                    getLightingSupplier(task)
            );
            WorldConversionFactory.mipSection(csec, task.world.getMapper());
            WorldUpdater.insertUpdate(task.world, csec);
        }
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();

        // Default sky light to 15 (full daylight) if light arrays are uninitialized or delayed,
        // except for deep underwater sections (cy <= 2 with fluids) where an empty skyLight array means 0 sky light.
        final byte defaultSky = (task.cy <= 2 && !task.section.hasOnlyAir()
                // Lithium's LithiumHashPalette passes null BlockStates to maybeHas' predicate
                // (vanilla guarantees non-null); guard against it.
                && task.section.maybeHas(s -> s != null && !s.getFluidState().isEmpty())) ? (byte) 0 : (byte) 15;

        if (sl && bl) {
            return (x, y, z) -> {
                int block = Math.min(15, bla.get(x, y, z));
                int sky = Math.min(15, sla.get(x, y, z));
                return (byte) (sky | (block << 4));
            };
        } else if (sl) {
            return (x, y, z) -> {
                int block = 0;
                int sky = Math.min(15, sla.get(x, y, z));
                return (byte) (sky | (block << 4));
            };
        } else if (bl) {
            return (x, y, z) -> {
                int block = Math.min(15, bla.get(x, y, z));
                return (byte) (defaultSky | (block << 4));
            };
        } else {
            return (x, y, z) -> defaultSky;
        }
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    private static final java.util.concurrent.atomic.AtomicLong DEFERRED_LIGHT_SECTIONS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * True when neither light layer has data yet, i.e. the chunk is not lit and
     * only the block data exists. Baking such a snapshot stores zero sky light
     * everywhere; the renderer's fallback treats missing light as full daylight,
     * which is what made LOD canopies render as uniform bright shells. The
     * light packet (enableChunkLight) re-enqueues the chunk afterwards.
     */
    private static boolean lightNotReady(Level level, int cx, int cy, int cz, LevelChunkSection section) {
        if (section.hasOnlyAir()) {
            return false;
        }
        var lp = level.getLightEngine();
        var pos = SectionPos.of(cx, cy, cz);
        boolean skyReady = lp.getDebugSectionType(LightLayer.SKY, pos)
                == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA;
        boolean blockReady = lp.getDebugSectionType(LightLayer.BLOCK, pos)
                == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA;
        return !skyReady && !blockReady;
    }

    private static void countDeferred() {
        DEFERRED_LIGHT_SECTIONS.incrementAndGet();
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        // MC 1.21.1: LevelChunk.getMinSectionY() → chunk.getLevel().getMinSectionY()
        int i = chunk.getLevel().getMinSectionY() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty &= section.hasOnlyAir();
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (allEmpty && !gotLighting) {
            // Special case all empty chunk columns, we need to clear it out
            i = chunk.getLevel().getMinSectionY() - 1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                engine.markActive();
                this.ingestQueue.add(new IngestSection(chunk.getPos().x, i, chunk.getPos().z, engine, section, null, null));
                try {
                    this.service.execute();
                } catch (Exception e) {
                    Logger.error("Executing had an error: assume shutting down, aborting", e);
                    break;
                }
            }
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);

        var sections = chunk.getSections();
        // MC 1.21.1: LevelChunk.getMinSectionY() → chunk.getLevel().getMinSectionY()
        int minSecY = chunk.getLevel().getMinSectionY();
        for (int idx = 0; idx < sections.length; idx++) {
            var section = sections[idx];
            int secY = minSecY + idx;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, secY, chunk.getPos().z)) continue;
            if (lightNotReady(chunk.getLevel(), chunk.getPos().x, secY, chunk.getPos().z, section)) {
                countDeferred();
                continue;
            }
            var pos = SectionPos.of(chunk.getPos(), secY);

            DataLayer bl = null;
            if (blp != null) {
                var rawBl = blp.getDataLayerData(pos);
                if (rawBl != null) {
                    bl = rawBl.copy();
                }
            }

            DataLayer sl = null;
            if (slp != null) {
                var rawSl = slp.getDataLayerData(pos);
                if (rawSl != null) {
                    sl = rawSl.copy();
                }
            }

            this.ingestQueue.add(new IngestSection(chunk.getPos().x, secY, chunk.getPos().z, engine, section, bl, sl));
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting", e);
                break;
            }
        }
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
        while (!this.ingestQueue.isEmpty()) {
            try {
                this.processJob();
            } catch (Exception e) {
                break;
            }
        }
        this.ingestQueue.clear();
    }

    public boolean enqueueIngest(WorldEngine engine, net.minecraft.world.level.chunk.ChunkAccess chunk, Level level) {
        if (!this.service.isLive() || !engine.isLive() || chunk == null || level == null) return false;
        engine.markActive();
        var lightingProvider = level.getLightEngine();
        int minSec = level.getMinSectionY();
        int i = minSec - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            if (lightNotReady(level, chunk.getPos().x, i, chunk.getPos().z, section)) {
                countDeferred();
                continue;
            }
            var pos = SectionPos.of(chunk.getPos(), i);
            DataLayer bl = null;
            DataLayer sl = null;
            if (lightingProvider != null) {
                var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
                var slp = lightingProvider.getLayerListener(LightLayer.SKY);
                if (blp != null) {
                    var rawBl = blp.getDataLayerData(pos);
                    if (rawBl != null) bl = rawBl.copy();
                }
                if (slp != null) {
                    var rawSl = slp.getDataLayerData(pos);
                    if (rawSl != null) sl = rawSl.copy();
                }
            }
            this.ingestQueue.add(new IngestSection(chunk.getPos().x, i, chunk.getPos().z, engine, section, bl, sl));
            try {
                this.service.execute();
            } catch (Exception e) {
                break;
            }
        }
        return true;
    }

    public static boolean tryAutoIngestChunkAccess(net.minecraft.world.level.chunk.ChunkAccess chunk, Level level) {
        if (chunk == null || level == null) return false;
        var worldId = WorldIdentifier.of(level);
        if (worldId == null) return false;
        var instance = VoxelCommon.getInstance();
        if (instance == null) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk, level);
    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxelCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        this.ingestQueue.add(new IngestSection(x, y, z, engine, section, bl, sl));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            return false;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, bl, sl);
    }
}
