package com.origin.client.client.mods;

import net.minecraft.network.chat.Component;

/**
 * Incoming-chat dispatch for the Lunar-parity chat mods (Auto GG, Hypixel,
 * Hypixel Bed Wars) on
 * 1.17.1, standing in for Fabric API's ClientReceiveMessageEvents (1.19+; not in
 * Fabric API 0.46.1+1.17). ChatReceiveMixin calls in from Gui.handleChat, the one
 * place every chat / system / action-bar line from the server is delivered.
 *
 * Before 1.19 there is no signed-player-chat vs system split: Hypixel's lines
 * arrive as CHAT or SYSTEM, so both count as the "game" messages the 1.21.1 mods
 * filter; GAME_INFO (the action bar) is the overlay. Same order as Fabric's
 * events: modify, then allow, then the received notification. Each mod's call
 * is fail-soft so one can never block a line.
 */
public final class ChatEvents {
	private ChatEvents() {
	}

	/** MODIFY_GAME: the line as it should be shown. */
	public static Component modify(Component message, boolean overlay) {
		try {
			Component m = Hypixel.modifyMessage(message, overlay);
			return m != null ? m : message;
		} catch (Throwable t) {
			return message;
		}
	}

	/** ALLOW_GAME: false hides the line. */
	public static boolean allow(Component message, boolean overlay) {
		boolean ok = true;
		try {
			ok = overlay || AutoGG.allow(message);
		} catch (Throwable t) {
			// keep it
		}
		try {
			ok &= Hypixel.allowMessage(message, overlay);
		} catch (Throwable t) {
			// keep it
		}
		return ok;
	}

	/** GAME: a line that is being shown (chat and action bar alike). */
	public static void received(Component message, boolean overlay) {
		try {
			HypixelBedwars.onMessage(message, overlay);
		} catch (Throwable t) {
			// fail-soft
		}
		if (overlay) {
			return;
		}
		try {
			AutoGG.onSystemMessage(message);
		} catch (Throwable t) {
			// chat handling must never break message delivery
		}
	}
}
