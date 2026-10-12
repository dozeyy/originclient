package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider > Hide Lobby ID: each scoreboard sidebar line is built by the
// static PlayerTeam.formatNameForTeam(Team, Component) -- team prefix + entry +
// suffix (Gui.displayScoreboardSidebar, decompiled 1.19.1 (the 1.19.2 code base)). Hypixel can
// split one line across prefix and suffix, so the check runs on the finished
// line: a "date serverID" line comes back as just the date. Every other line
// is returned untouched.
@Mixin(PlayerTeam.class)
public abstract class NickHiderLobbyMixin {
	@Inject(method = "formatNameForTeam", at = @At("RETURN"), cancellable = true)
	private static void originclient$hideLobbyId(Team team, Component name, CallbackInfoReturnable<MutableComponent> cir) {
		try {
			MutableComponent line = NickHider.hideLobbyId(cir.getReturnValue());
			if (line != null) {
				cir.setReturnValue(line);
			}
		} catch (Throwable t) {
			// keep the original line
		}
	}
}
