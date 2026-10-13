package com.origin.client.client.mixin.world;

import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// 1.8.9's ShaderGroup has no setUniform: Motion Blur ("Amount") and Color
// Saturation (Saturation / Brightness / Contrast) reach the private pass list
// and set the uniform on each pass's ShaderManager themselves (1.16.5's
// PostChainAccessor). Callers check `instanceof` first, so a failed accessor
// costs the uniform update, never a ClassCastException.
@Mixin(ShaderGroup.class)
public interface ShaderGroupAccessor {
	@Accessor("listShaders")
	List<Shader> origin$listShaders();
}
