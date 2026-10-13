package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// SETTINGS > Performance FPS caps. 1.8.9's Minecraft.getLimitFramerate() is the
// single value the game loop syncs to (Display.sync) and isFramerateLimitBelowMax()
// tests, so adjusting its return value applies both caps centrally:
//   Max Main Menu FPS -- out of a world. Vanilla 1.8.9 pins every out-of-world
//     screen to 30 fps; Origin's animated menus get the slider's value instead
//     (never above the player's own Max Framerate).
//   Max Unfocused FPS -- whenever the window isn't the active one; only ever
//     lowers the limit.
@Mixin(Minecraft.class)
public abstract class MinecraftFramerateMixin {
	@Inject(method = "getLimitFramerate", at = @At("RETURN"), cancellable = true)
	private void originclient$fpsCaps(CallbackInfoReturnable<Integer> cir) {
		try {
			Minecraft mc = (Minecraft) (Object) this;
			int limit = cir.getReturnValueI();
			if (mc.theWorld == null && mc.gameSettings != null) {
				limit = Math.min(mc.gameSettings.limitFramerate, (int) Mods.num(Mods.PERFORMANCE_ID, "maxMainMenuFps"));
			}
			if (!Display.isActive() && Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps")) {
				limit = Math.min(limit, (int) Mods.num(Mods.PERFORMANCE_ID, "maxUnfocusedFps"));
			}
			cir.setReturnValue(Math.max(1, limit));
		} catch (Throwable ignored) {
			// vanilla's limit
		}
	}
}
