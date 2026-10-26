package voxel.client.core.model.bakery;

import voxel.client.core.vk.VkFramebuffer;
import voxel.client.core.vk.VkTexture;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.vk.VkVoxel;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT1;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_R32UI;

public class VkViewCapture {
    private final int width;
    private final int height;
    private final VkTexture colourTex;
    private final VkTexture depthTex;
    private final VkTexture stencilTex;
    private final VkTexture metaTex;
    final VkFramebuffer framebuffer;
    private final AutoBindingShader copyOutShader;

    public VkViewCapture(int width, int height) {
        this.width = width;
        this.height = height;
        this.metaTex = new VkTexture().store(GL_R32UI, 1, width*3, height*2).name("ModelBakeryMetadata");
        this.colourTex = new VkTexture().store(GL_RGBA8, 1, width*3, height*2).name("ModelBakeryColour");
        this.depthTex = new VkTexture().store(GL_DEPTH24_STENCIL8, 1, width*3, height*2).name("ModelBakeryDepth");
        this.stencilTex = this.depthTex.createView();

        this.framebuffer = new VkFramebuffer()
                .bind(GL_COLOR_ATTACHMENT0, this.colourTex)
                .bind(GL_COLOR_ATTACHMENT1, this.metaTex)
                .setDrawBuffers(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1)
                .bind(GL_DEPTH_STENCIL_ATTACHMENT, this.depthTex)
                .verify()
                .name("ModelFramebuffer");

        this.copyOutShader = Shader.makeAuto()
                .define("WIDTH", width)
                .define("HEIGHT", height)
                .define("COLOUR_IN_BINDING", 0)
                .define("DEPTH_IN_BINDING", 1)
                .define("STENCIL_IN_BINDING", 2)
                .define("META_IN_BINDING", 3)
                .define("BUFFER_OUT_BINDING", 4)
                .add(ShaderType.COMPUTE, "voxel:bakery/bufferreorder.comp")
                .compile()
                .name("ModelBakeryOut")
                .texture("META_IN_BINDING", 0, this.metaTex)
                .texture("COLOUR_IN_BINDING", 0, this.colourTex)
                .texture("DEPTH_IN_BINDING", 0, this.depthTex)
                .texture("STENCIL_IN_BINDING", 0, this.stencilTex);
    }

    public void emitToStream(long buffer, int offset) {
        VkVoxel.ensureOutsideRenderPass();
        this.copyOutShader.ssboRaw(4, buffer, offset, (this.width*3L)*(this.height*2L)*4L*2);
        VkVoxel.fullBarrier();
        this.copyOutShader.dispatch(3, 2, 1);
        VkVoxel.fullBarrier();
    }

    public void clear() {
        this.colourTex.image().clearColorZero();
        this.metaTex.image().clearColorZero();
        this.depthTex.image().clearDepthStencil(1.0f, 0);
    }

    public VkTexture depthTex() {
        return this.depthTex;
    }

    public void free() {
        this.framebuffer.free();
        this.colourTex.free();
        this.stencilTex.free();
        this.depthTex.free();
        this.metaTex.free();
        this.copyOutShader.free();
    }
}
