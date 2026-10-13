package com.origin.client.client.mixin.server;

import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Hypixel Bed Wars: the title half of Custom Trap Alert. Every hook asks
// HypixelBedwars first, which says no unless the mod is on and we're in a Bed
// Wars game on Hypixel -- everywhere else these are no-ops.
//
// 1.8.9: the title packet lands in GuiIngame.displayTitle(String title,
// String subTitle, int fadeIn, int stay, int fadeOut) -- § Strings, one call per
// packet setting either the title or the subtitle (the other is null).
// GuiIngameForge doesn't override it. Both arguments pass through HypixelBedwars
// at HEAD; `displayedSubTitle` is the field the title block draws from.
// (Hide Armor / Hide Food / Hardcore Hearts are Forge overlay events in
// ServerHooks on this version; Colored Beds has no 1.8.9 equivalent -- beds are
// plain chunk-baked blocks -- so there is no bed mixin.)
@Mixin(GuiIngame.class)
public class HypixelBedwarsGuiMixin {
	@Shadow
	protected String displayedSubTitle;

	@ModifyVariable(method = "displayTitle", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private String originclient$bwTrapTitle(String title) {
		if (title == null) {
			return null;
		}
		try {
			String out = HypixelBedwars.onTitle(title);
			if (out != null && !out.equals(title) && displayedSubTitle != null) {
				// Hypixel sends the subtitle first; recolour the one already waiting.
				displayedSubTitle = HypixelBedwars.onSubtitle(displayedSubTitle);
			}
			return out;
		} catch (Throwable t) {
			return title;
		}
	}

	@ModifyVariable(method = "displayTitle", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private String originclient$bwTrapSubtitle(String subtitle) {
		if (subtitle == null) {
			return null;
		}
		try {
			return HypixelBedwars.onSubtitle(subtitle);
		} catch (Throwable t) {
			return subtitle;
		}
	}
}
