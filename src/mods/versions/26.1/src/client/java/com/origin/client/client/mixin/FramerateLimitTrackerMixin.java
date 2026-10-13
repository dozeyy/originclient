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
// Max Unfocused FPS whenever the window isn't active. We only ever lower the limit
// vanilla already computed, never raise it. (Pre-1.21.2 uses MinecraftFramerateMixin;
// this one is registered only in the render-pipeline modules.)
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
			limit = Math.min(limit, (int) Mods.num(Mods.PERFORMANCE_ID, "maxMainMenuFps"));
		}
		if (!mc.isWindowActive() && Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps")) {
			limit = Math.min(limit, (int) Mods.num(Mods.PERFORMANCE_ID, "maxUnfocusedFps"));
		}
		cir.setReturnValue(limit);
	}
}
