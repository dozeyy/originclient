package com.origin.client.client.mixin.menus;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.achievement.GuiAchievement;
import net.minecraft.stats.Achievement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > General > Show Achievements (1.16.5's ToastComponentMixin). When
// off, drop the pop-up before it is queued so it never appears.
// 1.8.9: no toast system -- an earned achievement goes to
// GuiAchievement.displayAchievement. The other entry, displayUnformattedAchievement,
// is the "Open your inventory" tutorial hint (not an earned achievement), so it is
// left alone, as the other versions leave non-advancement toasts alone.
@Mixin(GuiAchievement.class)
public abstract class GuiAchievementMixin {

	@Inject(method = "displayAchievement", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressAchievements(Achievement achievement, CallbackInfo ci) {
		try {
			if (!Mods.bool(Mods.GENERAL_ID, "showAchievements")) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// the pop-up shows
		}
	}
}
