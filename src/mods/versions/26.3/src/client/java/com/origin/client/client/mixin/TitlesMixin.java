package com.origin.client.client.mixin;

import com.origin.client.client.hud.TitlesHud;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Titles mod: with the mod on, Origin draws the /title title + subtitle itself
// (TitlesHud) at the "titles" HUD element's position, scale and colours, and
// vanilla's draw is skipped. Called from vanilla's own title layer, so z-order
// is unchanged. Mod off = vanilla untouched.
// 1.21.11 shape (source-verified against decompiled 1.21.11 Gui; same as 1.21.1): Gui.renderTitle(GuiGraphicsExtractor, DeltaTracker)
// reads the private title/subtitle/titleTime/fade fields below; the fade maths
// here is a verbatim copy of vanilla's, so titles fade exactly as before.
// Fail-soft: if Origin's draw throws, vanilla draws the title instead.
@Mixin(Hud.class)
public class TitlesMixin {
	@Shadow
	private int titleTime;
	@Shadow
	private Component title;
	@Shadow
	private Component subtitle;
	@Shadow
	private int titleFadeInTime;
	@Shadow
	private int titleStayTime;
	@Shadow
	private int titleFadeOutTime;

	@Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
	private void originclient$titles(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!Mods.on("titles")) {
			return;
		}
		if (title == null || titleTime <= 0) {
			ci.cancel();   // nothing to show -- same as vanilla
			return;
		}
		try {
			float t = (float) titleTime - deltaTracker.getGameTimeDeltaPartialTick(false);
			int alpha = 255;
			if (titleTime > titleFadeOutTime + titleStayTime) {
				float in = (float) (titleFadeInTime + titleStayTime + titleFadeOutTime) - t;
				alpha = (int) (in * 255.0F / (float) titleFadeInTime);
			}
			if (titleTime <= titleFadeOutTime) {
				alpha = (int) (t * 255.0F / (float) titleFadeOutTime);
			}
			alpha = Mth.clamp(alpha, 0, 255);
			if (alpha > 0) {
				guiGraphics.nextStratum();   // vanilla opens its own stratum for the title
				TitlesHud.render(guiGraphics, title, subtitle, alpha);
			}
			ci.cancel();
		} catch (Throwable t) {
			// leave the title to vanilla this frame
		}
	}
}
