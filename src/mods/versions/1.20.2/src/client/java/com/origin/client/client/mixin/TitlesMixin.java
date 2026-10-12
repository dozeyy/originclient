package com.origin.client.client.mixin;

import com.origin.client.client.hud.TitlesHud;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Titles mod: with the mod on, Origin draws the /title title + subtitle itself
// (TitlesHud) at the "titles" HUD element's position, scale and colours, and
// vanilla's draw is skipped. Mod off = vanilla untouched.
// Pre-1.20.5 shape (source-verified against decompiled 1.20.1 / 1.20.2 / 1.20.4
// Gui): there is no renderTitle method -- Gui.render(GuiGraphics, float) draws
// the title inline inside `if (this.title != null && this.titleTime > 0)`, and
// that null test is the FIRST read of the `title` field in render(). This
// redirect answers that read: with the mod on it draws Origin's titles right
// there (same spot in the frame as vanilla's) and returns null, so vanilla's
// whole title block is skipped. The fade maths is a verbatim copy of vanilla's.
// Fail-soft: if Origin's draw throws, vanilla draws the title instead.
@Mixin(Gui.class)
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

	// The frame's GuiGraphics + partial tick, captured at render() HEAD for the
	// field redirect below (a field redirect sees only the field's owner).
	@Unique
	private GuiGraphics originclient$titleGraphics;
	@Unique
	private float originclient$titlePartial;

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("HEAD"), require = 0)
	private void originclient$captureFrame(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
		originclient$titleGraphics = guiGraphics;
		originclient$titlePartial = partialTick;
	}

	@Redirect(method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Gui;title:Lnet/minecraft/network/chat/Component;",
					opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 0),
			require = 0)
	private Component originclient$titles(Gui self) {
		GuiGraphics guiGraphics = originclient$titleGraphics;
		if (!Mods.on("titles") || guiGraphics == null || title == null || titleTime <= 0) {
			return title;
		}
		try {
			float t = (float) titleTime - originclient$titlePartial;
			int alpha = 255;
			if (titleTime > titleFadeOutTime + titleStayTime) {
				float in = (float) (titleFadeInTime + titleStayTime + titleFadeOutTime) - t;
				alpha = (int) (in * 255.0F / (float) titleFadeInTime);
			}
			if (titleTime <= titleFadeOutTime) {
				alpha = (int) (t * 255.0F / (float) titleFadeOutTime);
			}
			alpha = Mth.clamp(alpha, 0, 255);
			if (alpha > 8) {
				TitlesHud.render(guiGraphics, title, subtitle, alpha);
			}
			return null;   // vanilla's title block is skipped
		} catch (Throwable t) {
			return title;   // leave the title to vanilla this frame
		}
	}
}
