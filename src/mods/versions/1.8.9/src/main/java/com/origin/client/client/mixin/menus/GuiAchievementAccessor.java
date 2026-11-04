package com.origin.client.client.mixin.menus;

import net.minecraft.client.gui.achievement.GuiAchievement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Target: GuiAchievement's private notificationTime (0 = no pop-up; set while an earned
// achievement or the permanent "Open your inventory" hint is up, 160x32 at the top right).
// Why: 1.8.9 has no toast stack, so Origin's own pop-ups (SocialToast, FpsCoachToast)
// start below it while it shows instead of drawing over it (1.21.1 stacks them all as
// vanilla toasts). Accessor, not reflection: the refmap carries the SRG name.
// GuiAchievement is not replaced by Forge or OptiFine. If it stops applying, the
// instanceof check in SocialToast.columnTop reads "no pop-up": today's overlap, no crash.
@Mixin(GuiAchievement.class)
public interface GuiAchievementAccessor {
	@Accessor("notificationTime")
	long originclient$notificationTime();
}
