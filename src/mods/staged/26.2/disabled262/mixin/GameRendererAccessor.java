package com.origin.client.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Motion Blur needs the same private post-chain loader vanilla uses for the
// creeper/spider spectator shaders.
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("loadEffect")
	void originclient$loadEffect(ResourceLocation shader);
}
