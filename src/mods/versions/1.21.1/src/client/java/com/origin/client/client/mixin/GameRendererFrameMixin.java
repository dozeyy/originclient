package com.origin.client.client.mixin;

import com.origin.client.client.mods.GlintColorizer;
import com.origin.client.client.mods.HitColor;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Once-a-frame texture upkeep for Hit Color and Glint Colorizer, on the render
// thread before anything of the frame is drawn, plus the frame timer
// (FrameStats: one nanoTime + array store). Both only re-upload when their
// colour actually changed, so a steady frame costs a couple of compares.
// `render` matched by name with a CallbackInfo-only handler (same as
// GameRendererMixin's zoom ease).
@Mixin(GameRenderer.class)
public class GameRendererFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$syncTextures(CallbackInfo ci) {
		HitColor.sync();
		GlintColorizer.sync();
		// frame-time ring for the FPS module's 1% low / frame time / graph
		com.origin.client.client.hud.FrameStats.frame(System.nanoTime());
		// background mode flag + hitch log (hud/FrameWatch)
		com.origin.client.client.hud.FrameWatch.onFrame(net.minecraft.client.Minecraft.getInstance());
		// Motion Blur's per-frame blend: it must use this frame's duration, not the tick's
		try {
			com.origin.client.client.mods.MotionBlur.frame(net.minecraft.client.Minecraft.getInstance());
		} catch (Throwable ignored) {
			// fail soft: the blur keeps its last strength
		}
	}
}
