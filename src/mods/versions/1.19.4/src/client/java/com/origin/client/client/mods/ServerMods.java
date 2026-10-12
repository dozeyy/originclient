package com.origin.client.client.mods;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
		ChatEvents.allowGame((message, overlay) -> {
			try {
				return Hypixel.allowMessage(message, overlay);
			} catch (Throwable t) {
				return true;
			}
		});
		ChatEvents.modifyGame((message, overlay) -> {
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
		HudRenderCallback.EVENT.register((poseStack, delta) -> {
			try {
				TeamView.render(new com.origin.client.client.gui.Gfx(poseStack));
			} catch (Throwable t) {
				// fail-soft
			}
		});

		registerLunarBedwars();
	}

	// Hypixel Bed Wars (Lunar parity): its own listeners, registered after the
	// ones above so Hypixel.tick has already refreshed onHypixel / inBedwars when
	// HypixelBedwars.tick reads them.
	private static void registerLunarBedwars() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			try {
				HypixelBedwars.tick(mc);
			} catch (Throwable t) {
				// fail-soft
			}
		});
		ChatEvents.onGame((message, overlay) -> {
			try {
				HypixelBedwars.onMessage(message, overlay);
			} catch (Throwable t) {
				// fail-soft
			}
		});
	}
}
