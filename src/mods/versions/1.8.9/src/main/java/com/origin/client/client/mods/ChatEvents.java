package com.origin.client.client.mods;

import net.minecraft.util.IChatComponent;

/**
 * Incoming-chat dispatch for the Lunar-parity chat mods (Auto GG, Hypixel,
 * Hypixel Bed Wars).
 *
 * 1.8.9: Forge's ClientChatReceivedEvent is the one door every server line goes
 * through (NetHandlerPlayClient.handleChat posts it before printing to chat or
 * the action bar), so ServerHooks calls in from there -- no mixin. Its `type`
 * byte is 0 chat, 1 system, 2 action bar (the "overlay"); Hypixel's lines arrive
 * as 0 or 1, so both count as the "game" messages the 1.21.1 mods filter. The
 * event's `message` field is writable and cancelling it drops the line, so a
 * rewrite is just an assignment (no re-delivery dance as on 1.16.5). Same order
 * as Fabric's events: modify, then allow, then the received notification. Each
 * mod's call is fail-soft so one can never block a line.
 */
public final class ChatEvents {
	private ChatEvents() {
	}

	/** MODIFY_GAME: the line as it should be shown. */
	public static IChatComponent modify(IChatComponent message, boolean overlay) {
		try {
			IChatComponent m = Hypixel.modifyMessage(message, overlay);
			return m != null ? m : message;
		} catch (Throwable t) {
			return message;
		}
	}

	/** ALLOW_GAME: false hides the line. */
	public static boolean allow(IChatComponent message, boolean overlay) {
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
	public static void received(IChatComponent message, boolean overlay) {
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
