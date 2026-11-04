package com.origin.client.client.mixin.menus;

import com.origin.client.client.render.ReloadOverlay;
import net.minecraftforge.fml.common.ProgressManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: Forge's ProgressManager.ProgressBar.step(String) -- the progress ticks Forge's
// model loader (and others) report from INSIDE one long reload listener.
// Why: one listener (models with OptiFine and a big pack) can run for seconds; a redraw
// here (ReloadOverlay.pump: only during a reload, ~30 fps at most, never inside a texture
// step) keeps the reload screen moving and the window answering Windows ("Not Responding").
// Forge's own class: never obfuscated, so remap = false. HEAD only. If this stops
// applying, the reload screen still updates between listeners (ReloadProgressMixin).
@Mixin(value = ProgressManager.ProgressBar.class, remap = false)
public abstract class ReloadPumpMixin {
	@Inject(method = "step(Ljava/lang/String;)V", at = @At("HEAD"), remap = false)
	private void originclient$pump(String message, CallbackInfo ci) {
		try {
			ReloadOverlay.pump();
		} catch (Throwable t) {
			// no redraw this step
		}
	}
}
