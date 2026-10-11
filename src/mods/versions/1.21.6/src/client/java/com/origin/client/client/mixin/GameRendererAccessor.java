package com.origin.client.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Motion Blur drives vanilla's own post-effect pipeline; both entry points
// are private in 1.21.11 (vanilla only calls them from spectator/keybind
// paths), so invokers it is.
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("setPostEffect")
	void originclient$setPostEffect(ResourceLocation effect);

	@Invoker("clearPostEffect")
	void originclient$clearPostEffect();

	/**
	 * The EFFECTIVE vertical fov in degrees -- the exact number vanilla builds this
	 * frame's projection from, zoom included. Private here; WaypointHud needs it to
	 * project world positions to the screen itself, because this era no longer
	 * exposes the projection matrix through RenderSystem. Returns FLOAT from 1.21.2
	 * (GameRendererMixin's CallbackInfoReturnable<Float> on the same method).
	 * An invoker, not a value published from an inject, so it can never go stale
	 * (the 1.21.11 "marker slides as I look around" lesson).
	 */
	@Invoker("getFov")
	float originclient$getFov(net.minecraft.client.Camera camera, float partialTick, boolean usePerspective);
}
