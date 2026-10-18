package com.origin.client.client.mixin;

import com.origin.client.client.cosmetics.PlusBadge;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Nametag STYLING for the Nametags mod: text shadow, opacity, a custom background
 * colour, and independent overrides for your OWN tag. Vanilla draws every name tag
 * with {@link Font#drawInBatch} -- twice (a faint SEE_THROUGH pass with a black
 * background box, then, if not sneaking, a solid NORMAL pass with no background).
 * Redirecting that call lets us restyle both passes without re-implementing the
 * billboard/positioning math (which EntityNametagMixin's scale/toggle hooks already
 * wrap). Every option is read live and gated on the mod being on, so it fails soft
 * to vanilla the instant the mod is off.
 *
 * <p>PER-VERSION DELTA (1.21.6-1.21.8):
 * <ul>
 * <li>renderNameTag takes an EntityRenderState, not the live Entity (1.21.2+; see
 *     EntityNametagMixin), so "your own tag" is matched by comparing the rendered
 *     text with the local player's display name -- the 1.21.11 module's rule. Exact
 *     in every normal case; another entity literally renamed to your username would
 *     be restyled too, which is cosmetic and harmless.</li>
 * <li>Font.drawInBatch(Component, ...) returns VOID from the 1.21.6 PreparedText
 *     rework (the 1.21.11 module's redirect target ends in ")V"); it returned int
 *     through 1.21.5. REASONED, not read off this jar -- so BOTH shapes are
 *     redirected. Exactly one exists in the jar; the other finds no target and is
 *     skipped ("defaultRequire": 0). The int-shaped handler returns 0 because
 *     renderNameTag discards the value.</li>
 * <li>Friends &amp; Party tint: both handlers also capture renderNameTag's own
 *     parameters (as 1.21.1 does); the party member is found from the
 *     PlayerRenderState's entity id through the client level (LevelHeadMixin's
 *     lookup), since the state carries no Entity.</li>
 * </ul>
 */
@Mixin(EntityRenderer.class)
public class NametagStyleMixin {

	@Redirect(method = "renderNameTag",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"))
	private void originclient$styleNameTag(Font font, Component text, float x, float y, int color, boolean shadow,
										   Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
										   int bgColor, int light,
										   // captured params of renderNameTag (the state names the entity):
										   EntityRenderState state, Component displayName, PoseStack poseStack,
										   MultiBufferSource bufferSource, int packedLight) {
		originclient$draw(font, text, x, y, color, shadow, matrix, buffer, mode, bgColor, light, state, displayName);
	}

	@Redirect(method = "renderNameTag",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"))
	private int originclient$styleNameTagInt(Font font, Component text, float x, float y, int color, boolean shadow,
											 Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
											 int bgColor, int light,
											 EntityRenderState state, Component displayName, PoseStack poseStack,
											 MultiBufferSource bufferSource, int packedLight) {
		originclient$draw(font, text, x, y, color, shadow, matrix, buffer, mode, bgColor, light, state, displayName);
		return 0;
	}

	private static void originclient$draw(Font font, Component text, float x, float y, int color, boolean shadow,
										  Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
										  int bgColor, int light, EntityRenderState state,
										  Component displayName) {
		// Friends & Party: a party member's tag in the party colour (LAN worlds only;
		// PartyWorld decides). Keeps vanilla's per-pass alpha like the colours below.
		Integer party = null;
		Entity entity = null;   // the player behind a PlayerRenderState (party tint + Origin+ badge)
		try {
			entity = originclient$entity(state);
			party = com.origin.client.client.social.PartyWorld.nametagTint(entity);
		} catch (Throwable ignored) {
			// no tint
		}
		if (party != null) {
			color = (color & 0xFF000000) | (party & 0xFFFFFF);
		}
		// "your own tag" is matched on the name text, so it is read before the badge pad goes in.
		boolean self = originclient$isSelf(text);
		// Origin+ badge: blank text in front of the name makes vanilla's own centring and
		// background box grow to hold it; the badge is then drawn over the blank in each
		// pass, with that pass's alpha/light/mode (so sneaking + see-through match the name).
		// 1.21.2+: `entity` is the state's player looked up above (null = no badge).
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
			font.drawInBatch(text, x, y, color, shadow, matrix, buffer, mode, bgColor, light);
			if (padW > 0f) {
				PlusBadge.nametag(matrix, buffer, mode, x, y, padW, color >>> 24, light);
			}
			return;
		}
		boolean over = self && Mods.bool("nametags", "ownOverride");
		double op = Mods.num("nametags", "opacity");
		if (op <= 0.0) {
			op = 1.0;
		}
		boolean wantShadow = Mods.bool("nametags", "textShadow");

		// Text colour: keep vanilla's per-pass ALPHA (that's what makes the two-pass
		// see-through/solid look), swap only the RGB. Priority: your own override (if
		// self) -> the global custom text colour -> vanilla/team colour.
		int rgb = color & 0xFFFFFF;
		if (over) {
			rgb = Mods.color("nametags", "ownTextColor") & 0xFFFFFF;
		} else if (party != null) {
			// the party colour wins over the global custom colour
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
		if (padW > 0f) {
			PlusBadge.nametag(matrix, buffer, mode, x, y, padW, newColor >>> 24, light);
		}
	}

	/** The player a render state was extracted from (null for anything else). */
	private static Entity originclient$entity(EntityRenderState state) {
		Minecraft mc = Minecraft.getInstance();
		if (!(state instanceof PlayerRenderState ps) || mc.level == null) {
			return null;
		}
		return mc.level.getEntity(ps.id);
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
