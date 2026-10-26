package voxel.client.core.vk;

import voxel.client.core.vk.shader.Shader;

/**
 * Debug-name helper. GL object labels do not exist in Vulkan here (debug names
 * are applied inside VulkanMod); all methods are pass-throughs so call sites
 * keep compiling.
 */
public class VkDebug {
    public static final boolean GL_DEBUG = System.getProperty("voxel.VkDebug", "false").equals("true");

    public static void push() {
    }

    public static VkBuffer name(String name, VkBuffer buffer) {
        return buffer;
    }

    public static <T extends Shader> T name(String name, T shader) {
        return shader;
    }

    public static VkFramebuffer name(String name, VkFramebuffer framebuffer) {
        return framebuffer;
    }

    public static VkTexture name(String name, VkTexture texture) {
        return texture;
    }

    public static VkPersistentMappedBuffer name(String name, VkPersistentMappedBuffer buffer) {
        return buffer;
    }
}
