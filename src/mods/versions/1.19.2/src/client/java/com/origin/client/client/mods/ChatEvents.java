package com.origin.client.client.mods;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Incoming SYSTEM ("game") chat, for the Lunar-parity mods that read or filter
 * it (Auto GG, Hypixel, Hypixel Bed Wars). Same three hooks and order as Fabric's
 * ClientReceiveMessageEvents: allow (false hides the line) runs first, then
 * modify, then receive sees the final line.
 *
 * 1.19.2: Fabric API (0.77.0+1.19.2) has no client message events -- they arrived with
 * 1.19.3 -- so this class keeps the listeners itself and ChatReceiveMixin
 * dispatches every incoming system message (ChatListener.handleSystemMessage) through dispatch(). The mods register through
 * this class on every version.
 */
public final class ChatEvents {
	private ChatEvents() {
	}

	public interface Allow {
		boolean allow(Component message, boolean overlay);
	}

	public interface Modify {
		Component modify(Component message, boolean overlay);
	}

	public interface Receive {
		void receive(Component message, boolean overlay);
	}

	private static final List<Allow> ALLOW = new CopyOnWriteArrayList<>();
	private static final List<Modify> MODIFY = new CopyOnWriteArrayList<>();
	private static final List<Receive> RECEIVE = new CopyOnWriteArrayList<>();

	public static void allowGame(Allow a) {
		ALLOW.add(a);
	}

	public static void modifyGame(Modify m) {
		MODIFY.add(m);
	}

	public static void onGame(Receive r) {
		RECEIVE.add(r);
	}

	/**
	 * Runs one incoming line through the hooks, in Fabric's order. Returns null
	 * when an allow hook hid it, else the (possibly modified) line to show.
	 * Every listener is isolated: one throwing never loses the message.
	 */
	public static Component dispatch(Component message, boolean overlay) {
		for (Allow a : ALLOW) {
			try {
				if (!a.allow(message, overlay)) {
					return null;
				}
			} catch (Throwable t) {
				// a broken filter never hides anything
			}
		}
		for (Modify m : MODIFY) {
			try {
				Component next = m.modify(message, overlay);
				if (next != null) {
					message = next;
				}
			} catch (Throwable t) {
				// keep the line as it was
			}
		}
		for (Receive r : RECEIVE) {
			try {
				r.receive(message, overlay);
			} catch (Throwable t) {
				// fail-soft
			}
		}
		return message;
	}
}
