package com.origin.client.client.mods;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Wires the server-integration mods (Lunar parity batch 3b: Team View, Discord
 * Status, Hypixel) into Fabric's events, from one call in
 * OriginClientMod.onInitializeClient. Every listener is wrapped so a failure in
 * one of these mods can never break a tick, a frame or an incoming chat line --
 * it just leaves that mod quiet.
 */
public final class ServerMods {
	private ServerMods() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			try {
				Hypixel.tick(mc);
			} catch (Throwable t) {
				// fail-soft
			}
			try {
				DiscordPresence.tick(mc);
			} catch (Throwable t) {
				// fail-soft
			}
		});

		// Hypixel sends all of its chat as unsigned system ("game") messages.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			try {
				return Hypixel.allowMessage(message, overlay);
			} catch (Throwable t) {
				return true;
			}
		});
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			try {
				return Hypixel.modifyMessage(message, overlay);
			} catch (Throwable t) {
				return message;
			}
		});

		// Team View: project heads in the world pass, draw markers in the HUD pass.
		WorldRenderEvents.LAST.register(ctx -> {
			try {
				TeamView.capture(ctx);
			} catch (Throwable t) {
				// fail-soft
			}
		});
		HudRenderCallback.EVENT.register((g, delta) -> {
			try {
				TeamView.render(g);
			} catch (Throwable t) {
				// fail-soft
			}
		});
	}
}
