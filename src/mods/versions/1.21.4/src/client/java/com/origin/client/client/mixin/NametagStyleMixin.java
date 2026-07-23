package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Nametag STYLING for the Nametags mod: text shadow, opacity, and a custom
 * background + text colour. Vanilla draws every name tag with {@link Font#drawInBatch}
 * — twice (a faint SEE_THROUGH pass with a black background box, then, if not
 * sneaking, a solid NORMAL pass with no background). Redirecting that call lets us
 * restyle both passes without re-implementing the billboard/positioning math (the
 * scale/toggle mixin in EntityNametagMixin already wraps that). Every option is read
 * live and gated on the mod being on, so it fails soft to vanilla the instant the
 * mod is off.
 *
 * <p>PER-VERSION DELTA (1.21.4): 1.21.4 is the render-state era — {@code renderNameTag}
 * receives an {@code EntityRenderState}, NOT the live {@code Entity}, so the "override
 * YOUR OWN tag" sub-feature (which needs {@code entity == the local player}) is not
 * available here and its options are omitted from the 1.21.4 Nametags settings. The
 * global text-shadow / opacity / background / text-colour styling all still apply.
 * Because this redirect captures none of {@code renderNameTag}'s locals, it is
 * insensitive to that signature change.
 */
@Mixin(EntityRenderer.class)
public class NametagStyleMixin {

	@Redirect(method = "renderNameTag",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"))
	private int originclient$styleNameTag(Font font, Component text, float x, float y, int color, boolean shadow,
										  Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
										  int bgColor, int light) {
		if (!Mods.on("nametags")) {
			return font.drawInBatch(text, x, y, color, shadow, matrix, buffer, mode, bgColor, light);
		}
		double op = Mods.num("nametags", "opacity");
		if (op <= 0.0) {
			op = 1.0;
		}
		boolean wantShadow = Mods.bool("nametags", "textShadow");

		// Text colour: keep vanilla's per-pass ALPHA (that's what makes the two-pass
		// see-through/solid look), swap only the RGB when the custom colour is on.
		int rgb = color & 0xFFFFFF;
		if (Mods.bool("nametags", "overrideColor")) {
			rgb = Mods.color("nametags", "textColor") & 0xFFFFFF;
		}
		int newColor = scaleAlpha((color & 0xFF000000) | rgb, op);

		// Background box: only the pass that actually has one (alpha != 0). Replace it
		// with the chosen colour, then fade by the opacity slider.
		int newBg = bgColor;
		if (((bgColor >>> 24) & 0xFF) != 0) {
			newBg = scaleAlpha(Mods.color("nametags", "backgroundColor"), op);
		}
		return font.drawInBatch(text, x, y, newColor, wantShadow, matrix, buffer, mode, newBg, light);
	}

	private static int scaleAlpha(int argb, double f) {
		int a = (int) Math.round(((argb >>> 24) & 0xFF) * f);
		a = Math.max(0, Math.min(255, a));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
