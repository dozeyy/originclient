package com.origin.client.client.mixin.hud;

import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Read-only access to the /title state the Titles mod draws itself (TitlesHud).
// 1.8.9: the fields are PROTECTED on GuiIngame and Forge's GuiIngameForge reads
// them in its own renderTitle; an @Accessor (refmap maps the MCP names to SRG at
// build time) beats reflection with hand-kept SRG names. The titles are plain
// formatted Strings on 1.8.9 (no Component), "" when none was sent.
// GuiIngameForge extends GuiIngame, so `(GuiIngameTitleAccessor) (Object) gui`
// works on the HUD object players actually have; callers guard with instanceof
// so an accessor that failed to apply = Titles mod off, never a crash.
@Mixin(GuiIngame.class)
public interface GuiIngameTitleAccessor {

	@Accessor("titlesTimer")
	int originclient$getTitlesTimer();

	@Accessor("displayedTitle")
	String originclient$getDisplayedTitle();

	@Accessor("displayedSubTitle")
	String originclient$getDisplayedSubTitle();

	@Accessor("titleFadeIn")
	int originclient$getTitleFadeIn();

	@Accessor("titleDisplayTime")
	int originclient$getTitleDisplayTime();

	@Accessor("titleFadeOut")
	int originclient$getTitleFadeOut();
}
