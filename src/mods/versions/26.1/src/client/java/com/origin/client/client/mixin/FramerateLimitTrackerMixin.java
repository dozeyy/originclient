package com.origin.client.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// SETTINGS > Performance FPS caps for the 1.21.2+ render era. The limit value
// moved off Minecraft.getFramerateLimit() onto FramerateLimitTracker; clamping its
// return applies both caps centrally: Max Main Menu FPS while no world is loaded,
// Max Unfocused FPS whenever the window isn't active. (Pre-1.21.2 uses
// MinecraftFramerateMixin; this one is registered only in the render-pipeline modules.)
//
// Max Main Menu FPS REPLACES vanilla's menu cap instead of only lowering it (G121-8):
// getFramerateLimit() returns a literal 60 (OUT_OF_LEVEL_MENU) whenever level == null
// and a screen or overlay is up (javap 1.21.4 to 1.21.11, 2026-11-04; the 26.x tracker
// is the same class), so a slider that could only lower it did nothing above 60. When
// that 60 comes from a menu screen (no world, no loading overlay; iconified 10 and AFK
// 10/30 are never 60) the limit is the player's own Max Framerate, capped by the slider.
// Everything else is only lowered.
// The world-join screens (render/LoadingScreens: connecting, generating, receiving the
// world, progress, BMOD-4) keep vanilla's 60: more frames there would only slow the load.
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void originclient$fpsCaps(CallbackInfoReturnable<Integer> cir) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		int limit = cir.getReturnValueI();
		if (mc.level == null) {
			int menuCap = (int) Mods.num(Mods.PERFORMANCE_ID, "maxMainMenuFps");
			if (limit == 60 && mc.screen != null && mc.getOverlay() == null
					&& !com.origin.client.client.render.LoadingScreens.isLoading(mc.screen)) {
				limit = Math.min(mc.options.framerateLimit().get(), menuCap);   // lifts vanilla's 60
			} else {
				limit = Math.min(limit, menuCap);
			}
		}
		if (!mc.isWindowActive() && Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps")) {
			limit = Math.min(limit, (int) Mods.num(Mods.PERFORMANCE_ID, "maxUnfocusedFps"));
		}
		cir.setReturnValue(limit);
	}
}
