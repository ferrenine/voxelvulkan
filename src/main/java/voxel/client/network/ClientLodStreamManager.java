package voxel.client.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import voxel.common.Logger;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.ThreadLocalMemoryBuffer;
import voxel.common.world.SectionSerializationUtil;
import voxel.common.world.SectionSerializer;
import voxel.common.world.WorldEngine;
import voxel.common.world.WorldSection;
import voxel.commonimpl.WorldIdentifier;
import voxel.network.VoxelNetworking;
import voxel.network.payload.VoxelHelloPayload;
import voxel.network.payload.VoxelLodDataPayload;
import voxel.network.payload.VoxelLodRequestPayload;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages client-side LOD streaming requests and received voxel sections.
 *
 * <p>This is the client half of the protocol. The current build is client-only,
 * so a server companion is required before any payload can arrive. Everything
 * on this path is treated as untrusted: sizes, levels and coordinates are
 * validated before a section is acquired, and full sections are dropped when
 * the queue is saturated.</p>
 */
public class ClientLodStreamManager {
    private static final int MAX_STREAMING_RADIUS_CHUNKS = 128;
    private static final int MAX_PENDING_SECTIONS = 256;
    private static final long REQUEST_INTERVAL_MS = 10_000L;
    private static final long LOG_INTERVAL_MS = 3_000L;
    private static final long DROP_LOG_INTERVAL_MS = 10_000L;

    private static volatile boolean serverStreamingSupported = false;
    private static volatile int serverMaxRadiusChunks = MAX_STREAMING_RADIUS_CHUNKS;
    private static long lastRequestTime = 0;
    private static int lastRequestedX = Integer.MIN_VALUE;
    private static int lastRequestedZ = Integer.MIN_VALUE;

    /** Incremented whenever the client changes world; old queued payloads are discarded. */
    private static final AtomicInteger worldGeneration = new AtomicInteger();
    private static final AtomicInteger receivedSectionsCount = new AtomicInteger();
    private static final AtomicInteger droppedSectionsCount = new AtomicInteger();
    private static final AtomicInteger invalidSectionsCount = new AtomicInteger();
    private static volatile long lastLogReceivedTime = 0;
    private static volatile long lastDropLogTime = 0;
    private static volatile long lastInvalidLogTime = 0;

    // One native staging buffer per worker instead of a fresh malloc per packet.
    private static final ThreadLocalMemoryBuffer SECTION_BUFFER =
            new ThreadLocalMemoryBuffer(SectionSerializationUtil.BIGGEST_SERIALIZED_SECTION_SIZE);

    private static final ThreadPoolExecutor streamProcessingExecutor = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_SECTIONS),
            r -> {
                Thread t = new Thread(r, "Voxel Client LOD Stream Receiver");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            (r, executor) -> {
                int dropped = droppedSectionsCount.incrementAndGet();
                long now = System.currentTimeMillis();
                if (now - lastDropLogTime >= DROP_LOG_INTERVAL_MS) {
                    lastDropLogTime = now;
                    Logger.warn("Voxel LOD stream queue is full; dropped " + dropped + " section(s) so far");
                }
            });

    public static void onServerHello(VoxelHelloPayload payload) {
        if (payload == null) {
            return;
        }
        if (payload.protocolVersion() != VoxelNetworking.PROTOCOL_VERSION) {
            serverStreamingSupported = false;
            Logger.warn("Voxel server protocol " + payload.protocolVersion()
                    + " does not match client " + VoxelNetworking.PROTOCOL_VERSION
                    + "; server LOD streaming disabled");
            return;
        }

        serverStreamingSupported = payload.serverStreamingEnabled();
        serverMaxRadiusChunks = Math.clamp(payload.maxStreamingRadiusChunks(), 1, MAX_STREAMING_RADIUS_CHUNKS);
        Logger.info("Server LOD Streaming is " + (serverStreamingSupported
                ? "ENABLED (MaxRadius=" + serverMaxRadiusChunks + " chunks)"
                : "DISABLED"));

        // Trigger initial request around player
        triggerRequestIfMoved(true);
    }

    public static void tick() {
        if (!serverStreamingSupported || Minecraft.getInstance().player == null) {
            return;
        }
        triggerRequestIfMoved(false);
    }

    private static void triggerRequestIfMoved(boolean force) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(VoxelLodRequestPayload.TYPE)) {
            return;
        }

        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;

        long now = System.currentTimeMillis();
        // Request update if player moved more than 8 chunks or every 10 seconds
        if (force || now - lastRequestTime > REQUEST_INTERVAL_MS
                || Math.abs(chunkX - lastRequestedX) >= 8 || Math.abs(chunkZ - lastRequestedZ) >= 8) {
            lastRequestTime = now;
            lastRequestedX = chunkX;
            lastRequestedZ = chunkZ;

            int requestRadius = Math.clamp(serverMaxRadiusChunks, 1, MAX_STREAMING_RADIUS_CHUNKS);
            ClientPlayNetworking.send(new VoxelLodRequestPayload(
                    chunkX,
                    chunkZ,
                    requestRadius,
                    0,
                    WorldEngine.MAX_LOD_LAYER
            ));
        }
    }

    /**
     * Called on the client thread by {@link voxel.network.VoxelNetworkHandler}.
     * The world and engine are resolved here, before work moves to a worker
     * thread, because {@code Minecraft.getInstance().level} is not safe to read
     * from the stream workers.
     */
    public static void onLodDataReceived(VoxelLodDataPayload payload) {
        if (payload == null || !serverStreamingSupported) {
            return;
        }
        byte[] bytes = payload.compressedVoxelData();
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (bytes.length > SectionSerializationUtil.BIGGEST_SERIALIZED_SECTION_SIZE) {
            rejectInvalid("payload is too large (" + bytes.length + " bytes)");
            return;
        }

        var detailLevel = payload.detailLevel();
        var sectionLevel = WorldEngine.getLevel(payload.sectionKey());
        if (detailLevel < 0 || detailLevel > WorldEngine.MAX_LOD_LAYER
                || sectionLevel < 0 || sectionLevel > WorldEngine.MAX_LOD_LAYER) {
            rejectInvalid("invalid level detail=" + detailLevel + " section=" + sectionLevel);
            return;
        }

        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        WorldEngine engine = WorldIdentifier.ofEngine(level);
        if (engine == null || !engine.isLive()) {
            return;
        }

        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int centerChunkX = player.getBlockX() >> 4;
        int centerChunkZ = player.getBlockZ() >> 4;
        int radius = serverMaxRadiusChunks + 2;
        int dx = Math.abs(WorldEngine.getX(payload.sectionKey()) - centerChunkX);
        int dz = Math.abs(WorldEngine.getZ(payload.sectionKey()) - centerChunkZ);
        if (dx > radius || dz > radius) {
            rejectInvalid("section is outside the streaming radius (dx=" + dx + ", dz=" + dz + ")");
            return;
        }

        int generation = worldGeneration.get();
        try {
            streamProcessingExecutor.execute(() -> processLodData(payload, engine, generation, bytes));
        } catch (RejectedExecutionException e) {
            // The rejection handler normally absorbs this; keep the caller safe
            // if the executor is shutting down.
            droppedSectionsCount.incrementAndGet();
        }
    }

    private static void processLodData(VoxelLodDataPayload payload, WorldEngine engine,
                                       int generation, byte[] bytes) {
        if (generation != worldGeneration.get() || !engine.isLive()) {
            return;
        }

        MemoryBuffer buffer = SECTION_BUFFER.get();
        if (bytes.length > buffer.size) {
            rejectInvalid("payload does not fit the staging buffer");
            return;
        }
        MemoryUtil.memByteBuffer(buffer.address, bytes.length).put(bytes);

        WorldSection section = null;
        try {
            section = engine.acquire(payload.sectionKey());
            if (section == null) {
                return;
            }
            if (!SectionSerializer.deserialize(section, buffer.address, bytes.length)) {
                rejectInvalid("section " + payload.sectionKey() + " failed deserialization");
                return;
            }

            // Let the regular dirty/save services own persistence and mesh
            // regeneration; writing to the storage backend directly from two
            // stream workers duplicates those writes and races with the saver.
            engine.markDirty(section);
            engine.saveSection(section);

            int count = receivedSectionsCount.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastLogReceivedTime > LOG_INTERVAL_MS) {
                lastLogReceivedTime = now;
                Logger.info("[Voxel Client] Received " + count + " streamed LOD sections from server");
            }
        } catch (Exception e) {
            Logger.error("Failed to process streamed LOD section: " + payload.sectionKey(), e);
        } finally {
            if (section != null) {
                section.release();
            }
        }
    }

    private static void rejectInvalid(String reason) {
        int count = invalidSectionsCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastInvalidLogTime >= DROP_LOG_INTERVAL_MS) {
            lastInvalidLogTime = now;
            Logger.warn("Rejected invalid Voxel LOD payload (" + count + " so far): " + reason);
        }
    }

    public static void reset() {
        worldGeneration.incrementAndGet();
        serverStreamingSupported = false;
        lastRequestTime = 0;
        lastRequestedX = Integer.MIN_VALUE;
        lastRequestedZ = Integer.MIN_VALUE;
        receivedSectionsCount.set(0);
        droppedSectionsCount.set(0);
        invalidSectionsCount.set(0);
        lastLogReceivedTime = 0;
        lastDropLogTime = 0;
        lastInvalidLogTime = 0;
    }
}
