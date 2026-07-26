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

// Scoreboard mod: read-only re-render — repositions and rescales the whole
// sidebar around its right-center anchor. Never touches scoreboard data. push at
// HEAD / pop at every RETURN so early exits can't unbalance the pose stack.
// priority 2000: Origin's scoreboard rescale wins over other UI mods.
//
// FIXED 2026-07-26: this used to scale by Mods.num("scoreboard", "scale") — an
// option that does not exist on this card. Mods.num() returns 0 for an unknown
// key, so the sidebar was scaled to ZERO and vanished entirely the moment the
// Scoreboard mod was switched on. Placement now comes from the scoreboard HUD
// element's HudPos, matching 1.21.1, so dragging the sample in the HUD editor
// moves the real sidebar.
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
			// Reposition the real sidebar to the scoreboard element's HudPos (drag it
			// in the HUD editor). Translate from vanilla's default spot to the target;
			// the sample size is the shared reference for both the editor box and this.
			com.origin.client.client.hud.HudPos p = com.origin.client.client.hud.HudElements.scoreboardPos();
			int[] sz = com.origin.client.client.hud.HudElements.sampleScoreboardSize();
			int sw = guiGraphics.guiWidth(), sh = guiGraphics.guiHeight();
			double tx = p.x(sw, sz[0]), ty = p.y(sh, sz[1]);
			double vx = sw - sz[0] - 3, vy = (sh - sz[1]) / 2.0;
			pose.translate((float) (tx - vx), (float) (ty - vy));
			float s = (float) p.scale;
			if (s > 0.05f) {
				float ax = guiGraphics.guiWidth(), ay = guiGraphics.guiHeight() / 2f;
				pose.translate(ax, ay);
				pose.scale(s, s);
				pose.translate(-ax, -ay);
			}
		}
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
	private void originclient$scaleSidebarPop(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
		guiGraphics.pose().popMatrix();
	}

	// Background Color: vanilla fills the sidebar backgrounds itself, so the
	// scoreboard's bgColor was a dead setting. Recolour every background fill in
	// the sidebar to the mod's bgColor when it's on, so it behaves like every
	// other HUD's Background Color. require = 0: if a future mapping renames or
	// relocates the fill, this silently no-ops instead of crashing.
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
