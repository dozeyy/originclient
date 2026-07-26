package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Nametag STYLING for the Nametags mod: text shadow, opacity, a custom background
 * colour, and independent overrides for your OWN tag.
 *
 * <p>PER-VERSION DELTA (1.21.11): on 1.21.1 this redirected the two
 * {@link Font#drawInBatch} calls inside {@code EntityRenderer.renderNameTag} and
 * read the entity straight out of the captured parameters. Both of those are
 * gone here — name tags are now EXTRACTED into
 * {@code SubmitNodeStorage.NameTagSubmit} records during entity submission and
 * drawn later, in one batch, by {@link NameTagFeatureRenderer#render}. That
 * method still makes exactly the same pair of drawInBatch calls (a faint
 * SEE_THROUGH pass with a black background box, then a solid NORMAL pass with
 * none), so the redirect still works — it just lives here instead.
 *
 * <p>The one thing the new shape cannot give us is entity identity: a
 * NameTagSubmit carries only the rendered {@link Component}, not the entity it
 * came from. "Your own tag" is therefore matched by comparing the text against
 * the local player's display name. That is exact in every normal case and can
 * only ever mis-fire on another entity literally renamed to your own username —
 * in which case it restyles that tag too, which is cosmetic and harmless.
 *
 * <p>Every option is read live and gated on the mod being on, so it fails soft
 * to vanilla the instant the mod is off.
 */
@Mixin(NameTagFeatureRenderer.class)
public class NametagStyleMixin {

	@Redirect(method = "render",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"))
	private void originclient$styleNameTag(Font font, Component text, float x, float y, int color, boolean shadow,
										   Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
										   int bgColor, int light) {
		if (!Mods.on("nametags")) {
			font.drawInBatch(text, x, y, color, shadow, matrix, buffer, mode, bgColor, light);
			return;
		}
		boolean self = originclient$isSelf(text);
		boolean over = self && Mods.bool("nametags", "ownOverride");
		double op = Mods.num("nametags", "opacity");
		if (op <= 0.0) {
			op = 1.0;
		}
		boolean wantShadow = Mods.bool("nametags", "textShadow");

		// Text colour: keep vanilla's per-pass ALPHA (that's what makes the two-pass
		// see-through/solid look), swap only the RGB. Priority: your own override (if
		// self) → the global custom text colour → vanilla/team colour.
		int rgb = color & 0xFFFFFF;
		if (over) {
			rgb = Mods.color("nametags", "ownTextColor") & 0xFFFFFF;
		} else if (Mods.bool("nametags", "overrideColor")) {
			rgb = Mods.color("nametags", "textColor") & 0xFFFFFF;
		}
		int newColor = originclient$scaleAlpha((color & 0xFF000000) | rgb, op);

		// Background box: only the pass that actually has one (alpha != 0). Replace it
		// with the chosen colour (own vs global), then fade by the opacity slider.
		int newBg = bgColor;
		if (((bgColor >>> 24) & 0xFF) != 0) {
			int bg = over ? Mods.color("nametags", "ownBackgroundColor") : Mods.color("nametags", "backgroundColor");
			newBg = originclient$scaleAlpha(bg, op);
		}
		font.drawInBatch(text, x, y, newColor, wantShadow, matrix, buffer, mode, newBg, light);
	}

	private static boolean originclient$isSelf(Component text) {
		try {
			Minecraft mc = Minecraft.getInstance();
			return mc.player != null && mc.player.getDisplayName() != null
					&& mc.player.getDisplayName().getString().equals(text.getString());
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static int originclient$scaleAlpha(int argb, double f) {
		int a = (int) Math.round(((argb >>> 24) & 0xFF) * f);
		a = Math.max(0, Math.min(255, a));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
