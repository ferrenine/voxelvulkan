package voxel.client.core;

import voxel.client.config.VoxelConfig;
import voxel.client.core.vk.VkFramebuffer;
import voxel.client.core.vk.VkState;
import voxel.client.core.vk.VkTexture;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.hierarchical.AsyncNodeManager;
import voxel.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import voxel.client.core.rendering.hierarchical.NodeCleaner;
import voxel.client.core.rendering.post.FullscreenBlit;
import voxel.client.core.rendering.util.UploadStream;
import voxel.client.core.util.BerylUtil;
import voxel.client.vk.VkVoxel;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_RGBA16F;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

public class NormalRenderPipeline extends AbstractRenderPipeline {
    private VkTexture colourTex;
    private VkTexture colourSSAOTex;
    private VkTexture colourTranslucentTex;
    private boolean currentHdr;
    private net.vulkanmod.vulkan.texture.VulkanImage lastSourceDepth;
    private final VkFramebuffer fbSSAO = new VkFramebuffer();
    private final VkFramebuffer fbTranslucent = new VkFramebuffer();

    private final boolean useEnvFog;
    private final FullscreenBlit finalBlit;
    private final FullscreenBlit translucentBlit;

    private final AutoBindingShader ssaoComputeLdr = Shader.makeAuto()
            .add(ShaderType.COMPUTE, "voxel:post/ssao.comp")
            .compile();

    private final AutoBindingShader ssaoComputeHdr = Shader.makeAuto()
            .define("HDR_OUTPUT")
            .add(ShaderType.COMPUTE, "voxel:post/ssao.comp")
            .compile();

    protected NormalRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier syncCaptureSupplier) {
        super(nodeManager, nodeCleaner, traversal, syncCaptureSupplier, true);
        this.useEnvFog = VoxelConfig.CONFIG.useEnvironmentalFog;
        this.finalBlit = new FullscreenBlit("voxel:post/blit_texture_depth_cutout.frag",
                a -> a.defineIf("USE_ENV_FOG", this.useEnvFog).define("EMIT_COLOUR"));
        this.translucentBlit = new FullscreenBlit("voxel:post/blit_texture_depth_cutout.frag",
                a -> a.defineIf("USE_ENV_FOG", this.useEnvFog).define("EMIT_COLOUR").define("TRANSLUCENT_COMPOSITE"));
    }

    @Override
    protected long setup(Viewport<?> viewport, Framebuffer sourceFB, int srcWidth, int srcHeight) {
        boolean wantHdr = BerylUtil.berylShaderEnabled();
        if (this.colourTex == null
                || this.colourTex.getHeight() != viewport.height
                || this.colourTex.getWidth() != viewport.width
                || this.currentHdr != wantHdr) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
                this.colourTranslucentTex.free();
            }
            this.currentHdr = wantHdr;
            int glFormat = wantHdr ? GL_RGBA16F : GL_RGBA8;
            this.fb.resize(viewport.width, viewport.height);

            this.colourTex = new VkTexture().store(glFormat, 1, viewport.width, viewport.height);
            this.colourSSAOTex = new VkTexture().store(glFormat, 1, viewport.width, viewport.height);
            this.colourTranslucentTex = new VkTexture().store(glFormat, 1, viewport.width, viewport.height);

            this.fb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.colourTex).verify();
            this.fbSSAO.bind(this.fb.getDepthAttachmentType(), this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourSSAOTex).verify();
            this.fbTranslucent.bind(this.fb.getDepthAttachmentType(), this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourTranslucentTex).verify();
        }

        this.lastSourceDepth = sourceFB != null ? sourceFB.getDepthAttachment() : null;
        this.colourTex.image().clearColorZero();
        this.initDepthStencil(viewport, sourceFB, this.fb.framebuffer, srcWidth, srcHeight, viewport.width, viewport.height);

        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        VkVoxel.ensureOutsideRenderPass();
        long berylPtr = UploadStream.INSTANCE.upload(this.blitUniformBuffer, 160, 80);
        BerylUtil.writeBerylUniforms(berylPtr);
        UploadStream.INSTANCE.commit();

        this.colourSSAOTex.image().transition(VK_IMAGE_LAYOUT_GENERAL);

        AutoBindingShader ssao = this.currentHdr ? this.ssaoComputeHdr : this.ssaoComputeLdr;
        ssao.storageImage(0, this.colourSSAOTex)
                .texture(1, 0, this.fb.getDepthTex())
                .texture(2, 0, this.colourTex)
                .ubo(3, this.blitUniformBuffer)
                .pushMat4(0, viewport.MVP)
                .pushMat4(64, viewport.MVP.invert(new Matrix4f()));

        ssao.dispatch((viewport.width + 15) / 16, (viewport.height + 15) / 16, 1);
        VkVoxel.computeBarrier();
        this.colourSSAOTex.image().transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        // Update HiZ mip 0 with the complete opaque depth (including temporal sections and converted near-field depth) for water SSR
        float m32v = viewport.vanillaProjection.m32();
        float depthScale = Math.abs(m32v) > 1e-6f ? (viewport.projection.m32() / m32v) : 0.0f;
        float depthBias = depthScale * viewport.vanillaProjection.m22() - viewport.projection.m22();
        viewport.hiZBuffer.copyTopMip(this.fb.getDepthTex(), this.lastSourceDepth, depthScale, depthBias);
    }

    @Override
    protected void finish(Viewport<?> viewport, Framebuffer sourceFrameBuffer, int srcWidth, int srcHeight) {
        MappedBuffer fogCol = VRenderSystem.getShaderFogColor();
        float fogR = fogCol != null ? fogCol.getFloat(0) : 0.7f;
        float fogG = fogCol != null ? fogCol.getFloat(4) : 0.8f;
        float fogB = fogCol != null ? fogCol.getFloat(8) : 1.0f;
        float fogA = fogCol != null && fogCol.getFloat(12) > 0 ? fogCol.getFloat(12) : 1.0f;
        float fogEnd = (float) (VoxelConfig.CONFIG.sectionRenderDistance * 32.0f * 16.0f);
        float fogStart = BerylUtil.berylShaderEnabled()
                ? fogEnd * 0.8f
                : (float) (Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f);
        float cameraY = (float) Minecraft.getInstance().gameRenderer.getMainCamera().position().y;

        float invRange = 1.0f / Math.max(1.0f, fogEnd - fogStart);
        long ptr = UploadStream.INSTANCE.upload(this.blitUniformBuffer, 128, 32);
        MemoryUtil.memPutFloat(ptr + 0, invRange);
        MemoryUtil.memPutFloat(ptr + 4, -fogStart * invRange);
        MemoryUtil.memPutFloat(ptr + 8, 1.0f);
        MemoryUtil.memPutFloat(ptr + 12, cameraY);
        MemoryUtil.memPutFloat(ptr + 16, fogR);
        MemoryUtil.memPutFloat(ptr + 20, fogG);
        MemoryUtil.memPutFloat(ptr + 24, fogB);
        MemoryUtil.memPutFloat(ptr + 28, fogA);

        this.finalBlit.shader().texture(3, 0, this.colourSSAOTex);

        this.transformBlitDepth(this.finalBlit,
                this.fb.getDepthTex(), sourceFrameBuffer,
                viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        super.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
    }

    @Override
    protected void finishTranslucent(Viewport<?> viewport, Framebuffer sourceFrameBuffer, int srcWidth, int srcHeight) {
        this.translucentBlit.shader().texture(3, 0, this.colourTranslucentTex);
        VkState.setBlend(true);
        VkState.setBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        this.transformBlitDepth(this.translucentBlit,
                this.fb.getDepthTex(), sourceFrameBuffer,
                viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        VkState.setBlend(false);
        super.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
    }

    @Override
    public void free() {
        super.free0();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
            this.colourTranslucentTex.free();
        }
        this.fbSSAO.free();
        this.fbTranslucent.free();
        this.finalBlit.delete();
        this.translucentBlit.delete();
        this.ssaoComputeLdr.free();
        this.ssaoComputeHdr.free();
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        this.colourTranslucentTex.image().clearColorZero();
        this.fbTranslucent.bindRenderTarget();
        VkState.setStencilTest(true);
        VkState.setStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        VkState.setStencilFunc(GL_EQUAL, 1, 0xFF);
        VkState.setBlend(true);
        VkState.setBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override
    public void bindTranslucentSamplers(AutoBindingShader shader, Viewport<?> viewport) {
        var berylTemp = BerylUtil.getBerylTempColorImage();
        if (berylTemp != null) {
            shader.image(9, berylTemp);
        } else {
            shader.texture(9, 0, this.colourSSAOTex);
        }
        shader.texture(10, 0, viewport.hiZBuffer.getHizTexture());
    }
}
