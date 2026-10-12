package com.origin.client.client.mods;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

/**
 * Incoming SYSTEM ("game") chat, for the Lunar-parity mods that read or filter
 * it (Auto GG, Hypixel, Hypixel Bed Wars). Same three hooks and order as Fabric's
 * ClientReceiveMessageEvents: allow (false hides the line) runs first, then
 * modify, then receive sees the final line.
 *
 * 1.19.4: Fabric API's own ClientReceiveMessageEvents (fabric-message-api-v1,
 * client events from 1.19.3) -- these methods just register on it. The 1.19.2
 * and 1.18.2 modules have no such events and dispatch from a chat mixin
 * instead; the mods register through this class either way.
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

	public static void allowGame(Allow a) {
		ClientReceiveMessageEvents.ALLOW_GAME.register(a::allow);
	}

	public static void modifyGame(Modify m) {
		ClientReceiveMessageEvents.MODIFY_GAME.register(m::modify);
	}

	public static void onGame(Receive r) {
		ClientReceiveMessageEvents.GAME.register(r::receive);
	}
}
