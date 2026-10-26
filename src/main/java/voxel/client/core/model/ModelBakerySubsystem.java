package voxel.client.core.model;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import voxel.client.core.vk.VkFramebuffer;
import voxel.common.world.Mapper;
import net.vulkanmod.vulkan.Renderer;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class ModelBakerySubsystem {
    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final Mapper mapper;
    private final AtomicInteger blockIdCount = new AtomicInteger();
    private final ConcurrentLinkedDeque<Integer> blockIdQueue = new ConcurrentLinkedDeque<>();

    private final Thread processingThread;
    private volatile boolean isRunning = true;

    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        this.factory = new ModelFactory(mapper, this.storage);
        this.processingThread = new Thread(()->{
            while (this.isRunning) {
                this.factory.processAllThings();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, "Model factory processor");
        this.processingThread.setDaemon(true);
        this.processingThread.setPriority(Thread.NORM_PRIORITY - 1);
        this.processingThread.start();
    }

    public void tick(long totalBudget) {
        long start = System.nanoTime();
        this.factory.tickAndProcessUploads();
        Integer i = this.blockIdQueue.poll();
        long gameTime = net.minecraft.client.Minecraft.getInstance().level != null
                ? net.minecraft.client.Minecraft.getInstance().level.getGameTime() : -1L;
        boolean refreshLava = gameTime >= 0 && this.factory.hasPendingLavaRefresh(gameTime);
        // Animated-sprite refresh costs a capture+upload per model per 2 ticks;
        // distant plants animate imperceptibly during normal play, so only pay
        // for it while inspecting the far field with a detached camera.
        boolean refreshAnimated = gameTime >= 0 && isCameraDetached() && this.factory.hasPendingAnimatedRefresh(gameTime);
        if (i != null || refreshLava || refreshAnimated) {
            int j = 0;
            VkFramebuffer prevVoxelFb = VkFramebuffer.current();
            var prevExtFb = VkFramebuffer.captureExternalFramebuffer();

            if (i != null) {
                do {
                    this.factory.addEntry(i);
                    j++;
                    if (j >= 3 || (totalBudget < (System.nanoTime() - start) + 50_000))
                        break;
                    i = this.blockIdQueue.poll();
                } while (i != null);
                this.blockIdCount.addAndGet(-j);
            }

            if (refreshLava) {
                // One model tile, re-rendered from the live (animated) block
                // atlas. Runs in the same capture framebuffer scope as bakes.
                this.factory.refreshLavaTexture(gameTime);
            }

            if (refreshAnimated) {
                // Animated sprites (seagrass, kelp, fire, ...): re-render each
                // registered model's atlas tile from the live block atlas.
                this.factory.refreshAnimatedTextures(gameTime);
            }

            if (prevVoxelFb != null) {
                prevVoxelFb.bindRenderTarget();
            } else {
                VkFramebuffer.rebindExternal(prevExtFb);
                Renderer.resetViewport();
            }
        }
    }

    private static boolean isCameraDetached() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.gameRenderer == null) {
            return true;
        }
        return mc.getCameraEntity() != mc.player
                || mc.gameRenderer.getMainCamera().position().distanceToSqr(mc.player.position()) > 16.0;
    }

    public void shutdown() {
        this.isRunning = false;
        try {
            this.processingThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        this.factory.free();
        this.storage.free();
    }

    private final ReentrantLock seenIdsLock = new ReentrantLock();
    private final IntOpenHashSet seenIds = new IntOpenHashSet(6000);

    public void requestBlockBake(int blockId) {
        if (blockId <= 0 || blockId >= this.mapper.getBlockStateCount()) {
            return;
        }
        this.seenIdsLock.lock();
        if (!this.seenIds.add(blockId)) {
            this.seenIdsLock.unlock();
            return;
        }
        this.seenIdsLock.unlock();
        this.blockIdQueue.add(blockId);
        this.blockIdCount.incrementAndGet();
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("MQ/IF/MC: %04d, %03d, %04d", this.blockIdCount.get(), this.factory.getInflightCount(), this.factory.getBakedCount()));
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.blockIdCount.get()==0 && this.factory.getInflightCount() == 0;
    }

    public int getProcessingCount() {
        return this.blockIdCount.get() + this.factory.getInflightCount();
    }
}
