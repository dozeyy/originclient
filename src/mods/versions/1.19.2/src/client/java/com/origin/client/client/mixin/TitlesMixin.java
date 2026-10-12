package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.hud.TitlesHud;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.Gui;
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
//
// Pre-1.20.5 there is no Gui.renderTitle: the title is drawn inline in
// Gui.render(PoseStack, float) behind `if (this.title != null && this.titleTime
// > 0)` -- the FIRST read of `title` in render (decompiled 1.19.4 / 1.19.3 /
// 1.19.1 / 1.18.1 Gui, all identical). So that one read is redirected: with the
// mod on, Origin draws the titles right there (same moment, same z-order as
// vanilla) and the read answers null, which skips vanilla's block. The pose and
// partial tick come from render's HEAD. The fade maths is a verbatim copy of
// vanilla's, so titles fade exactly as before. Fail-soft: if Origin's draw
// throws, the real title is returned and vanilla draws it.
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

	@Unique
	private PoseStack originclient$pose;
	@Unique
	private float originclient$partialTick;

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$captureFrame(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		originclient$pose = poseStack;
		originclient$partialTick = partialTick;
	}

	@Redirect(method = "render",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Gui;title:Lnet/minecraft/network/chat/Component;",
					opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 0))
	private Component originclient$titles(Gui self) {
		if (!Mods.on("titles") || originclient$pose == null) {
			return title;
		}
		if (title == null || titleTime <= 0) {
			return null;   // nothing to show -- same as vanilla
		}
		try {
			float t = (float) titleTime - originclient$partialTick;
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
				TitlesHud.render(new Gfx(originclient$pose), title, subtitle, alpha);
			}
			return null;
		} catch (Throwable t) {
			return title;   // leave the title to vanilla this frame
		}
	}
}
