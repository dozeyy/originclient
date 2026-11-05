package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.cosmetics.PlusBadge;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Nametag STYLING for the Nametags mod: text shadow, opacity, a custom background
 * colour, and independent overrides for your OWN tag. Vanilla draws every name tag
 * with {@link Font#drawInBatch} — twice (a faint SEE_THROUGH pass with a black
 * background box, then, if not sneaking, a solid NORMAL pass with no background).
 * Redirecting that call lets us restyle both passes without re-implementing the
 * billboard/positioning math (which the scale/toggle mixin in EntityNametagMixin
 * already wraps). Every option is read live and gated on the mod being on, so it
 * fails soft to vanilla the instant the mod is off.
 */
@Mixin(EntityRenderer.class)
public class NametagStyleMixin {

	@Redirect(method = "renderNameTag",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"))
	private int originclient$styleNameTag(Font font, Component text, float x, float y, int color, boolean shadow,
										  Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
										  int bgColor, int light,
										  // captured params of renderNameTag (entity is the one we need):
										  Entity entity, Component displayName, PoseStack poseStack,
										  MultiBufferSource bufferSource, int packedLight) {
		// Friends & Party: a party member's tag in the party colour (LAN worlds only;
		// PartyWorld decides). Keeps vanilla's per-pass alpha like the colours below.
		Integer party = null;
		try {
			party = com.origin.client.client.social.PartyWorld.nametagTint(entity);
		} catch (Throwable ignored) {
			// no tint
		}
		if (party != null) {
			color = (color & 0xFF000000) | (party & 0xFFFFFF);
		}
		// Origin+ badge: blank text in front of the name makes vanilla's own centring and
		// background box grow to hold it; the badge is then drawn over the blank in each
		// pass, with that pass's alpha/light/mode (so sneaking + see-through match the name).
		float padW = 0f;
		try {
			if (PlusBadge.wantsNametag(entity) && !PlusBadge.isScoreLine(entity, displayName)) {
				String pad = PlusBadge.namePad(font);
				if (pad != null) {
					padW = font.width(pad);
					text = Component.literal(pad).append(text);
					x -= padW / 2f;
				}
			}
		} catch (Throwable ignored) {
			padW = 0f;
		}
		if (!Mods.on("nametags")) {
			int w = font.drawInBatch(text, x, y, color, shadow, matrix, buffer, mode, bgColor, light);
			if (padW > 0f) {
				PlusBadge.nametag(matrix, buffer, mode == Font.DisplayMode.SEE_THROUGH, x, y, padW, color >>> 24, light);
			}
			return w;
		}
		boolean self = entity == Minecraft.getInstance().player;
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
		} else if (party != null) {
			// the party colour wins over the global custom colour
		} else if (Mods.bool("nametags", "overrideColor")) {
			rgb = Mods.color("nametags", "textColor") & 0xFFFFFF;
		}
		int newColor = scaleAlpha((color & 0xFF000000) | rgb, op);

		// Background box: only the pass that actually has one (alpha != 0). Replace it
		// with the chosen colour (own vs global), then fade by the opacity slider.
		int newBg = 0;
		if (((bgColor >>> 24) & 0xFF) != 0) {
			int bg = over ? Mods.color("nametags", "ownBackgroundColor") : Mods.color("nametags", "backgroundColor");
			newBg = scaleAlpha(bg, op);
		}
		// The box is drawn here, ONCE, before the glyphs, never by Font (JP-1 on 1.20+, LV-2 here):
		// - this era's Font runs its text pass twice with Text Shadow on (shadow, then glyphs) and
		//   each run adds the background, so the box drew twice (40% black became 64%).
		// - Iris (even with no shader pack) batches entity rendering and draws every no-depth-test
		//   layer (the see-through text and its box) AFTER the depth-tested text, so the see-through
		//   box landed ON TOP of the white name: a dark grey smudge. Under that batching the box
		//   moves into the solid pass instead (depth-tested, behind the glyphs). Cost: behind a
		//   wall the faint name shows without its box.
		boolean see = mode == Font.DisplayMode.SEE_THROUGH;
		boolean late = originclient$seeThroughDrawnLast(buffer);
		if (see) {
			originclient$carriedBg = 0;
			if (late) {
				originclient$carriedBg = newBg;
				newBg = 0;
			}
		} else if (newBg == 0 && originclient$carriedBg != 0) {
			newBg = originclient$carriedBg;
			originclient$carriedBg = 0;
		}
		if (newBg != 0) {
			originclient$box(matrix, buffer, see, x, y, font.width(text), newBg, light);
		}
		// The see-through pass gets no shadow (vanilla never gives it one): a dark copy at the
		// faint pass's alpha only muddies the name when Iris draws it on top.
		boolean drawShadow = wantShadow && !see;
		int w = font.drawInBatch(text, x, y, newColor, drawShadow, matrix, buffer, mode, 0, light);
		if (padW > 0f) {
			PlusBadge.nametag(matrix, buffer, see, x, y, padW, newColor >>> 24, light);
		}
		return w;
	}

	// A see-through box held back from the faint pass for the solid pass that always follows
	// it (vanilla draws SEE_THROUGH then NORMAL for one tag, back to back, render thread only).
	private static int originclient$carriedBg;
	private static Class<?> originclient$lastBufferClass;
	private static boolean originclient$lastLate;
	private static ResourceLocation originclient$white;

	/** True when this buffer is Iris's batched entity buffer (its batchedentityrendering package), which orders
	 *  no-depth-test layers after depth-tested ones. Cached per buffer class: one compare a tag. */
	private static boolean originclient$seeThroughDrawnLast(MultiBufferSource buffer) {
		Class<?> c = buffer.getClass();
		if (c != originclient$lastBufferClass) {
			originclient$lastBufferClass = c;
			originclient$lastLate = c.getName().contains(".batchedentityrendering.");
		}
		return originclient$lastLate;
	}

	/** Font's own background box (x-1 .. x+width, y-1 .. y+9), 0.01 behind the shadow (z 0) and the
	 *  glyphs (+0.03; +z faces the camera here), so the text always wins the depth test whatever the
	 *  order. This era has no text-background layer: Font draws its box as a white glyph in the text
	 *  layer, so this draws Origin's own white texture in the same text / text-see-through layer
	 *  (the one PlusBadge uses), which every shader pack treats as text. */
	private static void originclient$box(Matrix4f m, MultiBufferSource buffer, boolean seeThrough, float x, float y,
							int width, int argb, int light) {
		if (originclient$white == null) {
			originclient$white = new ResourceLocation("originclient", "textures/cosmetics/white.png");
		}
		VertexConsumer vc = buffer.getBuffer(seeThrough
				? RenderType.textSeeThrough(originclient$white) : RenderType.text(originclient$white));
		int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF, a = argb >>> 24;
		float x0 = x - 1f, x1 = x + width, y0 = y + 9f, y1 = y - 1f, z = -0.01f;
		vc.vertex(m, x0, y0, z).color(r, g, b, a).uv(0.5f, 0.5f).uv2(light).endVertex();
		vc.vertex(m, x1, y0, z).color(r, g, b, a).uv(0.5f, 0.5f).uv2(light).endVertex();
		vc.vertex(m, x1, y1, z).color(r, g, b, a).uv(0.5f, 0.5f).uv2(light).endVertex();
		vc.vertex(m, x0, y1, z).color(r, g, b, a).uv(0.5f, 0.5f).uv2(light).endVertex();
	}

	private static int scaleAlpha(int argb, double f) {
		int a = (int) Math.round(((argb >>> 24) & 0xFF) * f);
		a = Math.max(0, Math.min(255, a));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
