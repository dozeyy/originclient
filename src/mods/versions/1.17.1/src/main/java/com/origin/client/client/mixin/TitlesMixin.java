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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Titles mod: with the mod on, Origin draws the /title title + subtitle itself
// (TitlesHud) at the "titles" HUD element's position, scale and colours, and
// vanilla's draw is skipped. Mod off = vanilla untouched.
//
// 1.17.1 has no Gui.renderTitle (that layer is 1.20.5+): the title block sits
// inline in Gui.render(PoseStack, float), guarded by
// `if (this.title != null && this.titleTime > 0)` -- the FIRST read of `title`
// in render (decompiled 1.16.4 and 1.18.1 agree, so 1.17.1 does too). So:
//  - that one read answers null while the mod is on -> vanilla skips its block;
//  - Origin's titles draw just before the next thing vanilla draws after that
//    block, the subtitle overlay -- the same place in the HUD's z-order.
// The fade maths is a verbatim copy of vanilla's, so titles fade exactly as before.
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

	@Redirect(method = "render",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Gui;title:Lnet/minecraft/network/chat/Component;",
					opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 0))
	private Component originclient$hideVanillaTitle(Gui self) {
		return Mods.on("titles") ? null : title;
	}

	@Inject(method = "render",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/SubtitleOverlay;render(Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void originclient$titles(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		if (!Mods.on("titles") || title == null || titleTime <= 0) {
			return;
		}
		try {
			float t = (float) titleTime - partialTick;
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
				TitlesHud.render(new Gfx(poseStack), title, subtitle, alpha);
			}
		} catch (Throwable t) {
			// the title is simply not shown this frame
		}
	}
}
