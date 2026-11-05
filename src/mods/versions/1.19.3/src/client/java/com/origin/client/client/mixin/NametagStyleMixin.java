package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
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
					target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;ZII)I"))
	private int originclient$styleNameTag(Font font, Component text, float x, float y, int color, boolean shadow,
										  Matrix4f matrix, MultiBufferSource buffer, boolean seeThrough,
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
			int w = font.drawInBatch(text, x, y, color, shadow, matrix, buffer, seeThrough, bgColor, light);
			if (padW > 0f) {
				PlusBadge.nametag(matrix, buffer, seeThrough, x, y, padW, color >>> 24, light);
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
		// The box is drawn ONCE, by Font, before the glyphs (JP-1 on 1.20+, LV-2 here):
		// - this era's Font runs its text pass twice with Text Shadow on (shadow, then glyphs) and
		//   each run adds the background, so the box drew twice (40% black became 64%). With a
		//   shadow, the box gets a call of its own whose glyphs are invisible (below).
		// - Iris (even with no shader pack) batches entity rendering and draws every no-depth-test
		//   layer (the see-through text and its box) AFTER the depth-tested text, so the see-through
		//   box landed ON TOP of the white name: a dark grey smudge. Under that batching the box
		//   moves into the solid pass instead (depth-tested, behind the glyphs). Cost: behind a
		//   wall the faint name shows without its box.
		boolean see = seeThrough;
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
		// The see-through pass gets no shadow (vanilla never gives it one): a dark copy at the
		// faint pass's alpha only muddies the name when Iris draws it on top.
		boolean drawShadow = wantShadow && !see;
		if (newBg != 0 && drawShadow) {
			// Box only: Font's own box in its own layer, with glyphs at alpha 4/255 (Font keeps an
			// alpha of 4+; the text shader discards anything under 0.1, 1.16.5's alpha test leaves
			// them at 1.5%). Origin's own quad in a text layer here lost the glyphs under Iris
			// with no shader pack (CI dev-shots r7nt, 1.19.4), so the box stays Font's.
			font.drawInBatch(text, x, y, 0x04FFFFFF, false, matrix, buffer, seeThrough, newBg, light);
			newBg = 0;
		}
		int w = font.drawInBatch(text, x, y, newColor, drawShadow, matrix, buffer, seeThrough, newBg, light);
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


	private static int scaleAlpha(int argb, double f) {
		int a = (int) Math.round(((argb >>> 24) & 0xFF) * f);
		a = Math.max(0, Math.min(255, a));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
