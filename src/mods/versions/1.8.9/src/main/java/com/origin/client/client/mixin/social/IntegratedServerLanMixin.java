package com.origin.client.client.mixin.social;

import com.origin.client.client.social.FairPlay;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.8.9: the integrated server never stores the port it was opened to LAN on
 * (shareToLAN hands it only to its private LAN pinger and returns it as a
 * string), so this records shareToLAN's answer for FairPlay's position key.
 * Both "Open to LAN" and /publish go through shareToLAN. RETURN only, so it is
 * indifferent to how the body is written. If it fails to apply, the host of a
 * LAN world simply shares no party positions (fail-safe for fair play).
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerLanMixin {
	@Inject(method = "shareToLAN", at = @At("RETURN"))
	private void originclient$lanOpened(CallbackInfoReturnable<String> cir) {
		try {
			FairPlay.lanOpened((IntegratedServer) (Object) this, cir.getReturnValue());
		} catch (Throwable ignored) {
			// positions stay off on this host
		}
	}
}
