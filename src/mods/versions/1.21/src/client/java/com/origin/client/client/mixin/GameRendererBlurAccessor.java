package com.origin.client.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Menu Blur drives vanilla's own menu-blur post chain (GameRenderer.blurEffect,
// private, null if the shader failed to load) with its own radius instead of
// the Menu Background Blurriness option.
@Mixin(GameRenderer.class)
public interface GameRendererBlurAccessor {
	@Accessor("blurEffect")
	PostChain originclient$blurEffect();
}
