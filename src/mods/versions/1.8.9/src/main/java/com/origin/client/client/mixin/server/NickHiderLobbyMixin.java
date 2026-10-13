package com.origin.client.client.mixin.server;

import com.origin.client.client.mods.NickHider;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider > Hide Lobby ID: each scoreboard sidebar line is built by the
// static ScorePlayerTeam.formatPlayerName(Team, String) -- team prefix + entry +
// suffix as one § String (1.8.9 GuiIngame.renderScoreboard, and Origin's own
// sidebar). Hypixel can split one line across prefix and suffix, so the check
// runs on the finished line: a "date serverID" line comes back as just the date.
// Every other line is returned untouched.
@Mixin(ScorePlayerTeam.class)
public abstract class NickHiderLobbyMixin {
	@Inject(method = "formatPlayerName", at = @At("RETURN"), cancellable = true)
	private static void originclient$hideLobbyId(Team team, String name, CallbackInfoReturnable<String> cir) {
		try {
			String line = NickHider.hideLobbyId(cir.getReturnValue());
			if (line != null) {
				cir.setReturnValue(line);
			}
		} catch (Throwable t) {
			// keep the original line
		}
	}
}
