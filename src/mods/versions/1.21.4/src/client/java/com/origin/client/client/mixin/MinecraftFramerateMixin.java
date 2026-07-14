package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// SETTINGS > Performance FPS caps. getFramerateLimit() is the single value the
// render loop throttles against, so clamping its return value applies both
// caps centrally: Max Main Menu FPS while no world is loaded, and Max Unfocused
// FPS whenever the window isn't the active one. We only ever lower the limit
// vanilla already computed, never raise it.
@Mixin(Minecraft.class)
public abstract class MinecraftFramerateMixin {
	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void originclient$fpsCaps(CallbackInfoReturnable<Integer> cir) {
		Minecraft mc = (Minecraft) (Object) this;
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
