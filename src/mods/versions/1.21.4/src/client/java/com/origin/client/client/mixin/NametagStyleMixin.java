package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;
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
 * billboard/positioning math (which the scale/toggle mixin in EntityNametagMixin
 * already wraps). Every option is read live and gated on the mod being on, so it
 * fails soft to vanilla the instant the mod is off.
 *
 * <p>PER-VERSION DELTA (1.21.4, covers 1.21.2-1.21.4): renderNameTag receives an
 * EntityRenderState, not the Entity (the shape EntityNametagMixin already hooks),
 * so "your own tag" is a PLAYER state whose name text is the local player's
 * display name -- the same text match 1.21.11 uses. Only another player literally
 * named like you could match, which is cosmetic and harmless.
 */
@Mixin(EntityRenderer.class)
public class NametagStyleMixin {

	@Redirect(method = "renderNameTag",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"))
	private int originclient$styleNameTag(Font font, Component text, float x, float y, int color, boolean shadow,
										  Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
										  int bgColor, int light,
										  // captured params of renderNameTag (the state + its name are what we need):
										  EntityRenderState state, Component displayName, PoseStack poseStack,
										  MultiBufferSource bufferSource, int packedLight) {
		// Friends & Party: a party member's tag in the party colour (LAN worlds only;
		// PartyWorld decides). Keeps vanilla's per-pass alpha like the colours below.
		// 1.21.2+: the hook gets a render state, so the player is looked up by the
		// state's entity id (PlayerRenderState.id), as LevelHeadMixin does.
		Integer party = null;
		try {
			Minecraft pmc = Minecraft.getInstance();
			if (state instanceof PlayerRenderState prs && pmc.level != null) {
				party = com.origin.client.client.social.PartyWorld.nametagTint(pmc.level.getEntity(prs.id));
			}
		} catch (Throwable ignored) {
			// no tint
		}
		if (party != null) {
			color = (color & 0xFF000000) | (party & 0xFFFFFF);
		}
		if (!Mods.on("nametags")) {
			return font.drawInBatch(text, x, y, color, shadow, matrix, buffer, mode, bgColor, light);
		}
		boolean self = state instanceof PlayerRenderState && originclient$isSelf(displayName);
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
		return font.drawInBatch(text, x, y, newColor, wantShadow, matrix, buffer, mode, newBg, light);
	}

	private static boolean originclient$isSelf(Component name) {
		try {
			Minecraft mc = Minecraft.getInstance();
			return mc.player != null && name != null
					&& mc.player.getDisplayName().getString().equals(name.getString());
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
