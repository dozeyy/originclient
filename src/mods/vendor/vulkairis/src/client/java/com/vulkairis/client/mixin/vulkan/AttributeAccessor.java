package com.vulkairis.client.mixin.vulkan;

import net.vulkanmod.vulkan.shader.converter.Attribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for package-private fields of VulkanMod's Attribute class.
 */
@Mixin(value = Attribute.class, remap = false)
public interface AttributeAccessor {
    @Accessor("location")
    int getLocation();
}
