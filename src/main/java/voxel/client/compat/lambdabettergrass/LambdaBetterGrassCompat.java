package voxel.client.compat.lambdabettergrass;

import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import voxel.client.core.model.bakery.ReuseVertexConsumer;
import voxel.common.Logger;

/**
 * Soft compatibility bridge for LambdaBetterGrass.
 *
 * <p>LambdaBetterGrass wraps a block state model and rewrites its side quads
 * inside {@code FabricBlockStateModel.emitQuads}. The LOD model baker normally
 * uses the old {@code collectParts}/{@code getQuads} path, which never reaches
 * that hook, so distant grass kept the vanilla dirt side. When LBG is present
 * we bake an additional "connected" model by asking its wrapper to emit quads
 * for a synthetic grass ledge, then the LOD mesher picks that model for side
 * faces whose diagonal-below neighbour is the same block state.</p>
 *
 * <p>All LBG classes are referenced by name only. The mod stays a soft
 * dependency: without LBG every method here is a no-op and no LBG class is
 * loaded.</p>
 *
 * <p>The mesher deliberately applies the fully-connected rule at LOD0 even in
 * LBG's FASTEST mode, where LBG would rewrite every side. Far LOD uses the
 * connected model for all side faces. The conservative LOD0 choice keeps
 * grass blocks above stone/dirt looking vanilla instead of painting every
 * cliff green.</p>
 */
public final class LambdaBetterGrassCompat {
    private static final String LBG_MOD_PACKAGE = "dev.lambdaurora.lambdabettergrass.";
    private static volatile Boolean loaded;

    private LambdaBetterGrassCompat() {
    }

    public static boolean isLoaded() {
        Boolean value = loaded;
        if (value == null) {
            value = FabricLoader.getInstance().isModLoaded("lambdabettergrass");
            if (value) {
                Logger.info("LambdaBetterGrass detected: LOD better-grass side models enabled");
            }
            loaded = value;
        }
        return value;
    }

    /**
     * {@return true when the model was wrapped by LambdaBetterGrass}
     */
    public static boolean isLBGModel(BlockStateModel model) {
        return model != null && model.getClass().getName().startsWith(LBG_MOD_PACKAGE);
    }

    /**
     * {@return true when LBG is installed and wraps this block state's model}
     */
    public static boolean isLBGState(BlockState state) {
        if (!isLoaded() || state == null || state.isAir()) {
            return false;
        }
        // Only the full-cube better-grass states. LBG also wraps plants,
        // fences and doors for its snow-layer feature; those have different
        // geometry and must keep the base model in the LOD atlas.
        if (!(state.getBlock() instanceof SnowyDirtBlock)) {
            return false;
        }
        if (isModeOff()) {
            return false;
        }
        BlockStateModel model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);
        return isLBGModel(model);
    }

    /**
     * Reads LBG's mode through reflection so this compatibility layer stays a
     * soft dependency. When the mode is OFF the wrapper just delegates to the
     * vanilla model, so baking a sibling would only cost memory and quads.
     */
    private static boolean isModeOff() {
        try {
            Class<?> mod = Class.forName("dev.lambdaurora.lambdabettergrass.LambdaBetterGrass");
            Object instance = mod.getMethod("get").invoke(null);
            Object config = mod.getField("config").get(instance);
            Object mode = config.getClass().getMethod("getMode").invoke(config);
            return "off".equalsIgnoreCase(String.valueOf(mode));
        } catch (ReflectiveOperationException | LinkageError e) {
            // Config API changed: keep compatibility on rather than silently
            // disabling the feature.
            return false;
        }
    }

    /**
     * Bakes the connected variant of {@code state} into {@code vc}.
     *
     * @return true when at least one quad was captured
     */
    @SuppressWarnings("deprecation") // SpriteFinder.get(TextureAtlas) is still the only overload in the compile API
    public static boolean bakeConnected(BlockStateModel model, BlockState state, ReuseVertexConsumer vc, int meta) {
        if (!isLoaded() || !isLBGModel(model)) {
            return false;
        }
        Renderer renderer = Renderer.get();
        if (renderer == null) {
            return false;
        }

        try {
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            SpriteFinder sprites = SpriteFinder.get(atlas);
            MutableMesh mesh = renderer.mutableMesh();
            mesh.clear();

            if (!(model instanceof FabricBlockStateModel fabricModel)) {
                return false;
            }
            fabricModel.emitQuads(
                    mesh.emitter(),
                    new ConnectedWorld(state),
                    BlockPos.ZERO,
                    state,
                    new net.minecraft.world.level.levelgen.SingleThreadedRandomSource(42L),
                    direction -> false
            );

            final int[] captured = {0};
            mesh.forEach(quad -> {
                if (quad.atlas() != null && quad.atlas() != QuadAtlas.BLOCK) {
                    return;
                }
                TextureAtlasSprite sprite;
                try {
                    sprite = sprites.find(quad);
                } catch (RuntimeException e) {
                    sprite = null;
                }
                if (sprite == null) {
                    return;
                }
                BakedQuad baked = quad.toBakedQuad(sprite);
                vc.quad(baked, meta | (baked.isTinted() ? 4 : 0));
                captured[0]++;
            });
            return captured[0] != 0;
        } catch (RuntimeException | LinkageError e) {
            Logger.warn("LambdaBetterGrass LOD model bake failed for " + state + ": " + e);
            return false;
        }
    }

    /**
     * A synthetic 3x3 grass ledge one block below the target block. This makes
     * LBG's {@code canFullyConnect} return true for all four side faces, so the
     * captured model contains its fully connected grass side texture.
     */
    private static final class ConnectedWorld implements BlockAndTintGetter {
        private final BlockState state;

        private ConnectedWorld(BlockState state) {
            this.state = state;
        }

        @Override
        public float getShade(Direction direction, boolean shaded) {
            return 1.0f;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return -1;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int amount) {
            return 0;
        }

        @Override
        public boolean canSeeSky(BlockPos pos) {
            return false;
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (pos.equals(BlockPos.ZERO)) {
                return this.state;
            }
            if (pos.getY() == -1
                    && Math.abs(pos.getX()) <= 1
                    && Math.abs(pos.getZ()) <= 1) {
                return this.state;
            }
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }
}
