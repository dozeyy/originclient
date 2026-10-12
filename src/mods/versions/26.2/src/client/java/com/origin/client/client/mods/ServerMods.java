package com.origin.client.client.mods;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Wires the server-integration mods (Lunar parity batch 3b: Team View, Discord
 * Status, Hypixel) into Fabric's events, from one call in
 * OriginClientMod.onInitializeClient. Every listener is wrapped so a failure in
 * one of these mods can never break a tick, a frame or an incoming chat line --
 * it just leaves that mod quiet.
 *
 * 1.21.11: the world pass is LevelRenderEvents.END_MAIN (Fabric's 1.21.9+ world
 * events; LAST no longer exists), and the HUD markers are drawn from
 * HudElements.renderAll (GuiHudMixin, Gui.render RETURN) like every other Origin
 * HUD draw on this module -- HudRenderCallback is deprecated here and would
 * paint in load order, not on top.
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

		// Team View: project heads in the world pass; the HUD pass (markers) is
		// TeamView.render, called from HudElements.renderAll.
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			try {
				TeamView.capture(ctx);
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
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			try {
				HypixelBedwars.onMessage(message, overlay);
			} catch (Throwable t) {
				// fail-soft
			}
		});
	}
}

