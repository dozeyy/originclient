package com.origin.client.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Motion Blur drives vanilla's own post-effect pipeline; both entry points
// are private in 1.21.11 (vanilla only calls them from spectator/keybind
// paths), so invokers it is.
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("setPostEffect")
	void originclient$setPostEffect(Identifier effect);

	@Invoker("clearPostEffect")
	void originclient$clearPostEffect();

	/**
	 * The EFFECTIVE vertical fov in degrees — the exact number vanilla builds
	 * this frame's projection from, with zoom and every other modifier already
	 * applied. Private in 1.21.11, and WaypointHud needs it to project world
	 * positions to the screen itself.
	 *
	 * <p>This is an invoker rather than a value published from a RETURN inject
	 * on purpose: an inject leaves the reader depending on WHEN in the frame it
	 * last fired, and if it silently fails to apply the reader keeps a stale
	 * default (70) instead of the player's real fov — an error that is invisible
	 * at screen centre and grows toward the edges, i.e. exactly the "marker
	 * slides around as I look" bug. Calling it directly can't drift.
	 */
	@Invoker("getFov")
	float originclient$getFov(net.minecraft.client.Camera camera, float partialTick, boolean usePerspective);
}
