package voxel.client.core.model;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import voxel.client.compat.lambdabettergrass.LambdaBetterGrassCompat;
import voxel.client.core.vk.Capabilities;
import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkTexture;
import voxel.client.core.model.bakery.ModelTextureBakery;
import voxel.client.core.rendering.util.RawDownloadStream;
import voxel.client.core.rendering.util.UploadStream;
import voxel.common.Logger;
import voxel.common.util.MemoryBuffer;
import voxel.common.util.Pair;
import voxel.common.world.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

import static voxel.client.core.model.ModelStore.MODEL_SIZE;

//Manages the storage and updating of model states, textures and colours

//Also has a fast long[] based metadata lookup for when the terrain mesher needs to look up the face occlusion data

//TODO: support more than 65535 states, what should actually happen is a blockstate is registered, the model data is generated, then compared
// to all other models already loaded, if it is a duplicate, create a mapping from the id to the already loaded id, this will help with meshing aswell
// as leaves and such will be able to be merged



//TODO: NOTE!!! is it worth even uploading as a 16x16 texture, since automatic lod selection... doing 8x8 textures might be perfectly ok!!!
// this _quarters_ the memory requirements for the texture atlas!!! WHICH IS HUGE saving
public class ModelFactory {
    public static final int MODEL_TEXTURE_SIZE = 16;
    public static final int LAYERS = Integer.numberOfTrailingZeros(MODEL_TEXTURE_SIZE);

    /**
     * Model ids are packed into a 16-bit field of the quad metadata and index
     * the model/atlas buffers, so the id space ends at 65,535.
     */
    public static final int MAX_MODEL_COUNT = 1 << 16;

    /**
     * Biome tint LUT capacity in ints (the colour buffer is this many RGBA
     * slots). One row per biome-dependent model, one column per biome id.
     */
    public static final int MAX_BIOME_COLOUR_SLOTS = 1 << 16;

    /** Biome ids are 9 bits wide in the packed quad format. */
    public static final int MAX_BIOME_ID = (1 << 9) - 1;

    /**
     * Publication channel for {@link #idMappings}. The bake thread writes every
     * metadata table first, then publishes the model id with release; mesher
     * threads must read it with acquire before touching metadataCache /
     * fluidStateLUT / stairStepLUT.
     */
    private static final VarHandle ID_MAPPINGS =
            MethodHandles.arrayElementVarHandle(int[].class);

    //Slanted face detection (block units): planar within 3/4 texel, sloped over at least 2.5 texels,
    // slope within the packed int8/64 range
    private static final float SLANT_MAX_RESIDUAL = 0.75f / MODEL_TEXTURE_SIZE;
    private static final float SLANT_MIN_DEPTH_RANGE = 2.5f / MODEL_TEXTURE_SIZE;
    private static final float SLANT_MAX_SLOPE = 1.95f;

    //TODO: replace the fluid BlockState with a client model id integer of the fluidState, requires looking up
    // the fluid state in the mipper
    private record ModelEntry(ColourDepthTextureData down, ColourDepthTextureData up, ColourDepthTextureData north, ColourDepthTextureData south, ColourDepthTextureData west, ColourDepthTextureData east, int fluidBlockStateId, int tintingColour, int offsetInfo) {
        public ModelEntry(ColourDepthTextureData[] textures, int fluidBlockStateId, int tintingColour, int offsetInfo) {
            this(textures[0], textures[1], textures[2], textures[3], textures[4], textures[5], fluidBlockStateId, tintingColour, offsetInfo);
        }
    }

    // MC 1.21.1: RegistryLookup.getValue() → getOrThrow() which returns Holder<T>
    // Type changed from Biome to Holder<Biome> for consistency with registry API
    private final Holder<Biome> DEFAULT_BIOME = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);

    public final ModelTextureBakery bakery;


    //Model data might also contain a constant colour if the colour resolver produces a constant colour, this saves space in the
    // section buffer reverse indexing

    //model data also contains if a face should be randomly rotated,flipped etc to get rid of moire effect
    // this would be done in the fragment shader

    //The Meta-cache contains critical information needed for meshing, colour provider bit, per-face = is empty, has alpha, is solid, full width, full height
    // alpha means that some pixels have alpha values and belong in the translucent rendering layer,
    // is empty means that the face is air/shouldent be rendered as there is nothing there
    // is solid means that every pixel is fully opaque
    // full width, height, is if the blockmodel dimentions occupy a full block, e.g. comparator, some faces do some dont and some only in a specific axis

    //AUDIT-FIXME: leaves/translucent-alpha semantics unresolved — e.g. leaves
    // are translucent but their alpha channels carry COLOUR; the metadata can't
    // express "alpha is colour not transparency".

    //TODO: ADD an occlusion mask that can be queried (16x16 pixels takes up 4 longs) this mask shows what pixels are exactly occluded at the edge of the block
    // so that full block occlusion can work nicely


    //TODO: what might work maybe, is that all the transparent pixels should be set to the average of the other pixels
    // that way the block is always "fully occluding" (if the block model doesnt cover the entire thing), maybe
    // this has some issues with quad merging
    //TODO: ACTUALLY, full out all the transparent pixels that are _within_ the bounding box of the model
    // this will mean that when quad merging and rendering, the transparent pixels of the block where there shouldent be
    // might still work???

    // this has an issue with scaffolding i believe tho, so maybe make it a probability to render??? idk
    private final long[] metadataCache;
    private final int[] fluidStateLUT;

    //Provides a map from id -> model id as multiple ids might have the same internal model id
    private final int[] idMappings;
    private final int[] stairStepLUT;
    /** Base model id -> LambdaBetterGrass connected-side sibling model id, or -1. */
    private final int[] lbgVariantLUT;
    /** CPU copy of the last uploaded 64-byte model record, used by the LBG sibling. */
    private final MemoryBuffer modelDataCache;
    private final Object2IntOpenHashMap<ModelEntry> modelTexture2id = new Object2IntOpenHashMap<>();

    //Contains the set of all block ids that are currently inflight/being baked
    // this is required due to "async" nature of gpu feedback
    private final IntOpenHashSet blockStatesInFlight = new IntOpenHashSet();
    private final ReentrantLock blockStatesInFlightLock = new ReentrantLock();

    private final List<Biome> biomes = new ArrayList<>();
    private final List<Pair<Integer, BlockState>> modelsRequiringBiomeColours = new ArrayList<>();

    private static final ObjectSet<BlockState> LOGGED_SELF_CULLING_WARNING = new ObjectOpenHashSet<>();

    private final Mapper mapper;
    private final ModelStore storage;
    private final RawDownloadStream downstream = new RawDownloadStream(8*1024*1024);//8mb downstream

    private final ConcurrentLinkedDeque<RawBakeResult> rawBakeResults = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<ResultUploader> uploadResults = new ConcurrentLinkedDeque<>();

    private Object2IntMap<BlockState> customBlockStateIdMapping;

    // Animated lava. All lava states share one still model and one flow-top
    // sibling model; their atlas tiles are re-baked from the live block atlas
    // every other game tick so far-field lava animates with the near field.
    private final ReentrantLock lavaModelLock = new ReentrantLock();
    private @Nullable ColourDepthTextureData[] canonicalLavaTextures;
    private volatile int canonicalLavaBlockId = -1;
    private volatile int canonicalLavaModelId = -1;
    /** Sibling of {@link #canonicalLavaModelId} for flowing tops: its UP tile holds the whole flowing sprite captured at rotation 0, which quads3.vert re-rotates per quad. Captured from addEntry ahead of the first lava bake; -1 until registered. */
    private volatile int canonicalLavaFlowModelId = -1;
    private volatile boolean lavaFlowBakeQueued;
    private long lastLavaRefreshGameTime = Long.MIN_VALUE;

    /** Models whose sprites are animated (seagrass, kelp, fire, ...): their
     * atlas tiles are re-baked from the live block atlas on the same cadence as
     * lava so far-field sprites move with the near field. Lava keeps its own
     * dedicated refresh (shared canonical textures + flow sibling). */
    private final ConcurrentHashMap<Integer, AnimatedEntry> animatedModels = new ConcurrentHashMap<>();
    private long lastAnimatedRefreshGameTime = Long.MIN_VALUE;

    private record AnimatedEntry(int blockId, BlockState state) {}

    //TODO: NOTE!!! is it worth even uploading as a 16x16 texture, since automatic lod selection... doing 8x8 textures might be perfectly ok!!!
    // this _quarters_ the memory requirements for the texture atlas!!! WHICH IS HUGE saving
    public ModelFactory(Mapper mapper, ModelStore storage) {
        this.mapper = mapper;
        this.storage = storage;
        this.bakery = new ModelTextureBakery(MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE);

        this.metadataCache = new long[MAX_MODEL_COUNT];
        this.fluidStateLUT = new int[MAX_MODEL_COUNT];
        this.stairStepLUT = new int[MAX_MODEL_COUNT];
        this.lbgVariantLUT = new int[MAX_MODEL_COUNT];
        this.modelDataCache = new MemoryBuffer((long) MAX_MODEL_COUNT * MODEL_SIZE);
        this.idMappings = new int[1<<20];//Max of 1 million blockstates mapping to 65k model states
        Arrays.fill(this.idMappings, -1);
        Arrays.fill(this.fluidStateLUT, -1);
        Arrays.fill(this.stairStepLUT, -1);
        Arrays.fill(this.lbgVariantLUT, -1);

        this.modelTexture2id.defaultReturnValue(-1);
        this.blockStatesInFlight.add(0);
        RawBakeResult airResult = new RawBakeResult(0, Blocks.AIR.defaultBlockState());
        MemoryUtil.memSet(airResult.rawData.address, 0, airResult.rawData.size);
        this.rawBakeResults.add(airResult);
        this.processModelResult();
    }

    private int modelOverflowCount;
    private int biomeOverflowCount;
    private int biomeRowOverflowCount;

    /** Removes the in-flight marker without requiring that this thread added it. */
    private void clearInFlight(int blockId) {
        this.blockStatesInFlightLock.lock();
        this.blockStatesInFlight.remove(blockId);
        this.blockStatesInFlightLock.unlock();
    }
    private void reportModelOverflow(Object state) {
        this.modelOverflowCount++;
        if (this.modelOverflowCount == 1 || (this.modelOverflowCount & 1023) == 0) {
            Logger.error("Model id space exhausted (" + MAX_MODEL_COUNT
                    + " ids): state " + state
                    + " is rendered as air. overflowed models: " + this.modelOverflowCount);
        }
    }

    private void reportBiomeOverflow(int biomeId, int projectedBiomes) {
        this.biomeOverflowCount++;
        if (this.biomeOverflowCount == 1 || (this.biomeOverflowCount & 255) == 0) {
            Logger.error("Biome tint LUT is full (" + MAX_BIOME_COLOUR_SLOTS
                    + " slots): dropping biome id " + biomeId
                    + " (would need " + projectedBiomes + " columns x "
                    + this.modelsRequiringBiomeColours.size() + " models)."
                    + " Biome " + biomeId + " falls back to stale/white tint. dropped: "
                    + this.biomeOverflowCount);
        }
    }

    /** True if one more biome-dependent model row fits, including room for any 9-bit biome id. */
    private boolean biomeRowFits(int models, int biomes) {
        return (long) models * biomes + MAX_BIOME_ID < MAX_BIOME_COLOUR_SLOTS;
    }

    private void reportBiomeRowOverflow(BlockState state) {
        this.biomeRowOverflowCount++;
        if (this.biomeRowOverflowCount == 1 || (this.biomeRowOverflowCount & 255) == 0) {
            Logger.error("Biome tint LUT rows exhausted (" + this.biomes.size()
                    + " biomes x " + this.modelsRequiringBiomeColours.size()
                    + " models): state " + state
                    + " falls back to a constant tint. overflowed models: "
                    + this.biomeRowOverflowCount);
        }
    }

    public void setCustomBlockStateMapping(Object2IntMap<BlockState> mapping) {
        this.customBlockStateIdMapping = mapping;
    }

    private static final class RawBakeResult {
        private final int blockId;
        private final BlockState blockState;
        private final MemoryBuffer rawData;

        public boolean isShaded;
        public boolean hasDarkenedTextures;
        /** Any baked quad used an animated sprite; registers the model for periodic texture refresh. */
        public boolean hasAnimatedSprites;
        /** >= 0: texture-only refresh of this already-uploaded model. */
        public int refreshModelId = -1;
        /** Bake under the modified "lava flow" world (southern air trench over a
         *  lava layer below): the UP face captures the flowing sprite window.
         *  With refreshModelId == -1 this registers the flow-top sibling model (queued from addEntry);
         *  with refreshModelId == canonicalLavaFlowModelId it refreshes it. */
        public boolean lavaFlowCapture;
        /** Lava refresh uploads extend side tiles to full height (flow strip); generic refreshes don't. */
        public boolean extendSidesOnRefresh;
        /** This bake is the LambdaBetterGrass connected-side sibling of {@link #blockId}'s base model. */
        public boolean lbgVariant;
        /** The LBG wrapper actually emitted quads for the synthetic ledge. */
        public boolean lbgCaptured;

        public RawBakeResult(int blockId, BlockState blockState, MemoryBuffer rawData) {
            this.blockId = blockId;
            this.blockState = blockState;
            this.rawData = rawData;
        }

        public RawBakeResult(int blockId, BlockState blockState) {
            this(blockId, blockState, new MemoryBuffer(MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*2*4*6));
        }

        public RawBakeResult cpyBuf(long ptr) {
            this.rawData.cpyFrom(ptr);
            return this;
        }
    }

    public boolean addEntry(int blockId) {
        if (blockId < 0 || blockId >= this.idMappings.length || blockId >= this.mapper.getBlockStateCount()) {
            return false;
        }
        if (getModelIdAcquire(blockId) != -1) {
            return false;
        }
        //We are (probably) going to be baking the block id
        // check that it is currently not inflight, if it is, return as its already being baked
        // else add it to the flight as it is going to be baked
        this.blockStatesInFlightLock.lock();
        try {
            // Recheck under the flight lock: the bake thread publishes the
            // mapping before it removes the in-flight entry, so this closes the
            // window where we would add a marker for an already-published id.
            if (getModelIdAcquire(blockId) != -1) {
                return false;
            }
            if (!this.blockStatesInFlight.add(blockId)) {
                //Block baking is already in-flight
                return false;
            }
        } finally {
            this.blockStatesInFlightLock.unlock();
        }

        VarHandle.loadLoadFence();

        //We need to get it twice cause of threading
        if (getModelIdAcquire(blockId) != -1) {
            // The bake finished between the two checks; drop the marker we just
            // added instead of leaving it in the in-flight set forever.
            clearInFlight(blockId);
            return false;
        }

        var blockState = this.mapper.getBlockStateFromBlockId(blockId);

        //Before we enqueue the baking of this blockstate, we must check if it has a fluid state associated with it
        // if it does, we must ensure that it is (effectivly) baked BEFORE we bake this blockstate
        boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
        if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
            //Insert into the fluid LUT
            var fluidState = blockState.getFluidState().createLegacyBlock();

            int fluidStateId = this.mapper.getIdForBlockState(fluidState);

            if (getModelIdAcquire(fluidStateId) == -1) {
                //Dont have to check for inflight as that is done recursively :p

                //This is a hack but does work :tm: due to how the download stream is setup
                // it should enforce that the fluid state is processed before our blockstate
                addEntry(fluidStateId);
            }
        }

        boolean isLava = isFluid && blockState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        if (isLava && !this.lavaFlowBakeQueued) {
            // Capture the flow-top sibling ahead of the first lava bake. The
            // download stream is FIFO, so it is registered before the still
            // model publishes its id mapping, i.e. before any section meshes lava.
            this.lavaFlowBakeQueued = true;
            this.queueLavaBake(blockId, Blocks.LAVA.defaultBlockState(), -1, true);
        }

        RawBakeResult result = new RawBakeResult(blockId, blockState);
        try {
            int allocation = this.downstream.download(MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*2*4*6, ptr -> this.rawBakeResults.add(result.cpyBuf(ptr)));
            int flags = this.bakery.renderToStream(isLava ? Blocks.LAVA.defaultBlockState() : blockState, this.downstream.getBufferId(), allocation);
            result.hasDarkenedTextures = (flags&2)!=0;
            result.isShaded = (flags&1)!=0;
            result.hasAnimatedSprites = (flags&4)!=0;

            // LambdaBetterGrass changes the side quads only in the Fabric
            // emitQuads path. Bake a second, connected-side sibling of the
            // same state; the mesher selects it per side face.
            if (!isLava && LambdaBetterGrassCompat.isLBGState(blockState)) {
                RawBakeResult variant = new RawBakeResult(blockId, blockState);
                variant.lbgVariant = true;
                int variantAllocation = this.downstream.download(
                        MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*2*4*6,
                        ptr -> this.rawBakeResults.add(variant.cpyBuf(ptr)));
                int variantFlags = this.bakery.renderToStream(
                        blockState, this.downstream.getBufferId(), variantAllocation, false, true);
                variant.lbgCaptured = (variantFlags & 8) != 0;
                variant.hasDarkenedTextures = (variantFlags & 2) != 0;
                variant.isShaded = (variantFlags & 1) != 0;
                variant.hasAnimatedSprites = (variantFlags & 4) != 0;
            }
        } catch (RuntimeException | Error e) {
            // The bake never reaches processTextureBakeResult, so nobody else
            // would remove this marker; without this the block id stays
            // "in flight" forever and never gets re-requested.
            clearInFlight(blockId);
            throw e;
        }
        return true;
    }

    private boolean processModelResult() {
        var result = this.rawBakeResults.poll();
        if (result == null) return false;
        ColourDepthTextureData[] textureData = new ColourDepthTextureData[6];
        {//Create texture data
            long ptr = result.rawData.address;
            final int FACE_SIZE = MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
            for (int face = 0; face < 6; face++) {
                long faceDataPtr = ptr + (FACE_SIZE * 4) * face * 2;
                int[] colour = new int[FACE_SIZE];
                int[] depth = new int[FACE_SIZE];

                //Copy out colour
                for (int i = 0; i < FACE_SIZE; i++) {
                    //De-interpolate results
                    colour[i] = MemoryUtil.memGetInt(faceDataPtr + (i * 4 * 2));
                    depth[i] = MemoryUtil.memGetInt(faceDataPtr + (i * 4 * 2) + 4);
                }
                textureData[face] = new ColourDepthTextureData(colour, depth, MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE);
            }
        }
        result.rawData.free();
        if (result.lbgVariant) {
            if (result.lbgCaptured) {
                this.processLBGVariantBakeResult(result, textureData);
            }
            return !this.rawBakeResults.isEmpty();
        }
        if (result.refreshModelId >= 0) {
            // Animation refresh: replace the existing model's atlas tile only.
            this.uploadResults.add(createTextureRefreshUpload(result.refreshModelId, textureData, result.hasDarkenedTextures, result.extendSidesOnRefresh));
        } else {
            var bakeResult = this.processTextureBakeResult(result.blockId, result.blockState, textureData, result.isShaded, result.hasDarkenedTextures, result.lavaFlowCapture, result.hasAnimatedSprites);
            if (bakeResult!=null) {
                this.uploadResults.add(bakeResult);
            }
        }
        return !this.rawBakeResults.isEmpty();
    }

    /** Builds a texture-only upload for a model refresh bake (animated sprites). */
    private static ModelBakeResultUpload createTextureRefreshUpload(int modelId, ColourDepthTextureData[] textureData, boolean darkenedTinting, boolean extendSides) {
        if (extendSides) {
            // Lava: side tiles hold the flowing strip and must fill the tile.
            for (int face = 2; face < 6; face++) {
                textureData[face] = TextureUtils.extendToFullHeight(textureData[face], TextureUtils.WRITE_CHECK_STENCIL);
            }
        }
        ModelBakeResultUpload upload = new ModelBakeResultUpload();
        upload.modelId = modelId;
        upload.textureOnly = true;
        MipGen.putTextures(darkenedTinting, textureData, upload.texture);
        return upload;
    }

    private final ConcurrentLinkedDeque<Mapper.BiomeEntry> biomeQueue = new ConcurrentLinkedDeque<>();
    public void addBiome(Mapper.BiomeEntry biome) {
        this.biomeQueue.add(biome);
    }

    public void processAllThings() {
        var biomeEntry = this.biomeQueue.poll();
        while (biomeEntry != null) {
            var biomeRegistry = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME);
            var biomeKey = ResourceKey.create(Registries.BIOME, Identifier.parse(biomeEntry.biome));
            var res = this.addBiome0(biomeEntry.id, biomeRegistry.get(biomeKey).map(Holder::value).orElse(DEFAULT_BIOME.value()));
            if (res != null) {
                this.uploadResults.add(res);
            }
            biomeEntry = this.biomeQueue.poll();
        }

        while (this.processModelResult());
    }

    /** @return true when the shared lava model should be re-baked for this game tick. */
    public boolean hasPendingLavaRefresh(long gameTime) {
        return gameTime != this.lastLavaRefreshGameTime
                && (gameTime & 1L) == 0L
                && this.canonicalLavaModelId >= 0;
    }

    /** @return true when any animated-sprite model needs its tile refreshed this tick. */
    public boolean hasPendingAnimatedRefresh(long gameTime) {
        return gameTime != this.lastAnimatedRefreshGameTime
                && (gameTime & 1L) == 0L
                && !this.animatedModels.isEmpty();
    }

    /**
     * Re-bakes every animated-sprite model's atlas tile from the live block
     * atlas (seagrass sway, kelp, fire, ...). Same even-tick cadence as the
     * lava refresh. Must run on the render thread.
     */
    public void refreshAnimatedTextures(long gameTime) {
        if (gameTime == this.lastAnimatedRefreshGameTime || (gameTime & 1L) != 0L) {
            return;
        }
        this.lastAnimatedRefreshGameTime = gameTime;
        for (var e : this.animatedModels.entrySet()) {
            AnimatedEntry a = e.getValue();
            this.queueRefreshBake(a.blockId(), a.state(), e.getKey(), false, false);
        }
    }

    /**
     * Re-bakes the shared lava model's atlas tile from the live block atlas.
     * Vanilla's lava_still sprite advances every 2 game ticks, so sampling on
     * even ticks shows the same frames as the near-field animation.
     *
     * <p>Must run on the render thread: the model bakery renders through the
     * Vulkan capture framebuffer.</p>
     */
    public void refreshLavaTexture(long gameTime) {
        if (gameTime == this.lastLavaRefreshGameTime || (gameTime & 1L) != 0L) {
            return;
        }
        this.lastLavaRefreshGameTime = gameTime;

        int blockId;
        int modelId;
        this.lavaModelLock.lock();
        try {
            blockId = this.canonicalLavaBlockId;
            modelId = this.canonicalLavaModelId;
            if (blockId < 0 || modelId < 0 || getModelIdAcquire(blockId) != modelId) {
                return;
            }
        } finally {
            this.lavaModelLock.unlock();
        }

        BlockState state = Blocks.LAVA.defaultBlockState();
        this.queueLavaBake(blockId, state, modelId, false);

        // Keep the flow-top sibling (registered from addEntry) on the same frame.
        int flowId = this.canonicalLavaFlowModelId;
        if (flowId >= 0) {
            this.queueLavaBake(blockId, state, flowId, true);
        }
    }

    private void queueLavaBake(int blockId, BlockState state, int refreshModelId, boolean lavaFlowCapture) {
        this.queueRefreshBake(blockId, Blocks.LAVA.defaultBlockState(), refreshModelId, lavaFlowCapture, true);
    }

    private void queueRefreshBake(int blockId, BlockState state, int refreshModelId, boolean lavaFlowCapture, boolean extendSides) {
        RawBakeResult result = new RawBakeResult(blockId, state);
        result.refreshModelId = refreshModelId;
        result.lavaFlowCapture = lavaFlowCapture;
        result.extendSidesOnRefresh = extendSides;
        int allocation = this.downstream.download(MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*2*4*6,
                ptr -> this.rawBakeResults.add(result.cpyBuf(ptr)));
        int flags = this.bakery.renderToStream(state, this.downstream.getBufferId(), allocation, lavaFlowCapture);
        result.hasDarkenedTextures = (flags&2)!=0;
        result.isShaded = (flags&1)!=0;
    }

    /** Model id of the flow-top lava sibling, or -1 until it is baked. */
    public int lavaFlowModelId() {
        return this.canonicalLavaFlowModelId;
    }

    public void tickAndProcessUploads() {
        this.downstream.tick();

        var upload = this.uploadResults.poll();
        if (upload==null) return;

        do {
            upload.upload(this.storage);
            upload.free();
            upload = this.uploadResults.poll();
        } while (upload != null);
        this.storage.textures.image().image.readOnlyLayout();
        UploadStream.INSTANCE.commit();
    }

    private interface ResultUploader {
        void upload(ModelStore store);
        void free();
    }

    private static final class ModelBakeResultUpload implements ResultUploader {
        private final MemoryBuffer model = new MemoryBuffer(MODEL_SIZE).zero();
        private final MemoryBuffer texture = new MemoryBuffer((2L*3*computeSizeWithMips(MODEL_TEXTURE_SIZE))*4);

        public int modelId = -1;
        /** Texture refresh of an existing model: do not overwrite its model data. */
        public boolean textureOnly;

        public int biomeUploadIndex = -1;
        public @Nullable MemoryBuffer biomeUpload;
        public @Nullable ModelBakeResultUpload stepUpload;

        public void upload(ModelStore store) {//Uploads and resets for reuse
            this.upload(store.modelBuffer, store.modelColourBuffer, store.textures);
        }

        public void upload(VkBuffer modelBuffer, VkBuffer colourBuffer, VkTexture atlas) {//Uploads and resets for reuse
            if (!this.textureOnly) {
                this.model.cpyTo(UploadStream.INSTANCE.upload(modelBuffer, (long) this.modelId * MODEL_SIZE, MODEL_SIZE));
            }
            if (this.biomeUploadIndex != -1) {
                this.biomeUpload.cpyTo(UploadStream.INSTANCE.upload(colourBuffer, this.biomeUploadIndex * 4L, this.biomeUpload.size));
                this.biomeUploadIndex = -1;
                this.biomeUpload.free();
                this.biomeUpload = null;
            }

            int X = (this.modelId&0xFF) * MODEL_TEXTURE_SIZE*3;
            int Y = ((this.modelId>>8)&0xFF) * MODEL_TEXTURE_SIZE*2;

            long cAddr = this.texture.address;
            for (int lvl = 0; lvl < LAYERS; lvl++) {
                atlas.image().upload(lvl, X >> lvl, Y >> lvl, (MODEL_TEXTURE_SIZE*3) >> lvl, (MODEL_TEXTURE_SIZE*2) >> lvl, cAddr);
                cAddr += (MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*3*2*4)>>(lvl<<1);
            }

            this.modelId = -1;
            if (this.stepUpload != null) {
                this.stepUpload.upload(modelBuffer, colourBuffer, atlas);
            }
        }

        public void free() {
            this.model.free();
            this.texture.free();
            if (this.biomeUpload != null) {
                this.biomeUpload.free();
            }
            if (this.stepUpload != null) {
                this.stepUpload.free();
                this.stepUpload = null;
            }
        }
    }

    /**
     * @param lavaFlowCapture the bake re-ran the canonical lava state under the
     *                        modified "flow" world: it registers the flow-top
     *                        sibling model and must not claim the blockId
     *                        mapping or the in-flight slot.
     * @param hasAnimatedSprites any baked quad used an animated sprite; the
     *                        model is registered for periodic texture refresh.
     */
    private ModelBakeResultUpload processTextureBakeResult(int blockId, BlockState blockState, ColourDepthTextureData[] textureData, boolean isShaded, boolean darkenedTinting, boolean lavaFlowCapture, boolean hasAnimatedSprites) {
        if (getModelIdAcquire(blockId) != -1 && !lavaFlowCapture) {
            //This should be impossible to reach as it means that multiple bakes for the same blockId happened and where inflight at the same time!
            throw new IllegalStateException("Block id already added: " + blockId + " for state: " + blockState);
        }

        if (!lavaFlowCapture) {
            this.blockStatesInFlightLock.lock();
            if (!this.blockStatesInFlight.contains(blockId)) {
                this.blockStatesInFlightLock.unlock();
                throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
            }
            this.blockStatesInFlightLock.unlock();
        }

        //TODO: add thing for `blockState.hasEmissiveLighting()` and `blockState.getLuminance()`

        boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
        int modelId = -1;


        int clientFluidStateId = -1;

        if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
            //Insert into the fluid LUT
            var fluidState = blockState.getFluidState().createLegacyBlock();

            int fluidStateId = this.mapper.getIdForBlockState(fluidState);

            clientFluidStateId = getModelIdAcquire(fluidStateId);
            if (clientFluidStateId == -1) {
                throw new IllegalStateException("Block has a fluid state but fluid state is not already baked!!!");
            }
        }

        var colourProvider = getColourProvider(blockState.getBlock());

        boolean isBiomeColourDependent = false;
        if (colourProvider != null) {
            isBiomeColourDependent = isBiomeDependentColour(colourProvider, blockState);
        }

        int offsetInfo = computeOffsetInfo(blockState);

        ChunkSectionLayer blockRenderLayer = null;
        if (blockState.getBlock() instanceof LiquidBlock) {
            blockRenderLayer = ItemBlockRenderTypes.getRenderLayer(blockState.getFluidState());
        } else {
            blockRenderLayer = ItemBlockRenderTypes.getChunkRenderType(blockState);
        }

        int checkMode = blockRenderLayer==ChunkSectionLayer.SOLID?TextureUtils.WRITE_CHECK_STENCIL:TextureUtils.WRITE_CHECK_ALPHA;

        boolean isLava = isFluid && blockState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        if (isLava) {
            for (int face = 2; face < 6; face++) {
                textureData[face] = TextureUtils.extendToFullHeight(textureData[face], checkMode);
            }
        }

        ModelEntry entry;
        if (isLava) {
            this.lavaModelLock.lock();
        }
        try {
            if (isLava && !lavaFlowCapture) {
                if (this.canonicalLavaTextures == null) {
                    this.canonicalLavaTextures = textureData;
                } else {
                    textureData = this.canonicalLavaTextures;
                }
            }

            {//Deduplicate same entries
                // MC 1.21.1: DEFAULT_BIOME is now Holder<Biome>, need .value() to get Biome
                entry = new ModelEntry(copyTextureData(textureData), clientFluidStateId, isBiomeColourDependent||colourProvider==null?-1:captureColourConstant(colourProvider, blockState, DEFAULT_BIOME.value())|0xFF000000, offsetInfo);
                int possibleDuplicate = this.modelTexture2id.getInt(entry);
                if (possibleDuplicate != -1) {//Duplicate found
                    if (lavaFlowCapture) {
                        Logger.warn("Lava flow-top capture duplicates model " + possibleDuplicate + ", flowing lava tops disabled");
                        return null;
                    }
                    publishModelId(blockId, possibleDuplicate);
                    modelId = possibleDuplicate;
                    if (isLava && this.canonicalLavaModelId == -1) {
                        this.canonicalLavaBlockId = blockId;
                        this.canonicalLavaModelId = possibleDuplicate;
                    }
                    if (hasAnimatedSprites && !isLava) {
                        this.animatedModels.putIfAbsent(possibleDuplicate, new AnimatedEntry(blockId, blockState));
                    }
                    //Remove from flight
                    clearInFlight(blockId);
                    return null;
                } else {//Not a duplicate so create a new entry
                    if (this.modelTexture2id.size() >= MAX_MODEL_COUNT) {
                        this.reportModelOverflow(blockState);
                        if (!lavaFlowCapture) {
                            // Publish an air mapping so the mesher stops retrying this state.
                            publishModelId(blockId, 0);
                            clearInFlight(blockId);
                        }
                        return null;
                    }
                    modelId = this.modelTexture2id.size();
                    //NOTE: we set the mapping at the very end so that race conditions with this and getMetadata dont occur
                    //this.idMappings[blockId] = modelId;
                    this.modelTexture2id.put(entry, modelId);
                    if (isLava && !lavaFlowCapture && this.canonicalLavaModelId == -1) {
                        this.canonicalLavaBlockId = blockId;
                        this.canonicalLavaModelId = modelId;
                    }
                    if (hasAnimatedSprites && !isLava && !lavaFlowCapture) {
                        this.animatedModels.putIfAbsent(modelId, new AnimatedEntry(blockId, blockState));
                    }
                }
            }
        } finally {
            if (isLava) {
                this.lavaModelLock.unlock();
            }
        }

        if (isFluid) {
            this.fluidStateLUT[modelId] = modelId;
        } else if (clientFluidStateId != -1) {
            this.fluidStateLUT[modelId] = clientFluidStateId;
        }

        ModelBakeResultUpload uploadResult = new ModelBakeResultUpload();
        uploadResult.modelId = modelId;
        long uploadPtr = uploadResult.model.address;

        //TODO: implement;
        // TODO: if it has a constant colour instead... idk why (apparently for things like spruce leaves)?? but premultiply the texture data by the constant colour

        //If it contains fluid but isnt a fluid
        if ((!isFluid) && (!blockState.getFluidState().isEmpty()) && clientFluidStateId != -1) {

            //Or it with the fluid state biome dependency
            isBiomeColourDependent |= ModelQueries.isBiomeColoured(this.getModelMetadataFromClientId(clientFluidStateId));
        }



        //TODO: special case stuff like vines and glow lichen, where it can be represented by a single double sided quad
        // since that would help alot with perf of lots of vines, can be done by having one of the faces just not exist and the other be in no occlusion mode

        boolean isStair = blockState.getBlock() instanceof StairBlock;
        ColourDepthTextureData[] stepTextureData = isStair ? extractStairStepTextures(textureData) : null;
        float[] stepDepths = stepTextureData != null ? computeModelDepth(stepTextureData, checkMode, TextureUtils.DEPTH_MODE_MIN) : null;

        var avgDepths = computeModelDepth(textureData, checkMode, TextureUtils.DEPTH_MODE_AVG);
        var minDepths = computeModelDepth(textureData, checkMode, TextureUtils.DEPTH_MODE_MIN);
        float[] depths = minDepths;

        if (isLava) {
            // Base quad is at y = cellY + 1; the vertex shader displaces each
            // corner by its signed 1/64-block offset in lightAO.
            minDepths[1] = 0.0f;
            avgDepths[1] = 0.0f;
        }

        boolean isCrossPlant = avgDepths[0] < -0.1f && avgDepths[1] < -0.1f
                && avgDepths[2] >= 0.0f && avgDepths[3] >= 0.0f
                && avgDepths[4] >= 0.0f && avgDepths[5] >= 0.0f;
        if (isCrossPlant) {
            if (blockState.getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
                depths = avgDepths;
            } else {
                // Cross models (grass, flowers, sugar cane, saplings, ferns): emit 2 double-sided
                // planes intersecting at the center (0.5, 0.5) instead of 4 box walls at 0.25/0.75.
                depths = new float[]{-1.0f, -1.0f, 0.5f, -1.0f, 0.5f, -1.0f};
            }
        } else if (!isStair
                && minDepths[2] >= 0.0f && minDepths[2] < 0.05f
                && minDepths[3] >= 0.0f && minDepths[3] < 0.05f
                && minDepths[4] >= 0.0f && minDepths[4] < 0.05f
                && minDepths[5] >= 0.0f && minDepths[5] < 0.05f
                && avgDepths[1] > 0.15f && minDepths[1] >= 0.0f && minDepths[1] < 0.05f) {
            // Hollow-top containers (Composter, Cauldron, Hopper): keep top face flush with the
            // 4 outer walls and shade the recessed interior cavity so it matches near-field depth.
            shadeHollowContainerTop(textureData[1]);
            depths[1] = 0.0f;
        }

        //TODO: THIS, note this can be tested for in 2 ways, re render the model with quad culling disabled and see if the result
        // is the same, (if yes then needs double sided quads)
        // another way to test it is if e.g. up and down havent got anything rendered but the sides do (e.g. all plants etc)
        boolean needsDoubleSidedQuads = (depths[0] < -0.1 && depths[1] < -0.1) || (depths[2] < -0.1 && depths[3] < -0.1) || (depths[4] < -0.1 && depths[5] < -0.1);


        boolean isRodOrChain = blockState.getBlock() instanceof net.minecraft.world.level.block.RodBlock
                || blockState.getBlock() instanceof net.minecraft.world.level.block.ChainBlock;
        boolean isLeaves = blockState.getBlock() instanceof LeavesBlock;
        if (isLeaves) {
            // Cutout leaves rely on seeing the inside of the opposite face
            // through their alpha holes. Keep the cutout silhouette; do NOT
            // fill the holes here, or distant canopies become solid green
            // cubes. Mip-filtered cutout alpha (quads.frag) fades the holes
            // smoothly at distance instead.
            needsDoubleSidedQuads = true;
        }
        boolean cullsSame = isStair || isRodOrChain;

        if (!cullsSame) {
            //TODO: Could also move this into the RenderDataFactory and do it on the actual blockstates instead of a guestimation
            boolean allTrue = true;
            boolean allFalse = true;
            //Guestimation test for if the block culls itself
            for (var dir : Direction.values()) {
                if (blockState.skipRendering(blockState, dir)) {
                    allFalse = false;
                } else {
                    allTrue = false;
                }
            }

            if (allFalse == allTrue) {//If only some sides where self culled then abort
                cullsSame = false;
                //if (LOGGED_SELF_CULLING_WARNING.add(blockState))
                //    Logger.info("Warning! blockstate: " + blockState + " only culled against its self some of the time");
            }

            if (allTrue) {
                cullsSame = true;
            }
        }
        // Leaves are cutout foliage: vanilla's fancy path renders the faces
        // between adjacent leaf blocks. Culling them turned LOD canopies into
        // hollow shells (all interior leaf faces gone) and removed the internal
        // shading that comes from those faces.
        cullsSame &= !isLeaves;


        // Slanted faces (wall torches, ascending rails, lectern tops, ...): a flat quad at the
        // nearest depth puts e.g. the wall torch's head-on stick in the wrong place, so it shows
        // next to the correct side-view stick. When a face's baked depth is an exact plane with a
        // clear slope, render that plane instead.
        boolean[] slanted = new boolean[6];
        float[] slantDepth = new float[6];//Plane depth at the face's (min u, min v) corner
        int[] packedSlopes = new int[3];
        boolean hasSlantedFace = false;
        if (!isFluid && !isStair && !isCrossPlant) {
            for (int face = 0; face < 6; face++) {
                if (depths[face] < -0.1f) {
                    continue;
                }
                float[] fit = TextureUtils.fitDepthPlane(textureData[face], checkMode);
                if (fit == null || fit[5] > SLANT_MAX_RESIDUAL || (fit[7] - fit[6]) < SLANT_MIN_DEPTH_RANGE
                        || Math.abs(fit[3]) > SLANT_MAX_SLOPE || Math.abs(fit[4]) > SLANT_MAX_SLOPE) {
                    continue;
                }
                var bounds = TextureUtils.computeBounds(textureData[face], checkMode);
                float u0 = bounds[0] / (float) MODEL_TEXTURE_SIZE;
                float v0 = bounds[2] / (float) MODEL_TEXTURE_SIZE;
                slanted[face] = true;
                slantDepth[face] = fit[2] + fit[3] * (u0 - fit[0]) + fit[4] * (v0 - fit[1]);
                int su = Math.clamp(Math.round(fit[3] * 64), -127, 127);
                int sv = Math.clamp(Math.round(fit[4] * 64), -127, 127);
                packedSlopes[face >> 1] |= ((su & 0xFF) | ((sv & 0xFF) << 8)) << ((face & 1) * 16);
                hasSlantedFace = true;
            }
        }

        //Each face gets 1 byte, with the top 2 bytes being for whatever
        long metadata = 0;
        metadata |= isBiomeColourDependent?1:0;
        metadata |= blockRenderLayer == ChunkSectionLayer.TRANSLUCENT?2:0;
        metadata |= needsDoubleSidedQuads?4:0;
        metadata |= ((!isFluid) && !blockState.getFluidState().isEmpty())?8:0;//Has a fluid state accosiacted with it and is not itself a fluid
        metadata |= isFluid?16:0;//Is a fluid

        metadata |= cullsSame?32:0;
        //Bit 64 (fully opaque) is set after the face loop
        metadata |= (offsetInfo != 0 || hasSlantedFace)?128:0;//Per-block geometry (model offset or slanted faces), never merge

        boolean fullyOpaque = true;

        //TODO: FIXME faces that have the same "alignment depth" e.g. (sizes[0]+sizes[1])~=1 can be merged into a double faced single quad

        //TODO: add a bunch of control config options for overriding/setting options of metadata for each face of each type
        for (int face = 5; face != -1; face--) {//In reverse order to make indexing into the metadata long easier
            long faceUploadPtr = uploadPtr + 4L * face;//Each face gets 4 bytes worth of data
            metadata <<= 8;
            float offset = depths[face];
            if (offset < -0.1) {//Face is empty, so ignore
                metadata |= 0xFF;//Mark the face as non-existent
                //Set to -1 as safepoint
                MemoryUtil.memPutInt(faceUploadPtr, -1);

                fullyOpaque = false;
                continue;
            }
            var faceSize = TextureUtils.computeBounds(textureData[face], checkMode);
            int writeCount = TextureUtils.getWrittenPixelCount(textureData[face], checkMode);

            boolean faceCoversFullBlock = faceSize[0] == 0 && faceSize[2] == 0 &&
                    faceSize[1] == (MODEL_TEXTURE_SIZE-1) && faceSize[3] == (MODEL_TEXTURE_SIZE-1);

            //TODO: use faceSize and the depths to compute if mesh can be correctly rendered

            metadata |= faceCoversFullBlock?2:0;

            //TODO: add alot of config options for the following
            boolean occludesFace = faceCoversFullBlock;
            occludesFace &= blockRenderLayer != ChunkSectionLayer.TRANSLUCENT;//If its translucent, it doesnt occlude

            // Only flush faces (offset < 0.02, i.e. < 1/32 block) can occlude adjacent full blocks;
            // prevents 15/16 blocks like dirt_path / farmland (offset = 0.0625) from culling adjacent grass_block sides.
            occludesFace &= offset < 0.02f;
            //A slanted face leaves the block boundary, it neither occludes nor gets occluded
            occludesFace &= !slanted[face];

            if (occludesFace) {
                occludesFace &= ((float)writeCount)/(MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE) > 0.9;// only occlude if the face covers more than 90% of the face
            }
            // Leaves never occlude, not even against themselves: vanilla's fancy
            // leaf rendering keeps the faces between adjacent leaf blocks, and
            // those interior faces are what give canopies their internal shading.
            occludesFace &= !isLeaves;
            // Fluids never occlude adjacent solid faces in vanilla (LiquidBlock
            // has an empty occlusion shape), so bank and ceiling faces stay intact.
            occludesFace &= !isFluid;
            // Vanilla never culls a neighbour's face against a block without
            // occlusion (noOcclusion/noCollission: bush, wildflowers, leaf
            // litter, glass, ...). Their baked faces can still cover >90% of the
            // tile, which used to delete the grass top / dirt step behind them
            // and leave black holes in the far field.
            occludesFace &= blockState.canOcclude();
            metadata |= occludesFace?1:0;
            fullyOpaque &= occludesFace;

            // 15/16 tall solid blocks (Farmland, Dirt Path) cover y in [0..14] (>= 14/16 fluid height),
            // so their vertical side faces fully occlude adjacent fluid side faces without culling 16/16 opaque blocks.
            boolean occludesFluid = occludesFace || (face >= 2
                    && !slanted[face]
                    && blockRenderLayer == ChunkSectionLayer.SOLID
                    && offset < 0.02f
                    && faceSize[0] == 0
                    && faceSize[1] == (MODEL_TEXTURE_SIZE - 1)
                    && faceSize[2] == 0
                    && faceSize[3] >= (MODEL_TEXTURE_SIZE - 2)
                    && writeCount >= MODEL_TEXTURE_SIZE * (MODEL_TEXTURE_SIZE - 1));
            metadata |= occludesFluid ? 0b10000 : 0;



            boolean canBeOccluded = !isLeaves;
            //TODO: make this an option on how far/close
            canBeOccluded &= offset < 0.3;//If the face is rendered far away from the other face, then it cant be occluded
            if (stepDepths != null && stepDepths[face] >= 0.0f) {
                canBeOccluded = false;
            }
            canBeOccluded &= !slanted[face];

            metadata |= canBeOccluded?4:0;

            //Face uses its own lighting if its not flat against the adjacent block & isnt traslucent
            metadata |= (offset > 0.01 || slanted[face] || blockRenderLayer == ChunkSectionLayer.TRANSLUCENT)?0b1000:0;



            if (MODEL_TEXTURE_SIZE-1 != 15) {
                //Scale face size from 0->this.modelTextureSize-1 to 0->15
                for (int i = 0; i < 4; i++) {
                    faceSize[i] = Math.round((((float) faceSize[i]) / (MODEL_TEXTURE_SIZE - 1)) * 15);
                }
            }

            int faceModelData = 0;
            faceModelData |= faceSize[0] | (faceSize[1]<<4) | (faceSize[2]<<8) | (faceSize[3]<<12);
            //Change the scale from 0->1 (ends inclusive)
            // this is cursed also warning stuff at 63 (i.e half a pixel from the end will be clamped to the end)
            int enc = Math.round(offset*64);
            enc = Math.min(enc, 62);
            if (slanted[face]) {
                //Depth of the slanted plane at the quad's start corner, 63 decodes as 1.0
                enc = Math.clamp(Math.round(slantDepth[face]*64), 0, 63);
            }
            faceModelData |= enc<<16;
            //Still have 11 bits free

            //Stuff like fences are solid, however they have extra side piece that mean it needs to have discard on
            int area = (faceSize[1]-faceSize[0]+1) * (faceSize[3]-faceSize[2]+1);
            boolean needsAlphaDiscard = ((float)writeCount)/area<0.9;//If the amount of area covered by written pixels is less than a threashold, disable discard as its not needed

            needsAlphaDiscard |= blockRenderLayer != ChunkSectionLayer.SOLID;
            needsAlphaDiscard &= blockRenderLayer != ChunkSectionLayer.TRANSLUCENT;//Translucent doesnt have alpha discard
            faceModelData |= needsAlphaDiscard?1<<22:0;

            faceModelData |= ((!faceCoversFullBlock)&&blockRenderLayer != ChunkSectionLayer.TRANSLUCENT)?1<<23:0;//Alpha discard override, translucency doesnt have alpha discard

            //Bits 24,25 are tint metadata
            if (colourProvider!=null) {//We have a tint
                int tintState = TextureUtils.computeFaceTint(textureData[face], checkMode);
                if (tintState == 2) {//Partial tint
                    faceModelData |= 1<<24;
                } else if (tintState == 3) {//Full tint
                    faceModelData |= 2<<24;
                }
            }

            MemoryUtil.memPutInt(faceUploadPtr, faceModelData);
        }

        fullyOpaque &= !isFluid;
        metadata |= fullyOpaque?(1L<<(48+6)):0;
        metadata |= isLeaves?(1L<<56):0;
        metadata |= isLava?(1L<<57):0;

        boolean canBeCorrectlyRendered = true;//This represents if a model can be correctly (perfectly) represented
        // i.e. no gaps

        this.metadataCache[modelId] = metadata;

        uploadPtr += 4*6;
        //Have 40 bytes free for remaining model data
        // todo: put in like the render layer type ig? along with colour resolver info
        int modelFlags = 0;
        boolean biomeCapacityHit = isBiomeColourDependent && !this.biomes.isEmpty()
                && !this.biomeRowFits(this.modelsRequiringBiomeColours.size() + 1, this.biomes.size());
        boolean useBiomeLut = isBiomeColourDependent && !this.biomes.isEmpty() && !biomeCapacityHit;
        modelFlags |= colourProvider != null?1:0;
        modelFlags |= useBiomeLut?2:0;//Basicly whether to use the next int as a colour or as a base index/id into a colour buffer for biome dependent colours
        modelFlags |= blockRenderLayer == ChunkSectionLayer.TRANSLUCENT?4:0;//Is translucent


        //TODO: THIS
        modelFlags |= isShaded?8:0;//model has AO and shade
        modelFlags |= needsDoubleSidedQuads?16:0;
        modelFlags |= isLava?32:0;//Emissive (Beryl lightEmission = 5.0)
        modelFlags |= isLava?64:0;//Per-corner lava Y offsets in lightAO
        modelFlags |= (isLava && lavaFlowCapture)?128:0;//Flow-top sibling model

        //modelFlags |= blockRenderLayer == RenderLayer.getSolid()?0:1;// should discard alpha
        MemoryUtil.memPutInt(uploadPtr, modelFlags); uploadPtr += 4;


        int resolvedBiomeIndex = -1;
        //Temporary override to always be non biome specific
        if (colourProvider == null) {
            MemoryUtil.memPutInt(uploadPtr, -1);//Set the default to nothing so that its faster on the gpu
        } else if (!useBiomeLut) {
            if (isBiomeColourDependent && biomeCapacityHit) {
                // No row in the colour LUT (it is full): use a constant white
                // tint instead of a dangling row index.
                this.reportBiomeRowOverflow(blockState);
            }
            MemoryUtil.memPutInt(uploadPtr, isBiomeColourDependent ? -1 : entry.tintingColour);
        } else {
            //Populate the list of biomes for the model state
            int biomeIndex = this.modelsRequiringBiomeColours.size() * this.biomes.size();
            resolvedBiomeIndex = biomeIndex;
            MemoryUtil.memPutInt(uploadPtr, biomeIndex);
            this.modelsRequiringBiomeColours.add(new Pair<>(modelId, blockState));

            uploadResult.biomeUploadIndex = biomeIndex;
            long clrUploadPtr = (uploadResult.biomeUpload = new MemoryBuffer(4L * this.biomes.size())).address;
            for (var biome : this.biomes) {
                int col = biome != null ? (captureColourConstant(colourProvider, blockState, biome) | 0xFF000000) : 0xFFFFFFFF;
                MemoryUtil.memPutInt(clrUploadPtr, col);
                clrUploadPtr += 4;
            }
        }
        uploadPtr += 4;

        //have 32 bytes of free space after here

        //install the custom mapping id if it exists
        int resolvedCustomId = 0;
        if (this.customBlockStateIdMapping != null && this.customBlockStateIdMapping.containsKey(blockState)) {
            resolvedCustomId = this.customBlockStateIdMapping.getInt(blockState);
        }
        MemoryUtil.memPutInt(uploadPtr, resolvedCustomId); uploadPtr += 4;
        MemoryUtil.memPutInt(uploadPtr, offsetInfo); uploadPtr += 4;
        for (int packedSlope : packedSlopes) {
            MemoryUtil.memPutInt(uploadPtr, packedSlope); uploadPtr += 4;
        }

        // Keep a CPU copy so a LambdaBetterGrass sibling can share the same
        // geometry record and only replace its atlas tile.
        MemoryUtil.memCopy(
                uploadResult.model.address,
                this.modelDataCache.address + (long) modelId * MODEL_SIZE,
                MODEL_SIZE);

        //Note: if the layer isSolid then need to fill all the points in the texture where alpha == 0 with the average colour
        // of the surrounding blocks but only within the computed face size bounds

        //TODO callback to inject extra data into the model data


        MipGen.putTextures(darkenedTinting, textureData, uploadResult.texture);

        if (stepTextureData != null && stepDepths != null) {
            uploadResult.stepUpload = this.createStairStepUpload(
                    modelId, stepTextureData, stepDepths, checkMode, blockRenderLayer,
                    colourProvider, useBiomeLut, entry.tintingColour,
                    resolvedBiomeIndex, resolvedCustomId, isShaded, darkenedTinting);
        }

        //glGenerateTextureMipmap(this.textures.id);

        //Set the mapping at the very end
        if (lavaFlowCapture) {
            this.canonicalLavaFlowModelId = modelId;
        } else {
            publishModelId(blockId, modelId);

            clearInFlight(blockId);
        }

        return uploadResult;
    }

    /**
     * Registers the connected-side sibling produced by
     * {@link LambdaBetterGrassCompat}. The geometry is the same as the base
     * model, so its 64-byte model record is copied and only the four side tint
     * bits are refreshed from the LBG-generated textures.
     */
    private void processLBGVariantBakeResult(RawBakeResult result, ColourDepthTextureData[] textureData) {
        int baseModelId = getModelIdAcquire(result.blockId);
        if (baseModelId <= 0) {
            // The base model was dropped (overflow, or processed out of order).
            return;
        }
        if (this.lbgVariantLUT[baseModelId] != -1) {
            return;
        }

        // The LBG sibling must not replace a fully visible grass side with an
        // empty/transparent atlas view. This happens when LBG's generated
        // connect sprite is absent or cannot be resolved in the pack's atlas;
        // the result is the "missing block side" seen on terraced grass. Drop
        // the sibling and keep the vanilla base model instead.
        ChunkSectionLayer layer = ItemBlockRenderTypes.getChunkRenderType(result.blockState);
        int checkMode = layer == ChunkSectionLayer.SOLID ? TextureUtils.WRITE_CHECK_STENCIL : TextureUtils.WRITE_CHECK_ALPHA;
        for (int face = 2; face < 6; face++) {
            if (textureData[face] == null
                    || TextureUtils.getWrittenPixelCount(textureData[face], checkMode) == 0) {
                Logger.warn("LambdaBetterGrass sibling for " + result.blockState
                        + " has an empty side view; keeping the vanilla model");
                return;
            }
        }

        int variantModelId = this.modelTexture2id.size();
        if (variantModelId >= MAX_MODEL_COUNT) {
            this.reportModelOverflow("LambdaBetterGrass sibling of model " + baseModelId);
            return;
        }

        ModelEntry entry = new ModelEntry(
                copyTextureData(textureData), -3 - baseModelId, -1, 0);
        this.modelTexture2id.put(entry, variantModelId);
        this.lbgVariantLUT[baseModelId] = variantModelId;
        this.metadataCache[variantModelId] = this.metadataCache[baseModelId];
        this.fluidStateLUT[variantModelId] = this.fluidStateLUT[baseModelId];

        ModelBakeResultUpload upload = new ModelBakeResultUpload();
        upload.modelId = variantModelId;
        MemoryUtil.memCopy(
                this.modelDataCache.address + (long) baseModelId * MODEL_SIZE,
                upload.model.address,
                MODEL_SIZE);

        for (int face = 2; face < 6; face++) {
            long ptr = upload.model.address + 4L * face;
            int faceData = MemoryUtil.memGetInt(ptr);
            if (faceData == -1) {
                continue;
            }
            int tintState = TextureUtils.computeFaceTint(textureData[face], checkMode);
            faceData &= ~(3 << 24);
            if (tintState == 2) {
                faceData |= 1 << 24;
            } else if (tintState == 3) {
                faceData |= 2 << 24;
            }
            MemoryUtil.memPutInt(ptr, faceData);
        }

        MipGen.putTextures(result.hasDarkenedTextures, textureData, upload.texture);
        this.uploadResults.add(upload);
        Logger.info("LambdaBetterGrass LOD side model " + variantModelId + " for "
                + result.blockState + " (base " + baseModelId + ")");
    }

    private static final class BiomeUploadResult implements ResultUploader {
        private final MemoryBuffer biomeColourBuffer;
        private final MemoryBuffer modelBiomeIndexPairs;
        private BiomeUploadResult(int biomes, int models) {
            this.biomeColourBuffer = new MemoryBuffer(biomes*models*4);
            this.modelBiomeIndexPairs = new MemoryBuffer(models*8);
        }

        public void upload(ModelStore store) {
            this.upload(store.modelBuffer, store.modelColourBuffer);
        }

        public void upload(VkBuffer modelBuffer, VkBuffer modelColourBuffer) {
            this.biomeColourBuffer.cpyTo(UploadStream.INSTANCE.upload(modelColourBuffer, 0, this.biomeColourBuffer.size));

            //AUDIT-PERF: optimize this to a compute scatter update or similar —
            // CPU loop over every (model,biome) pair per upload.
            long ptr = this.modelBiomeIndexPairs.address;
            for (long offset = 0; offset < this.modelBiomeIndexPairs.size; offset += 8) {
                long v = MemoryUtil.memGetLong(ptr);ptr += 8;
                MemoryUtil.memPutInt(UploadStream.INSTANCE.upload(modelBuffer, (MODEL_SIZE*(v&((1L<<32)-1)))+ 4*6 + 4, 4), (int) (v>>>32));
            }

            this.biomeColourBuffer.free();
            this.modelBiomeIndexPairs.free();
        }

        public void free() {
            if (!this.biomeColourBuffer.isFreed()) {
                this.biomeColourBuffer.free();
                this.modelBiomeIndexPairs.free();
            }
        }
    }

    private BiomeUploadResult addBiome0(int id, Biome biome) {
        if (!this.modelsRequiringBiomeColours.isEmpty()) {
            int projectedBiomes = Math.max(this.biomes.size(), id + 1);
            if (!this.biomeRowFits(this.modelsRequiringBiomeColours.size(), projectedBiomes)) {
                // Growing the rows would run past the fixed colour buffer. Keep the
                // existing rows valid and drop this biome; its tint degrades to the
                // stale/white fallback, but no upload can go out of bounds.
                this.reportBiomeOverflow(id, projectedBiomes);
                return null;
            }
        }
        for (int i = this.biomes.size(); i <= id; i++) {
            this.biomes.add(null);
        }
        var oldBiome = this.biomes.set(id, biome);

        if (oldBiome != null && oldBiome != biome) {
            throw new IllegalStateException("Biome was put in an id that was not null");
        }
        if (oldBiome == biome) {
            Logger.error("Biome added was a duplicate");
        }

        if (this.modelsRequiringBiomeColours.isEmpty()) return null;

        var result = new BiomeUploadResult(this.biomes.size(), this.modelsRequiringBiomeColours.size());

        int i = 0;
        long modelUpPtr = result.modelBiomeIndexPairs.address;
        for (var entry : this.modelsRequiringBiomeColours) {
            var colourProvider = getColourProvider(entry.right().getBlock());
            if (colourProvider == null) {
                throw new IllegalStateException();
            }
            //Populate the list of biomes for the model state
            int biomeIndex = (i++) * this.biomes.size();
            MemoryUtil.memPutLong(modelUpPtr, Integer.toUnsignedLong(entry.left())|(Integer.toUnsignedLong(biomeIndex)<<32));modelUpPtr+=8;
            long clrUploadPtr = result.biomeColourBuffer.address + biomeIndex * 4L;
            for (var biomeE : this.biomes) {
                int col = biomeE != null ? (captureColourConstant(colourProvider, entry.right(), biomeE) | 0xFF000000) : 0xFFFFFFFF;
                MemoryUtil.memPutInt(clrUploadPtr, col);
                clrUploadPtr += 4;
            }
        }

        return result;
    }

    private static BlockColor getColourProvider(Block block) {
        return Minecraft.getInstance().getBlockColors().blockColors.byId(BuiltInRegistries.BLOCK.getId(block));
    }

    //TODO: add a method to detect biome dependent colours (can do by detecting if getColor is ever called)
    // if it is, need to add it to a list and mark it as biome colour dependent or something then the shader
    // will either use the uint as an index or a direct colour multiplier
    private static int captureColourConstant(BlockColor colorProvider, BlockState state, Biome biome) {
        var getter = new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return colorResolver.getColor(biome, 0, 0);
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinY() {
                return 0;
            }
        };
        //Multiple layer bs to do with flower beds
        int c = colorProvider.getColor(state, getter, BlockPos.ZERO, 0);
        if (c!=-1) return c;
        return colorProvider.getColor(state, getter, BlockPos.ZERO, 1);
    }

    private static boolean isBiomeDependentColour(BlockColor colorProvider, BlockState state) {
        boolean[] biomeDependent = new boolean[1];
        var getter = new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                biomeDependent[0] = true;
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinY() {
                return 0;
            }
        };
        colorProvider.getColor(state, getter, BlockPos.ZERO, 0);
        colorProvider.getColor(state, getter, BlockPos.ZERO, 1);
        return biomeDependent[0];
    }

    /**
     * The model map is keyed by the texture arrays. Stair-step extraction and
     * hollow-container shading mutate the arrays in place after the key has
     * been inserted, which changes equals() without rehashing the entry and
     * makes the whole map unreliable (duplicate model ids / wrong atlas
     * colours). Keep an independent copy for the key.
     */
    private static ColourDepthTextureData[] copyTextureData(ColourDepthTextureData[] data) {
        var copy = new ColourDepthTextureData[data.length];
        for (int i = 0; i < data.length; i++) {
            copy[i] = data[i] == null ? null : data[i].clone();
        }
        return copy;
    }

    private static ColourDepthTextureData[] extractStairStepTextures(ColourDepthTextureData[] textureData) {
        final int stepThreshold = ((1 << 24) - 1) / 8; // u2fdepth <= 0.25f is outer (0.0), > 0.25f is inner (0.5)
        ColourDepthTextureData[] stepData = new ColourDepthTextureData[6];
        boolean anyStepFace = false;
        for (int f = 0; f < 6; f++) {
            ColourDepthTextureData src = textureData[f];
            int w = src.width();
            int h = src.height();
            int[] col = src.colour();
            int[] dep = src.depth();

            int[] stepCol = new int[w * h];
            int[] stepDep = new int[w * h];
            Arrays.fill(stepDep, 0xFFFFFF00);

            boolean hasOuter = false;
            boolean hasInner = false;
            for (int i = 0; i < w * h; i++) {
                if ((dep[i] & 0xFF) != 0) {
                    if ((dep[i] >>> 8) > stepThreshold) {
                        hasInner = true;
                    } else {
                        hasOuter = true;
                    }
                }
            }

            if (hasOuter && hasInner) {
                anyStepFace = true;
                for (int i = 0; i < w * h; i++) {
                    if ((dep[i] & 0xFF) != 0 && (dep[i] >>> 8) > stepThreshold) {
                        stepCol[i] = col[i];
                        stepDep[i] = dep[i];
                        col[i] = 0;
                        dep[i] = 0xFFFFFF00;
                    }
                }
            }
            stepData[f] = new ColourDepthTextureData(stepCol, stepDep, w, h);
        }
        return anyStepFace ? stepData : null;
    }

    private static void shadeHollowContainerTop(ColourDepthTextureData topFace) {
        final int rimThreshold = ((1 << 24) - 1) / 32; // u2fdepth <= 0.0625f is top rim
        int w = topFace.width();
        int h = topFace.height();
        int[] col = topFace.colour();
        int[] dep = topFace.depth();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = x + y * w;
                if ((dep[idx] & 0xFF) == 0 || (dep[idx] >>> 8) <= rimThreshold) {
                    continue;
                }
                int rimDist = Math.min(Math.min(x - 2, (w - 3) - x), Math.min(y - 2, (h - 3) - y));
                float shade = (rimDist <= 0) ? 0.56f : ((rimDist == 1) ? 0.42f : 0.30f);
                int c = col[idx];
                int r = Math.clamp(Math.round((c & 0xFF) * shade), 0, 255);
                int g = Math.clamp(Math.round(((c >>> 8) & 0xFF) * shade), 0, 255);
                int b = Math.clamp(Math.round(((c >>> 16) & 0xFF) * shade), 0, 255);
                int a = (c >>> 24) & 0xFF;
                col[idx] = (a << 24) | (b << 16) | (g << 8) | r;
            }
        }
    }

    private ModelBakeResultUpload createStairStepUpload(
            int parentModelId, ColourDepthTextureData[] stepTextureData, float[] stepDepths,
            int checkMode, ChunkSectionLayer blockRenderLayer, BlockColor colourProvider,
            boolean isBiomeColourDependent, int tintingColour, int resolvedBiomeIndex,
            int resolvedCustomId, boolean isShaded, boolean darkenedTinting) {
        int stepModelId = this.modelTexture2id.size();
        if (stepModelId >= MAX_MODEL_COUNT) {
            this.reportModelOverflow("stair-step sibling of model " + parentModelId);
            return null;
        }
        this.modelTexture2id.put(new ModelEntry(stepTextureData, -2 - parentModelId, tintingColour, 0), stepModelId);
        this.stairStepLUT[parentModelId] = stepModelId;

        ModelBakeResultUpload stepUpload = new ModelBakeResultUpload();
        stepUpload.modelId = stepModelId;
        long uploadPtr = stepUpload.model.address;

        long metadata = 0;
        metadata |= isBiomeColourDependent ? 1 : 0;
        metadata |= blockRenderLayer == ChunkSectionLayer.TRANSLUCENT ? 2 : 0;

        for (int face = 5; face != -1; face--) {
            long faceUploadPtr = uploadPtr + 4L * face;
            metadata <<= 8;
            float offset = stepDepths[face];
            if (offset < -0.1f) {
                metadata |= 0xFF;
                MemoryUtil.memPutInt(faceUploadPtr, -1);
                continue;
            }
            var faceSize = TextureUtils.computeBounds(stepTextureData[face], checkMode);
            int writeCount = TextureUtils.getWrittenPixelCount(stepTextureData[face], checkMode);
            boolean faceCoversFullBlock = faceSize[0] == 0 && faceSize[2] == 0
                    && faceSize[1] == (MODEL_TEXTURE_SIZE - 1) && faceSize[3] == (MODEL_TEXTURE_SIZE - 1);
            metadata |= faceCoversFullBlock ? 2 : 0;
            metadata |= 0b1000; // uses self lighting

            int enc = Math.round(offset * 64);
            int faceModelData = faceSize[0] | (faceSize[1] << 4) | (faceSize[2] << 8) | (faceSize[3] << 12);
            faceModelData |= Math.min(enc, 62) << 16;

            int area = (faceSize[1] - faceSize[0] + 1) * (faceSize[3] - faceSize[2] + 1);
            boolean needsAlphaDiscard = ((float) writeCount) / area < 0.9f
                    || blockRenderLayer != ChunkSectionLayer.SOLID;
            needsAlphaDiscard &= blockRenderLayer != ChunkSectionLayer.TRANSLUCENT;
            faceModelData |= needsAlphaDiscard ? 1 << 22 : 0;
            faceModelData |= ((!faceCoversFullBlock) && blockRenderLayer != ChunkSectionLayer.TRANSLUCENT) ? 1 << 23 : 0;

            if (colourProvider != null) {
                int tintState = TextureUtils.computeFaceTint(stepTextureData[face], checkMode);
                if (tintState == 2) {
                    faceModelData |= 1 << 24;
                } else if (tintState == 3) {
                    faceModelData |= 2 << 24;
                }
            }
            MemoryUtil.memPutInt(faceUploadPtr, faceModelData);
        }

        this.metadataCache[stepModelId] = metadata;
        uploadPtr += 4 * 6;

        int modelFlags = 0;
        modelFlags |= colourProvider != null ? 1 : 0;
        modelFlags |= isBiomeColourDependent ? 2 : 0;
        modelFlags |= blockRenderLayer == ChunkSectionLayer.TRANSLUCENT ? 4 : 0;
        modelFlags |= isShaded ? 8 : 0;
        MemoryUtil.memPutInt(uploadPtr, modelFlags);
        uploadPtr += 4;

        if (colourProvider == null) {
            MemoryUtil.memPutInt(uploadPtr, -1);
        } else if (!isBiomeColourDependent) {
            MemoryUtil.memPutInt(uploadPtr, tintingColour);
        } else {
            MemoryUtil.memPutInt(uploadPtr, resolvedBiomeIndex);
        }
        uploadPtr += 4;

        MemoryUtil.memPutInt(uploadPtr, resolvedCustomId);
        MipGen.putTextures(darkenedTinting, stepTextureData, stepUpload.texture);
        return stepUpload;
    }

    public int getStairStepModelId(int clientModelId) {
        return (clientModelId >= 0 && clientModelId < this.stairStepLUT.length) ? this.stairStepLUT[clientModelId] : -1;
    }

    /** {@return the LambdaBetterGrass connected-side sibling of a model id, or -1} */
    public int getLambdabettergrassVariant(int clientModelId) {
        return (clientModelId >= 0 && clientModelId < this.lbgVariantLUT.length) ? this.lbgVariantLUT[clientModelId] : -1;
    }

    /**
     * Vanilla per-position model offset (BlockBehaviour.OffsetType XZ / XYZ) as two half floats:
     * low = max horizontal offset, high = max vertical offset (0 for XZ). Returns 0 when the
     * state has no offset or its offset function is not the vanilla one.
     */
    /** Vanilla's position hash behind {@link BlockState#getOffset} (reimplemented;
     *  {@code Mth.getSeed} is deprecated upstream). */
    private static long blockOffsetSeed(int x, int y, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    private static int computeOffsetInfo(BlockState state) {
        if (!state.hasOffsetFunction()) {
            return 0;
        }
        try {
            // getMaxHorizontalOffset/getMaxVerticalOffset are protected. Where the seed nibble is
            // 0 the vanilla offset is exactly -max (horizontal: -min(0.25, max), same clamp).
            float maxH = -1.0f;
            float maxV = -1.0f;
            for (int x = 0; x < 4096 && (maxH < 0.0f || maxV < 0.0f); x++) {
                long seed = blockOffsetSeed(x, 0, 0);
                if (maxH < 0.0f && (seed & 15L) == 0L) {
                    maxH = Math.max(0.0f, (float) -state.getOffset(new BlockPos(x, 0, 0)).x);
                }
                if (maxV < 0.0f && ((seed >> 4) & 15L) == 0L) {
                    maxV = Math.max(0.0f, (float) -state.getOffset(new BlockPos(x, 0, 0)).y);
                }
            }
            if (maxH < 0.0f || maxV < 0.0f || (maxH == 0.0f && maxV == 0.0f)) {
                return 0;
            }
            // Verify the reconstruction (this also rejects modded offset functions).
            for (int i = 0; i < 16; i++) {
                int x = i * 7919 - 60013;
                int y = i * 23 - 64;
                int z = 31337 - i * 104729;
                long seed = blockOffsetSeed(x, 0, z);
                double ex = Mth.clamp(((float) (seed & 15L) / 15.0f - 0.5) * 0.5, -maxH, maxH);
                double ey = ((float) ((seed >> 4) & 15L) / 15.0f - 1.0) * maxV;
                double ez = Mth.clamp(((float) ((seed >> 8) & 15L) / 15.0f - 0.5) * 0.5, -maxH, maxH);
                Vec3 actual = state.getOffset(new BlockPos(x, y, z));
                if (Math.abs(actual.x - ex) > 1e-4 || Math.abs(actual.y - ey) > 1e-4 || Math.abs(actual.z - ez) > 1e-4) {
                    return 0;
                }
            }
            return ((Float.floatToFloat16(maxV) & 0xFFFF) << 16) | (Float.floatToFloat16(maxH) & 0xFFFF);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static float[] computeModelDepth(ColourDepthTextureData[] textures, int checkMode, int computeMode) {
        float[] res = new float[6];
        for (var dir : Direction.values()) {
            var data = textures[dir.get3DDataValue()];
            float fd = TextureUtils.computeDepth(data, computeMode, checkMode);//Compute the min float depth, smaller means closer to the camera, range 0-1
            //int depth = Math.round(fd * MODEL_TEXTURE_SIZE);
            //If fd is -1, it means that there was nothing rendered on that face and it should be discarded
            if (fd < -0.1) {
                res[dir.ordinal()] = -1;
            } else {
                res[dir.ordinal()] = fd;//((float) depth)/MODEL_TEXTURE_SIZE;
            }
        }
        return res;
    }

    /** Acquire-load of the published model id (-1 = not baked yet). */
    public int getModelIdAcquire(int blockId) {
        return (int) ID_MAPPINGS.getAcquire(this.idMappings, blockId);
    }

    /** Release-publish after every metadata table for this model is written. */
    private void publishModelId(int blockId, int modelId) {
        ID_MAPPINGS.setRelease(this.idMappings, blockId, modelId);
    }

    public int getModelId(int blockId) {
        int map = getModelIdAcquire(blockId);
        if (map == -1) {
            throw new IdNotYetComputedException(blockId, true);
        }
        return map;
    }

    public boolean hasModelForBlockId(int blockId) {
        return getModelIdAcquire(blockId) != -1;
    }

    public int getFluidClientStateId(int clientBlockStateId) {
        int map = this.fluidStateLUT[clientBlockStateId];
        if (map == -1) {
            throw new IdNotYetComputedException(clientBlockStateId, false);
        }
        return map;
    }

    public long getModelMetadataFromClientId(int clientId) {
        return this.metadataCache[clientId];
    }


    public void free() {
        this.bakery.free();
        this.downstream.free();
        this.modelDataCache.free();
        while (!this.rawBakeResults.isEmpty()) {
            this.rawBakeResults.poll().rawData.free();
        }
        while (!this.uploadResults.isEmpty()) {
            this.uploadResults.poll().free();
        }
    }

    public int getBakedCount() {
        return this.modelTexture2id.size();
    }

    public int getInflightCount() {
        //TODO replace all of this with an atomic?
        int size = this.blockStatesInFlight.size();
        size += this.uploadResults.size();
        size += this.biomeQueue.size();
        return size;
    }


    private static int computeSizeWithMips(int size) {
        int total = 0;
        for (;size!=0;size>>=1) total += size*size;
        return total;
    }
}
