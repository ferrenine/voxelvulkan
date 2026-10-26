package voxel.client.core.model;

import voxel.client.core.vk.VkBuffer;
import voxel.client.core.vk.VkTexture;
import voxel.client.core.vk.shader.AutoBindingShader;
import voxel.client.core.util.BerylUtil;
import voxel.client.mixin.minecraft.AccessorTextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;

/**
 * Vulkan port note: the old GL code bound the model SSBOs and the model atlas
 * texture to global binding points. Under Vulkan those are descriptor set
 * bindings, so {@link #bind(AutoBindingShader, int, int, int)} writes them into
 * the shader that is about to be used. The atlas sampler settings are baked
 * into the {@link VkTexture} (Vulkan images own their samplers).
 */
public class ModelStore {
    public static final int MODEL_SIZE = 64;

    /** Same value as ModelFactory.MODEL_TEXTURE_SIZE (which lives in the as-yet unported bakery code). */
    public static final int MODEL_TEXTURE_SIZE = 16;

    final VkBuffer modelBuffer;
    final VkBuffer modelColourBuffer;
    final VkTexture textures;

    public ModelStore() {
        this.modelBuffer = new VkBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new VkBuffer(4 * (1<<16)).name("ModelColour");
        this.textures = new VkTexture()
                .store(GL_RGBA8, Integer.numberOfTrailingZeros(MODEL_TEXTURE_SIZE),
                        MODEL_TEXTURE_SIZE*3*256, MODEL_TEXTURE_SIZE*2*256)
                .name("ModelTextures");

        //The block atlas mip count is only read to configure the (now baked-in)
        //texture LOD range; keep the accessor call so the mixin stays exercised.
        int maxLod = Integer.numberOfTrailingZeros(MODEL_TEXTURE_SIZE) - 1;
        TextureAtlas blockAtlas = Minecraft.getInstance().getAtlasManager()
                .getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        maxLod = Math.min(maxLod, ((AccessorTextureAtlas) blockAtlas).voxel$getMipLevel());
        // GL_NEAREST_MIPMAP_LINEAR + GL_NEAREST (anisotropy disabled to prevent Nyquist Moire rings on distant flat plains)
        long sampler = net.vulkanmod.vulkan.texture.SamplerManager.getSampler(2, 2, 0, 0, 1, (float) maxLod, false, 0.0f, -1);
        this.textures.image().image.setSampler(sampler);
    }

    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.textures.free();
    }

    /** Writes the model data bindings into the given shader's descriptor set. */
    public void bind(AutoBindingShader shader, int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        shader.ssbo(modelBindingIndex, this.modelBuffer);
        shader.ssbo(colourBindingIndex, this.modelColourBuffer);
        // Beryl samples every VulkanMod RGBA8 texture (the block atlas included)
        // through an sRGB view while it renders (ImageDescriptorMixin), so its
        // terrain albedo is linear. Match that for the shader's Beryl branch,
        // which is selected by the same berylShaderEnabled() check.
        long view = BerylUtil.berylShaderEnabled() ? this.textures.srgbView() : 0L;
        shader.texture(textureBindingIndex, 0, this.textures, view);
    }

    public VkBuffer modelBuffer() {
        return this.modelBuffer;
    }

    public VkBuffer colourBuffer() {
        return this.modelColourBuffer;
    }

    public VkTexture textures() {
        return this.textures;
    }
}
