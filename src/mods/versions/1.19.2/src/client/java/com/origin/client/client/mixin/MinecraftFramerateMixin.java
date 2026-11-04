package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// SETTINGS > Performance FPS caps. getFramerateLimit() is the single value the
// render loop throttles against, so its return value applies both caps centrally:
// Max Main Menu FPS while no world is loaded, and Max Unfocused FPS whenever the
// window isn't the active one.
//
// Max Main Menu FPS REPLACES vanilla's menu cap instead of only lowering it (G121-8,
// ported from 1.21.1 2026-11-04): getFramerateLimit() returns a literal 60 whenever
// level == null and a screen or overlay is up (the same body since 1.14), so a slider
// that could only lower that limit did nothing above 60. On a menu screen (no world, no
// loading overlay) the limit is now the player's own Max Framerate, capped by the slider.
// The loading overlay keeps vanilla's 60. Every other case is only ever lowered.
@Mixin(Minecraft.class)
public abstract class MinecraftFramerateMixin {
	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void originclient$fpsCaps(CallbackInfoReturnable<Integer> cir) {
		Minecraft mc = (Minecraft) (Object) this;
		int limit = cir.getReturnValueI();

		if (mc.level == null) {
			int menuCap = (int) Mods.num(Mods.PERFORMANCE_ID, "maxMainMenuFps");
			if (mc.screen != null && mc.getOverlay() == null) {
				limit = Math.min(mc.getWindow().getFramerateLimit(), menuCap);   // lifts vanilla's 60
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
