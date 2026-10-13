package com.origin.client.client.mixin.hud;

import com.origin.client.client.hud.SidebarHud;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Scoreboard mod: the sidebar, repositioned to the "scoreboard" HUD element and
// rescaled around its own centre, plus Hide Scoreboard / Hide Numbers / colours.
// Mod off = vanilla untouched.
//
// 1.8.9: GuiIngame.renderScoreboard(ScoreObjective, ScaledResolution) is the one
// sidebar draw (GuiIngameForge calls the inherited method; no Forge event wraps
// it). 1.16.5 pushed a pose at HEAD + popped at RETURN and redirected the inline
// fills for Background Color; a @Redirect of the static Gui.drawRect call would
// have to name the bytecode owner (GuiIngame vs Gui) and an INVOKE point, the
// fragile kind of hook. The 1.8.9 sidebar is short and stable, so with the mod on
// SidebarHud draws the whole board itself (vanilla's own layout) and this HEAD
// injection cancels vanilla's -- which also makes Hide Numbers, Header Color,
// Text Shadow and Border real in-game (1.16.5 only previews them). A throw in
// SidebarHud leaves the call uncancelled, i.e. vanilla's board draws.
// priority 2000: Origin's scoreboard wins over other UI mods.
@Mixin(value = GuiIngame.class, priority = 2000)
public abstract class GuiIngameScoreboardMixin {

	@Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
	private void originclient$sidebar(ScoreObjective objective, ScaledResolution res, CallbackInfo ci) {
		if (!Mods.on("scoreboard")) {
			return;
		}
		try {
			if (SidebarHud.render(objective, res)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// vanilla's sidebar draws instead
		}
	}
}
