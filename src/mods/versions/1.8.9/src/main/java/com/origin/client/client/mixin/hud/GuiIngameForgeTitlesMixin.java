package com.origin.client.client.mixin.hud;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.hud.TitlesHud;
import com.origin.client.client.mods.Mods;
import net.minecraftforge.client.GuiIngameForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Titles mod: with the mod on, Origin draws the /title title + subtitle itself
// (TitlesHud) at the "titles" HUD element's position, scale and colours, and
// vanilla's draw is skipped. Mod off = vanilla untouched.
//
// 1.8.9: Forge replaces the whole HUD with GuiIngameForge, which draws the titles
// in its OWN method renderTitle(int width, int height, float partialTicks) -- the
// same z-order slot vanilla's inline block had (after the record overlay, before
// the scoreboard). No RenderGameOverlayEvent wraps it, so this is a mixin. Forge's
// classes are not obfuscated: remap = false on the mixin AND the injector. The
// title state lives on the vanilla superclass (protected fields), read through
// GuiIngameTitleAccessor -- a mixin can't @Shadow a superclass field.
// HEAD + cancel only (no INVOKE points), and OptiFine doesn't touch this class.
// The fade maths is a verbatim copy of Forge's, so titles fade exactly as before.
@Mixin(value = GuiIngameForge.class, remap = false)
public abstract class GuiIngameForgeTitlesMixin {

	@Inject(method = "renderTitle", at = @At("HEAD"), cancellable = true, remap = false)
	private void originclient$titles(int width, int height, float partialTicks, CallbackInfo ci) {
		if (!Mods.on("titles") || !((Object) this instanceof GuiIngameTitleAccessor)) {
			return;
		}
		try {
			GuiIngameTitleAccessor acc = (GuiIngameTitleAccessor) (Object) this;
			int timer = acc.originclient$getTitlesTimer();
			if (timer > 0) {
				int fadeIn = acc.originclient$getTitleFadeIn();
				int stay = acc.originclient$getTitleDisplayTime();
				int fadeOut = acc.originclient$getTitleFadeOut();
				float t = (float) timer - partialTicks;
				int alpha = 255;
				if (timer > fadeOut + stay) {
					float in = (float) (fadeIn + stay + fadeOut) - t;
					alpha = (int) (in * 255.0F / (float) fadeIn);
				}
				if (timer <= fadeOut) {
					alpha = (int) (t * 255.0F / (float) fadeOut);
				}
				alpha = Math.max(0, Math.min(255, alpha));
				if (alpha > 8) {
					TitlesHud.render(new Gfx(), acc.originclient$getDisplayedTitle(),
							acc.originclient$getDisplayedSubTitle(), alpha);
				}
			}
			ci.cancel();
		} catch (Throwable t) {
			// leave the titles to Forge's own draw this frame
		}
	}
}
