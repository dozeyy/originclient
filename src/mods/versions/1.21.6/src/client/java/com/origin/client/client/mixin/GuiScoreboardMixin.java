package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Scoreboard mod: read-only re-render — rescales the whole sidebar around
// its right-center anchor. Never touches scoreboard data. push at HEAD /
// pop at every RETURN so early exits can't unbalance the pose stack.
// priority 2000: Origin's scoreboard rescale wins over other UI mods.
@Mixin(value = Gui.class, priority = 2000)
public class GuiScoreboardMixin {

	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void originclient$scaleSidebarPush(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
		// Hide Scoreboard cancels BEFORE the push so the RETURN pop (which a
		// cancelled method never reaches) can't unbalance the pose stack.
		if (Mods.on("scoreboard") && Mods.bool("scoreboard", "hideScoreboard")) {
			ci.cancel();
			return;
		}
		var pose = guiGraphics.pose();
		pose.pushMatrix();
		if (Mods.on("scoreboard")) {
			float s = (float) Mods.num("scoreboard", "scale");
			float px = guiGraphics.guiWidth();
			float py = guiGraphics.guiHeight() / 2f;
			pose.translate(px, py);
			pose.scale(s, s);
			pose.translate(-px, -py);
		}
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
	private void originclient$scaleSidebarPop(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
		guiGraphics.pose().popMatrix();
	}
}
