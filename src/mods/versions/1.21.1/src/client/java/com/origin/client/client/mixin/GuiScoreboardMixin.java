package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
		pose.pushPose();
		if (Mods.on("scoreboard")) {
			float s = (float) Mods.num("scoreboard", "scale");
			float px = guiGraphics.guiWidth();
			float py = guiGraphics.guiHeight() / 2f;
			pose.translate(px, py, 0);
			pose.scale(s, s, 1f);
			pose.translate(-px, -py, 0);
		}
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
	private void originclient$scaleSidebarPop(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
		guiGraphics.pose().popPose();
	}

	// Background Color (#8, universal — Will 2026-07-21): vanilla fills the sidebar
	// backgrounds itself, so the scoreboard's bgColor was a dead setting. Recolor
	// every background fill in the sidebar to the mod's bgColor when it's on, so it
	// behaves like every other HUD's Background Color. require = 0: if a future
	// mapping renames/relocates the fill, this silently no-ops instead of crashing.
	@Redirect(method = "displayScoreboardSidebar",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"),
			require = 0)
	private void originclient$scoreboardBg(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
		if (Mods.on("scoreboard")) {
			color = OriginColorPicker.liveColor("scoreboard", "bgColor");
		}
		g.fill(x1, y1, x2, y2, color);
	}
}
