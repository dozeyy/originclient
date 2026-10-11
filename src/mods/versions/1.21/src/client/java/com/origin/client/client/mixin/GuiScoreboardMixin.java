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
		pose.pushPose();
		if (Mods.on("scoreboard")) {
			// Reposition the real sidebar to the scoreboard element's HudPos (drag it
			// in the HUD editor). Translate from vanilla's default spot to the target;
			// the sample size is the shared reference for both the editor box and this.
			com.origin.client.client.hud.HudPos p = com.origin.client.client.hud.HudElements.scoreboardPos();
			int[] sz = com.origin.client.client.hud.HudElements.sampleScoreboardSize();
			int sw = guiGraphics.guiWidth(), sh = guiGraphics.guiHeight();
			double tx = p.x(sw, sz[0]), ty = p.y(sh, sz[1]);
			double vx = sw - sz[0] - 3, vy = (sh - sz[1]) / 2.0;
			pose.translate(tx - vx, ty - vy, 0);
			float s = (float) p.scale;
			if (s > 0.05f) {
				// Pivot the scale about the board's OWN centre (in vanilla draw space),
				// not the screen's right edge — otherwise a board dragged away from the
				// right edge also slides sideways when scaled, landing off where the
				// HUD-editor preview showed it. (Bug-audit #4.)
				float ax = (float) (vx + sz[0] / 2.0), ay = (float) (vy + sz[1] / 2.0);
				pose.translate(ax, ay, 0);
				pose.scale(s, s, 1f);
				pose.translate(-ax, -ay, 0);
			}
		}
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
	private void originclient$scaleSidebarPop(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
		guiGraphics.pose().popPose();
	}

	// Background Color (#8, universal — Will 2026-07-21): vanilla fills the sidebar
	// backgrounds itself, so the scoreboard's bgColor was a dead setting.
	//
	// 1.20.3+ shape: the sidebar's fills sit inside a GuiGraphics.drawManaged(() -> ...)
	// LAMBDA, i.e. a separate synthetic method, so a @Redirect of fill() scoped to
	// displayScoreboardSidebar matches nothing (it silently no-op'd here -- the
	// 1.20/1.20.2 Redirect only works because their fills are inline). Instead the
	// flag below is raised for exactly the duration of that drawManaged call, and
	// GuiGraphicsFillMixin recolours fill(IIIII) while it is up. The sidebar's only
	// fill() calls are its two background bands, so nothing else is touched.
	@Inject(method = "displayScoreboardSidebar",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawManaged(Ljava/lang/Runnable;)V"))
	private void originclient$sidebarBgOn(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
		com.origin.client.client.hud.HudElements.sidebarBgActive = Mods.on("scoreboard");
	}

	@Inject(method = "displayScoreboardSidebar",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawManaged(Ljava/lang/Runnable;)V",
					shift = At.Shift.AFTER))
	private void originclient$sidebarBgOff(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
		com.origin.client.client.hud.HudElements.sidebarBgActive = false;
	}
}
