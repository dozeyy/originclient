package com.origin.client.client.mixin.world;

import com.origin.client.client.cosmetics.PlusBadge;
import com.origin.client.client.hooks.WorldHooks;
import com.origin.client.client.hooks.WorldLabel;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nametags mod + Hypixel Level Head + Friends & Party tint, on 1.8.9's one
 * label routine: Render.renderLivingLabel(entity, text, x, y, z, maxDistance)
 * draws every name tag (players, named mobs, armor stands) and the below-name
 * score line, as a billboard with a 25%-black backdrop, a faint see-through text
 * pass and a solid one. 1.16.5 split this over EntityNametagMixin (hide/scale),
 * NametagStyleMixin (Font.drawInBatch styling) and LevelHeadMixin; here one HEAD
 * hook decides:
 *   - hidden (Toggle All / Toggle Players / Hide in F1) -> cancel, nothing drawn;
 *   - nothing Origin-specific to apply -> vanilla draws, untouched;
 *   - otherwise -> cancel and draw the same label (vanilla's geometry and two
 *     passes, hooks/WorldLabel) with: Scale (about the entity's feet, so lines
 *     keep their spacing), text shadow, opacity, custom text/background colours
 *     and the own-tag overrides, the party colour, and the Level Head text --
 *     inline after the name ("Level Above Name" off) or as one more line above it,
 *     and the Origin+ badge in front of a member's name (cosmetics/PlusBadge).
 * The name line is told from the score line by its text (vanilla passes
 * getDisplayName().getFormattedText() for the name).
 *
 * The sneaking tag (drawn by RendererLivingEntity without this routine) only
 * honours the hide toggles (WorldHooks' RenderLivingEvent.Specials.Pre), as
 * vanilla draws it faint and unstyled -- so it carries no Origin+ badge either.
 */
@Mixin(Render.class)
public abstract class NametagLabelMixin {
	// One label line in world units: vanilla's FONT_HEIGHT * 1.15 * 0.02666667.
	private static final float originclient$LINE = 9.0F * 1.15F * WorldLabel.VANILLA_SCALE;
	private static boolean originclient$reentry = false;

	@Inject(method = "renderLivingLabel", at = @At("HEAD"), cancellable = true)
	private void originclient$label(Entity entity, String text, double x, double y, double z, int maxDistance,
									CallbackInfo ci) {
		if (originclient$reentry || entity == null || text == null) {
			return;
		}
		try {
			if (WorldHooks.nametagHidden(entity)) {
				ci.cancel();
				return;
			}
			RenderManager rm = ((Render<?>) (Object) this).getRenderManager();
			if (rm.livingPlayer != null && entity.getDistanceSqToEntity(rm.livingPlayer) > (double) (maxDistance * maxDistance)) {
				return;   // vanilla draws nothing past the range either
			}
			boolean modOn = Mods.on("nametags");
			Integer party = originclient$party(entity);
			// getFormattedText() builds a fresh String, and this runs for every visible
			// tag every frame (100+ players in a lobby). Only the two features that style
			// just the name line need to know it IS the name line, so it is worked out
			// only when one of them could apply -- an ordinary tag returns "pure vanilla"
			// below without allocating anything.
			boolean plusCandidate = PlusBadge.wantsNametag(entity);
			boolean levelCandidate = entity instanceof EntityPlayer && Mods.on("hypixel") && Mods.bool("hypixel", "levelHead");
			boolean nameLine = false;
			if (plusCandidate || levelCandidate) {
				nameLine = entity.getDisplayName() != null
						&& text.equals(entity.getDisplayName().getFormattedText());
			}
			String level = nameLine && levelCandidate ? originclient$level((EntityPlayer) entity) : null;
			// 1.8.9: the name line is the one 1.21.1 tells from the score line with PlusBadge.isScoreLine
			boolean plus = nameLine && plusCandidate;
			if (!modOn && party == null && level == null && !plus) {
				return;   // pure vanilla
			}
			boolean levelAbove = level != null && Mods.bool("hypixel", "levelAbove");
			String shown = level != null && !levelAbove ? text + " " + level : text;

			ci.cancel();
			originclient$reentry = true;
			try {
				originclient$draw(entity, shown, x, y, z, modOn, party, plus);
				if (levelAbove) {
					originclient$draw(entity, level, x, y + originclient$LINE, z, modOn, null, false);
				}
			} finally {
				originclient$reentry = false;
			}
		} catch (Throwable t) {
			// never take a frame down over a name tag
		}
	}

	/** vanilla's label, restyled (see the class doc). */
	private static void originclient$draw(Entity entity, String text, double x, double y, double z,
										  boolean modOn, Integer party, boolean plus) {
		Minecraft mc = Minecraft.getMinecraft();
		FontRenderer font = mc.fontRendererObj;
		boolean self = entity == mc.thePlayer;
		boolean over = modOn && self && Mods.bool("nametags", "ownOverride");
		double op = modOn ? Mods.num("nametags", "opacity") : 1.0;
		if (op <= 0.0) {
			op = 1.0;
		}
		boolean shadow = modOn && Mods.bool("nametags", "textShadow");
		float s = 1f;
		if (modOn) {
			// No "scale" option on some schemas: num() returns 0 -- guard it to 1 so
			// the tag never collapses to nothing (the "can't see my nametag" bug).
			s = (float) Mods.num("nametags", "scale");
			if (s <= 0f) {
				s = 1f;
			}
		}

		// Text colour: the formatted name keeps its own colour codes unless a colour
		// overrides it. Priority: your own override (if self) -> the party colour ->
		// the global custom text colour -> vanilla/team colours.
		Integer rgb = null;
		if (over) {
			rgb = Mods.color("nametags", "ownTextColor") & 0xFFFFFF;
		} else if (party != null) {
			rgb = party & 0xFFFFFF;
		} else if (modOn && Mods.bool("nametags", "overrideColor")) {
			rgb = Mods.color("nametags", "textColor") & 0xFFFFFF;
		}
		String drawn = rgb != null ? EnumChatFormatting.getTextWithoutFormattingCodes(text) : text;
		int base = rgb != null ? rgb : 0xFFFFFF;
		int ghost = originclient$alpha(0x20000000 | base, op);   // vanilla's 553648127 = 0x20FFFFFF
		int solid = originclient$alpha(0xFF000000 | base, op);
		int bg = 0x40000000;   // vanilla's 25% black
		if (modOn) {
			bg = originclient$alpha(over ? Mods.color("nametags", "ownBackgroundColor") : Mods.color("nametags", "backgroundColor"), op);
		}

		// Scale about the entity's feet: the line offset (score line / Level Head)
		// scales with the text, so stacked lines keep their spacing.
		RenderManager rm = mc.getRenderManager();
		float pt = WorldHooks.partialTicks();
		double feetY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * pt - rm.viewerPosY;
		double lift = (y - feetY) + entity.height + 0.5;
		int yOff = "deadmau5".equals(text) ? -10 : 0;   // vanilla's own special case
		// Origin+ badge: blank text in front of the name makes the centring and the
		// backdrop grow to hold it; the badge is then drawn over the blank in both passes.
		int padW = 0;
		if (plus) {
			String pad = PlusBadge.namePad(font);
			if (pad != null) {
				padW = font.getStringWidth(pad);
				drawn = pad + drawn;
			}
		}
		WorldLabel.begin(x, feetY, z, s, lift, WorldLabel.VANILLA_SCALE, false);
		try {
			int half = font.getStringWidth(drawn) / 2;
			WorldLabel.backdrop(-half - 1, -1 + yOff, half + 1, 8 + yOff, bg);
			WorldLabel.text(drawn, -half, yOff, ghost, solid, shadow, true);
			if (padW > 0) {
				PlusBadge.nametag(-half, yOff, padW, ghost >>> 24, solid >>> 24, Math.sqrt(x * x + y * y + z * z),
						WorldLabel.VANILLA_SCALE * s);
			}
		} finally {
			WorldLabel.end();
		}
	}

	private static Integer originclient$party(Entity entity) {
		try {
			Object t = com.origin.client.client.social.PartyWorld.nametagTint(entity);
			return t instanceof Integer ? (Integer) t : null;
		} catch (Throwable ignored) {
			return null;   // no tint
		}
	}

	/** Hypixel Level Head text for this player, or null (mod off, no key, no stats yet). */
	private static String originclient$level(EntityPlayer player) {
		try {
			if (!Mods.on("hypixel") || !Mods.bool("hypixel", "levelHead")) {
				return null;
			}
			Object level = com.origin.client.client.mods.Hypixel.levelHeadText(player.getUniqueID());
			if (level instanceof IChatComponent) {
				return ((IChatComponent) level).getFormattedText();
			}
			return level == null ? null : String.valueOf(level);
		} catch (Throwable t) {
			return null;
		}
	}

	private static int originclient$alpha(int argb, double f) {
		int a = (int) Math.round(((argb >>> 24) & 0xFF) * f);
		a = Math.max(0, Math.min(255, a));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
