package com.origin.client.client.mixin.menus;

import com.origin.client.client.render.StartupLoading;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: Minecraft.drawSplashScreen -- vanilla's Mojang screen. With FML's splash off,
// startGame reaches it through SplashProgress.drawVanillaScreen once the texture manager
// exists (javap 2026-11-04), in the middle of Origin's startup screen.
// Why: one of Origin's startup frames is drawn instead (StartupLoading), so the Mojang logo
// never flashes between them. vanilla stores the logo it loads in mojangLogo, and startGame
// later hands that to SplashProgress.clearVanillaResources to delete: a 1x1 stand-in is
// registered exactly as vanilla registers its logo (getDynamicTextureLocation("logo", ...)),
// so that cleanup runs on a real texture, as it always did.
// RISK (OptiFine replaces Minecraft): HEAD with cancel only. If this stops applying, the
// Mojang screen shows for one frame and Origin's frames continue right after it.
@Mixin(Minecraft.class)
public abstract class MinecraftSplashScreenMixin {
	@Shadow
	private ResourceLocation mojangLogo;

	@Inject(method = "drawSplashScreen", at = @At("HEAD"), cancellable = true)
	private void originclient$originStartupFrame(TextureManager textures, CallbackInfo ci) {
		try {
			if (StartupLoading.replaceMojangScreen()) {
				mojangLogo = textures.getDynamicTextureLocation("logo", new DynamicTexture(1, 1));
				ci.cancel();
			}
		} catch (Throwable t) {
			// vanilla's Mojang screen draws as before
		}
	}
}
