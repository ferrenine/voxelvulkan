package voxel.client.core.vk;

import voxel.client.vk.VkVoxel;
import voxel.client.vk.VkVoxelImage;
import voxel.common.util.TrackedObject;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan replacement for the original GL framebuffer object.
 *
 * <p>Supports single and multiple colour attachments (MRT, e.g.
 * {@code VkViewCapture}) as well as combined depth+stencil attachments via a
 * custom {@link RenderPass} subclass that drives {@code vkCmdBeginRenderingKHR}
 * with all bound colour attachments and {@code pStencilAttachment}.</p>
 */
public class VkFramebuffer extends TrackedObject {

    /** Last LING framebuffer bound as a render target on this thread. */
    private static VkFramebuffer current;

    /** Last external VulkanMod/Beryl framebuffer captured before LING took over. */
    private static Framebuffer lastExternal;
    private static final Map<Framebuffer, RenderPass> externalRebindPasses = new IdentityHashMap<>();

    public long id;

    private final List<VkTexture> colorAttachments = new ArrayList<>();
    private VkTexture depthTexture;
    private VkRenderBuffer depthBuffer;
    private VkVoxelImage dummyColor;

    private boolean loadOpLoad = true;

    private Framebuffer framebuffer;
    private RenderPass renderPass;

    public VkFramebuffer() {
    }

    public VkFramebuffer bind(int attachment, VkTexture texture) {
        return this.bind(attachment, texture, 0);
    }

    public VkFramebuffer bind(int attachment, VkTexture texture, int lvl) {
        if (attachment >= GL_COLOR_ATTACHMENT0 && attachment < GL_COLOR_ATTACHMENT0 + 32) {
            int index = attachment - GL_COLOR_ATTACHMENT0;
            while (this.colorAttachments.size() <= index) {
                this.colorAttachments.add(null);
            }
            this.colorAttachments.set(index, texture);
        } else if (attachment == GL_DEPTH_ATTACHMENT || attachment == GL_DEPTH_STENCIL_ATTACHMENT
                || attachment == GL_STENCIL_ATTACHMENT) {
            this.depthTexture = texture;
        }
        this.invalidate();
        return this;
    }

    public VkFramebuffer bind(int attachment, VkRenderBuffer buffer) {
        if (attachment == GL_DEPTH_ATTACHMENT || attachment == GL_DEPTH_STENCIL_ATTACHMENT) {
            this.depthBuffer = buffer;
        }
        this.invalidate();
        return this;
    }

    public VkFramebuffer setDrawBuffers(int... buffers) {
        this.invalidate();
        return this;
    }

    /** Requests that the next render pass loads existing attachment contents. */
    public VkFramebuffer setLoadOpLoad() {
        this.loadOpLoad = true;
        this.invalidate();
        return this;
    }

    public VkFramebuffer verify() {
        this.build();
        return this;
    }

    public Framebuffer framebuffer() {
        this.build();
        return this.framebuffer;
    }

    public RenderPass renderPass() {
        this.build();
        return this.renderPass;
    }

    private void invalidate() {
        if (this.framebuffer != null) {
            this.framebuffer = null;
            this.renderPass = null;
        }
    }

    private void build() {
        if (this.framebuffer != null) {
            return;
        }
        VkVoxelImage color = this.colorImage();
        VkVoxelImage depth = this.depthImage();
        if (color == null && depth == null) {
            throw new IllegalStateException("VkFramebuffer has no attachments bound");
        }
        if (color == null) {
            // A depth-only target is rebuilt on every resize; free the previous
            // dummy colour image instead of overwriting the only reference.
            if (this.dummyColor != null) {
                this.dummyColor.free();
                this.dummyColor = null;
            }
            this.dummyColor = VkVoxelImage.colorTarget("VoxelDummyColor", depth.width(), depth.height(),
                    VK_FORMAT_R8G8B8A8_UNORM);
            color = this.dummyColor;
        }
        this.framebuffer = new Framebuffer.Builder(color.image, depth != null ? depth.image : null)
                .setLinearFiltering(false)
                .build();
        this.renderPass = new VoxelRenderPass(this, this.framebuffer);
        this.id = System.identityHashCode(this.framebuffer);
    }

    /** Returns all active colour attachment images (or the single dummy colour image for depth-only targets). */
    public List<VkVoxelImage> activeColorImages() {
        this.build();
        List<VkVoxelImage> list = new ArrayList<>();
        for (VkTexture tex : this.colorAttachments) {
            if (tex != null) {
                list.add(tex.image());
            }
        }
        if (list.isEmpty() && this.dummyColor != null) {
            list.add(this.dummyColor);
        }
        return list;
    }

    public int[] colorFormats() {
        List<VkVoxelImage> imgs = this.activeColorImages();
        int[] formats = new int[imgs.size()];
        for (int i = 0; i < imgs.size(); i++) {
            formats[i] = imgs.get(i).format();
        }
        return formats;
    }

    public VkVoxelImage colorImage() {
        for (VkTexture texture : this.colorAttachments) {
            if (texture != null) {
                return texture.image();
            }
        }
        return null;
    }

    public VkVoxelImage depthImage() {
        if (this.depthTexture != null) {
            return this.depthTexture.image();
        }
        if (this.depthBuffer != null) {
            return this.depthBuffer.image();
        }
        return null;
    }

    /** First colour attachment as a raw Vulkan image (may be the dummy attachment). */
    public VulkanImage boundColorImage() {
        VkVoxelImage color = this.colorImage();
        return color != null ? color.image : (this.dummyColor != null ? this.dummyColor.image : null);
    }

    /**
     * Binds this framebuffer as the active render target: begins a dynamic
     * rendering pass through VulkanMod's {@link Renderer}.
     */
    public VkFramebuffer bindRenderTarget() {
        this.build();
        Renderer renderer = rendererOrNull();
        if (renderer != null) {
            if (current == null && renderer.getBoundFramebuffer() != null
                    && renderer.getBoundFramebuffer() != this.framebuffer) {
                lastExternal = renderer.getBoundFramebuffer();
            }
            renderer.beginRenderPass(this.renderPass, this.framebuffer);
        }
        current = this;
        return this;
    }

    /** The framebuffer currently bound as a render target, or {@code null}. */
    public static VkFramebuffer current() {
        return current;
    }

    /** Captures the currently bound VulkanMod/Beryl framebuffer so it can be restored later. */
    public static Framebuffer captureExternalFramebuffer() {
        Renderer renderer = rendererOrNull();
        if (renderer != null) {
            Framebuffer bound = renderer.getBoundFramebuffer();
            if (bound != null && (current == null || bound != current.framebuffer) && isFramebufferAlive(bound)) {
                lastExternal = bound;
            }
            if (lastExternal != null && !isFramebufferAlive(lastExternal)) {
                externalRebindPasses.remove(lastExternal);
                lastExternal = null;
            }
            if (lastExternal == null && renderer.getMainPass() != null) {
                Framebuffer mainFb = renderer.getMainPass().getMainFramebuffer();
                if (mainFb != null && isFramebufferAlive(mainFb)) {
                    lastExternal = mainFb;
                }
            }
        }
        return lastExternal;
    }

    private static boolean isFramebufferAlive(Framebuffer fb) {
        if (fb == null) {
            return false;
        }
        VulkanImage color = fb.getColorAttachment();
        if (color != null && color.getId() == 0L) {
            return false;
        }
        VulkanImage depth = fb.getDepthAttachment();
        if (depth != null && depth.getId() == 0L) {
            return false;
        }
        return true;
    }

    /** Rebinds an external VulkanMod/Beryl framebuffer with {@code LOAD_OP_LOAD}. */
    public static void rebindExternal(Framebuffer externalFb) {
        current = null;
        if (externalFb == null) {
            externalFb = lastExternal;
        }
        if (externalFb == null) {
            return;
        }
        // VulkanMod's dynamic-rendering pass assumes a depth attachment and
        // dereferences it unconditionally. Beryl's composite targets can be
        // colour-only; rebinding one of those crashes in
        // RenderPass.beginDynamicRendering. Keep the previous target bound.
        if (externalFb.getDepthAttachment() == null) {
            return;
        }
        lastExternal = externalFb;
        Renderer renderer = rendererOrNull();
        if (renderer == null) {
            return;
        }
        VkVoxel.invalidateExternalPipelineCache();
        RenderPass rebind = externalRebindPasses.computeIfAbsent(externalFb, fb -> {
            RenderPass.Builder builder = RenderPass.builder(fb);
            if (builder.getColorAttachmentInfo() != null) {
                builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
            }
            if (builder.getDepthAttachmentInfo() != null) {
                builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
            }
            return builder.build();
        });
        renderer.beginRenderPass(rebind, externalFb);
    }

    /**
     * Ensures a dynamic rendering pass is active before a draw command is
     * recorded (re-entering the current target if a compute/transfer operation
     * temporarily ended the pass).
     */
    public static void ensureRenderPassActive() {
        Renderer renderer = rendererOrNull();
        if (renderer == null || !Renderer.isRecording() || renderer.getBoundRenderPass() != null) {
            return;
        }
        if (current != null) {
            current.bindRenderTarget();
        } else if (lastExternal != null) {
            rebindExternal(lastExternal);
        }
    }

    /** Marks that no LING framebuffer is bound (drawing goes to the main pass). */
    public static void unbindRenderTarget() {
        rebindExternal(lastExternal);
    }

    private static Renderer rendererOrNull() {
        try {
            return Renderer.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void free() {
        this.free0();
        Renderer renderer = rendererOrNull();
        if (renderer != null && (renderer.getBoundFramebuffer() == this.framebuffer || renderer.getBoundRenderPass() == this.renderPass)) {
            try {
                renderer.endRenderPass();
            } catch (Throwable ignored) {
            }
        }
        if (current == this) {
            current = null;
        }
        if (this.framebuffer != null) {
            this.framebuffer = null;
            this.renderPass = null;
        }
        if (this.dummyColor != null) {
            this.dummyColor.free();
            this.dummyColor = null;
        }
    }

    public VkFramebuffer name(String name) {
        return this;
    }

    // ------------------------------------------------------------------
    // Custom dynamic-rendering pass supporting MRT and stencil attachments
    // ------------------------------------------------------------------

    private static final class VoxelRenderPass extends RenderPass {
        private final VkFramebuffer owner;

        VoxelRenderPass(VkFramebuffer owner, Framebuffer fb) {
            super(fb, null, null);
            this.owner = owner;
        }

        @Override
        public void beginDynamicRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
            List<VkVoxelImage> colors = this.owner.activeColorImages();
            VkVoxelImage depth = this.owner.depthImage();

            for (VkVoxelImage color : colors) {
                color.transitionOn(commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
            if (depth != null) {
                depth.transitionOn(commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            Framebuffer fb = this.getFramebuffer();
            VkRect2D renderArea = VkRect2D.malloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(fb.getWidth(), fb.getHeight());

            VkClearValue.Buffer clearValues = VkClearValue.malloc(2, stack);
            clearValues.get(0).color().float32(VRenderSystem.clearColor);
            clearValues.get(1).depthStencil().set(1.0f, 0);

            int loadOp = this.owner.loadOpLoad ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_CLEAR;

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                    .sType(1000044000) // VK_STRUCTURE_TYPE_RENDERING_INFO
                    .renderArea(renderArea)
                    .layerCount(1);

            if (!colors.isEmpty()) {
                VkRenderingAttachmentInfo.Buffer colorInfos = VkRenderingAttachmentInfo.calloc(colors.size(), stack);
                for (int i = 0; i < colors.size(); i++) {
                    colorInfos.get(i)
                            .sType(1000044001) // VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO
                            .imageView(colors.get(i).image.getImageView())
                            .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(loadOp)
                            .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                            .clearValue(clearValues.get(0));
                }
                renderingInfo.pColorAttachments(colorInfos);
            }

            if (depth != null) {
                VkRenderingAttachmentInfo depthInfo = VkRenderingAttachmentInfo.calloc(stack)
                        .sType(1000044001)
                        .imageView(depth.image.getImageView())
                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .loadOp(loadOp)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .clearValue(clearValues.get(1));
                renderingInfo.pDepthAttachment(depthInfo);
                if ((depth.aspect() & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
                    renderingInfo.pStencilAttachment(depthInfo);
                }
            }

            KHRDynamicRendering.vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
        }

        @Override
        public void endRenderPass(VkCommandBuffer commandBuffer) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);
            for (VkVoxelImage color : this.owner.activeColorImages()) {
                color.transitionOn(commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            VkVoxelImage depth = this.owner.depthImage();
            if (depth != null) {
                depth.transitionOn(commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            Renderer renderer = rendererOrNull();
            if (renderer != null) {
                renderer.setBoundRenderPass(null);
            }
        }
    }
}
