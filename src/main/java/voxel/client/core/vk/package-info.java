/**
 * Vulkan facade wrappers used by the render pipeline (buffer, texture,
 * framebuffer, state, fence, debug) plus the shader loading layer under
 * {@code voxel.client.core.vk.shader}.
 *
 * <p>These classes were historically {@code Gl*} facades; they are now
 * Vulkan-backed. Write new code with {@code Vk*} semantic names and do not
 * reintroduce GL-isms. {@link voxel.client.core.vk.Capabilities} holds device
 * feature detection.</p>
 */
package voxel.client.core.vk;
