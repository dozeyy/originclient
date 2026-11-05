package com.origin.client.client.mixin;

import com.mojang.realmsclient.dto.RealmsServer;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.realms.RealmsConnect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Waypoints on a Realm (MAP-7): the join hands over the RealmsServer, whose id is the one
// thing about a Realm that never changes (its address moves between hosts, its name can be
// renamed or shared). Waypoints keys the Realm's list by it. Fail-soft: if this hook doesn't
// apply (the config is not required, require = 0), Waypoints falls back to the Realm's name.
@Mixin(RealmsConnect.class)
public class RealmsConnectMixin {
	@Inject(method = "connect", at = @At("HEAD"), require = 0)
	private void originclient$realmId(RealmsServer server, ServerAddress address, CallbackInfo ci) {
		try {
			com.origin.client.client.waypoints.Waypoints.joiningRealm(server.id);
		} catch (Throwable ignored) {
			// a Realm's waypoints then go by its name; the join itself is never touched
		}
	}
}
