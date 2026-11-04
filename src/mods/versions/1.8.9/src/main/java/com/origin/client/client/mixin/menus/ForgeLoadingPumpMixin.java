package com.origin.client.client.mixin.menus;

import com.origin.client.client.render.StartupLoading;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: FMLClientHandler (Forge 11.15.1.2318).
//  - processWindowMessages HEAD: ProgressManager.push, pop and ProgressBar.step all end by
//    calling it (javap 2026-11-04), so it runs at every loading step of the startup:
//    construction, pre-init, init, post-init, the texture/model reload. Origin's startup
//    screen redraws there (StartupLoading.pump: only during the startup, ~30 fps at most,
//    never inside a texture step). Display.update in that frame also pumps the window's
//    messages, the job this method does for FML's splash.
//  - onInitializationComplete HEAD: startGame calls it right after the title screen is set;
//    the startup screen's last frame (bar full) is drawn and it hands over to the title.
// Forge's own class (never obfuscated, not touched by OptiFine): remap = false, HEAD only. If
// this stops applying, the startup screen stays on its first frame until the title.
@Mixin(value = FMLClientHandler.class, remap = false)
public abstract class ForgeLoadingPumpMixin {
	@Inject(method = "processWindowMessages()V", at = @At("HEAD"), remap = false)
	private void originclient$startupFrame(CallbackInfo ci) {
		try {
			StartupLoading.pump();
		} catch (Throwable t) {
			// no redraw this step
		}
	}

	@Inject(method = "onInitializationComplete()V", at = @At("HEAD"), remap = false)
	private void originclient$startupDone(CallbackInfo ci) {
		try {
			StartupLoading.finish();
		} catch (Throwable t) {
			// the title's first frame replaces the picture anyway
		}
	}
}
