package voxel.client.core.model.bakery;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import voxel.client.compat.lambdabettergrass.LambdaBetterGrassCompat;
import voxel.client.core.vk.VkState;
import voxel.client.vk.VkVoxel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class ModelTextureBakery {
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final VkViewCapture capture;
    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();

    private final int width;
    private final int height;

    public ModelTextureBakery(int width, int height) {
        this.capture = new VkViewCapture(width, height);
        this.width = width;
        this.height = height;
    }

    public static int getMetaFromLayer(ChunkSectionLayer layer) {
        boolean hasDiscard = layer == ChunkSectionLayer.CUTOUT ||
                layer == ChunkSectionLayer.TRANSLUCENT ||
                layer == ChunkSectionLayer.TRIPWIRE;

        int meta = hasDiscard ? 1 : 0;
        meta |= 2;
        return meta;
    }

    private boolean bakeBlockModel(BlockState state, ChunkSectionLayer layer, boolean lbgConnected) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return false;
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        int meta = getMetaFromLayer(layer);

        if (lbgConnected) {
            return LambdaBetterGrassCompat.bakeConnected(model, state, this.vc, meta);
        }

        // CullLeaves' smartleaves model (and several 3D leaf packs) adds inner
        // offset quads around the normal cube. Baking those into the 6-view
        // atlas leaves holes and rotated fragments when the LOD shader maps
        // them back onto a cube surface. Keep only the six full outer faces.
        if (bakeLeafOuterFaces(state, layer, model)) {
            return false;
        }

        var randomSource = new SingleThreadedRandomSource(42L);
        boolean isFence = state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock;
        List<BlockModelPart> parts = model.collectParts(randomSource);
        for (BlockModelPart part : parts) {
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                if (isFence && direction != null && direction.getAxis().isHorizontal()) {
                    // Skip horizontal cullface rail end-caps at block boundaries (always culled when connected in vanilla)
                    continue;
                }
                var quads = part.getQuads(direction);
                for (var quad : quads) {
                    this.vc.quad(quad, meta | (quad.isTinted() ? 4 : 0));
                }
            }
        }
        return false;
    }

    /**
     * Leaves are the one common model that resource packs replace with a
     * double-sided, offset cube (CullLeaves) or a multi-plane bush. The LOD
     * atlas represents the block as one six-view cube, so choose the six
     * original unit-cube faces and ignore the inner offset copies.
     *
     * @return true when all six outer faces were found and emitted
     */
    private boolean bakeLeafOuterFaces(BlockState state, ChunkSectionLayer layer, BlockStateModel model) {
        if (!(state.getBlock() instanceof LeavesBlock)) {
            return false;
        }

        var random = new SingleThreadedRandomSource(42L);
        List<BlockModelPart> parts = model.collectParts(random);
        BakedQuad[] faces = new BakedQuad[6];
        int found = 0;
        for (BlockModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                int index = direction.ordinal();
                if (faces[index] != null) {
                    continue;
                }
                for (BakedQuad quad : part.getQuads(direction)) {
                    if (isFullCubeFace(quad, direction)) {
                        faces[index] = quad;
                        found++;
                        break;
                    }
                }
            }
        }
        if (found != 6) {
            return false;
        }

        int meta = getMetaFromLayer(layer);
        for (Direction direction : Direction.values()) {
            BakedQuad quad = faces[direction.ordinal()];
            this.vc.quad(quad, meta | (quad.isTinted() ? 4 : 0));
        }
        return true;
    }

    /** True when all four vertices sit on the block boundary and span the whole face. */
    private static boolean isFullCubeFace(BakedQuad quad, Direction direction) {
        float plane = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0f : 0.0f;
        float minA = 1.0f;
        float maxA = 0.0f;
        float minB = 1.0f;
        float maxB = 0.0f;
        for (int i = 0; i < 4; i++) {
            var p = quad.position(i);
            float normal;
            float a;
            float b;
            switch (direction.getAxis()) {
                case X -> {
                    normal = p.x();
                    a = p.y();
                    b = p.z();
                }
                case Y -> {
                    normal = p.y();
                    a = p.x();
                    b = p.z();
                }
                case Z -> {
                    normal = p.z();
                    a = p.x();
                    b = p.y();
                }
                default -> throw new IllegalStateException();
            }
            if (Math.abs(normal - plane) > 0.001f) {
                return false;
            }
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }
        return minA <= 0.001f && maxA >= 0.999f
                && minB <= 0.001f && maxB >= 0.999f;
    }

    private void bakeFluidState(BlockState state, ChunkSectionLayer layer, int face) {
        bakeFluidState(state, layer, face, false);
    }

    private void bakeFluidState(BlockState state, ChunkSectionLayer layer, int face, boolean lavaFlowTop) {
        {
            int metadata = getMetaFromLayer(layer);
            metadata |= 4;//Has tint
            this.vc.setDefaultMeta(metadata);
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face, lavaFlowTop)) {
                    return Blocks.AIR.defaultBlockState();
                }
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face, lavaFlowTop)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }
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
        }, this.vc, state, state.getFluidState());
        if (lavaFlowTop && face == 1) {
            // Vanilla samples the flowing sprite through a half-size window
            // around the sprite centre ([0.25, 0.75]^2 at the trench's rotation
            // 0). Widen it to the whole sprite so the LOD vertex shader can
            // rotate the window like vanilla without leaving the tile.
            this.vc.scaleQuadUvs(2.0f);
        }
        this.vc.setDefaultMeta(0);
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face, boolean lavaFlowTop) {
        var fv = Direction.from3DDataValue(face).getUnitVec3i();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        if (dot >= 1) {
            return true;
        }
        // Lava flow-top capture: open a one-block-wide air trench to the south
        // (z >= 1) at and above the fluid's own height, leaving y < 0 filled.
        // Vanilla's LiquidBlockRenderer then sees air over lava below to the
        // south and reports flow = (0, 0, 1): the UP face is baked with the
        // flowing sprite at rotation angle 0; bakeFluidState then widens that
        // window to the whole sprite, which the LOD vertex shader re-rotates
        // per quad.
        return lavaFlowTop && face == 1 && pos.getZ() >= 1 && pos.getY() >= 0;
    }

    public void free() {
        this.capture.free();
        this.vc.free();
    }

    private static VulkanImage getBlockAtlasImage() {
        var tex = Minecraft.getInstance().getAtlasManager()
                .getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        GpuTexture gpuTex = tex.getTexture();
        if (gpuTex instanceof VkGpuTexture vkTex) {
            return vkTex.getVulkanImage();
        }
        if (gpuTex instanceof GlTexture glTex) {
            return VkGpuTexture.fromGlTexture(glTex).getVulkanImage();
        }
        throw new IllegalStateException("Unexpected block atlas texture class: " + gpuTex);
    }

    public int renderToStream(BlockState state, long streamBuffer, int streamOffset) {
        return this.renderToStream(state, streamBuffer, streamOffset, false);
    }

    public int renderToStream(BlockState state, long streamBuffer, int streamOffset, boolean lavaFlowTop) {
        return this.renderToStream(state, streamBuffer, streamOffset, lavaFlowTop, false);
    }

    public int renderToStream(BlockState state, long streamBuffer, int streamOffset, boolean lavaFlowTop, boolean lbgConnected) {
        this.capture.clear();
        boolean isBlock = true;
        ChunkSectionLayer layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else {
            layer = ItemBlockRenderTypes.getChunkRenderType(state);
        }

        VulkanImage blockAtlas = getBlockAtlasImage();

        {
            VkState.setStencilTest(true);
            VkState.setDepthTest(true);
            VkState.setCullFace(true);
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                VkState.setBlend(true);
                VkState.setBlendFuncSeparate(GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                VkState.setBlend(false);
            }

            VkState.setStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
            VkState.setStencilFunc(GL_ALWAYS, 1, 0xFF);
            VkState.setStencilMask(0xFF);
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean isAnyAnimated = false;
        boolean lbgCaptured = false;
        if (isBlock) {
            this.vc.reset();
            lbgCaptured = this.bakeBlockModel(state, layer, lbgConnected);
            isAnyShaded |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            isAnyAnimated |= this.vc.anyAnimated;
            if (!this.vc.isEmpty()) {
                BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockAtlas);
                this.capture.framebuffer.bindRenderTarget();

                var mat = new Matrix4f();
                for (int i = 0; i < VIEWS.length; i++) {
                    VkState.setCullMode(i == 1 || i == 2 || i == 4);

                    Renderer.setViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                    mat.set(2, 0, 0, 0,
                            0, 2, 0, 0,
                            0, 0, -0.5f, 0,
                            -1, -1, 0.5f, 1)
                            .mul(VIEWS[i]);

                    BudgetBufferRenderer.render(mat);
                }
            }
        } else {
            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();

            var mat = new Matrix4f();
            for (int i = 0; i < VIEWS.length; i++) {
                VkState.setCullMode(i == 1 || i == 2 || i == 4);

                this.vc.reset();
                this.bakeFluidState(state, layer, i, lavaFlowTop);
                if (this.vc.isEmpty()) continue;
                isAnyShaded |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;
                isAnyAnimated |= this.vc.anyAnimated;
                BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockAtlas);
                this.capture.framebuffer.bindRenderTarget();

                Renderer.setViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                mat.set(2, 0, 0, 0,
                        0, 2, 0, 0,
                        0, 0, -0.5f, 0,
                        -1, -1, 0.5f, 1)
                        .mul(VIEWS[i]);

                BudgetBufferRenderer.render(mat);
            }
        }

        VkState.setCullMode(false);
        VkState.setStencilTest(false);
        VkState.setBlend(false);

        VkVoxel.fullBarrier();
        this.capture.emitToStream(streamBuffer, streamOffset);

        this.capture.depthTex().image().clearDepth(1.0f);
        if (layer == ChunkSectionLayer.TRANSLUCENT) {
            VkState.setBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }

        return (isAnyShaded ? 1 : 0) | ((isAnyDarkend && layer == ChunkSectionLayer.SOLID) ? 2 : 0)
                | (isAnyAnimated ? 4 : 0) | (lbgCaptured ? 8 : 0);
    }

    static {
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        stack.mulPose(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)));
        stack.translate(-0.5f,-0.5f,-0.5f);
        VIEWS[i] = new Matrix4f(stack.last().pose());
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
