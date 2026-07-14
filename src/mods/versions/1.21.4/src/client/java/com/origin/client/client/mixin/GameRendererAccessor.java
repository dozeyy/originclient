package com.origin.client.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Motion Blur drives vanilla's own post-effect pipeline; both entry points
// are private (vanilla only calls them from spectator/keybind paths).
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("setPostEffect")
	void originclient$setPostEffect(ResourceLocation effect);

	@Invoker("clearPostEffect")
	void originclient$clearPostEffect();
}
