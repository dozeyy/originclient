package com.origin.client.client.gui;

import com.origin.client.OriginClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;

/**
 * "Reconnect" on the disconnect screen (JG-6): after a kick, a lost connection or a
 * failed connect, one click joins the same server again instead of Back to server
 * list, find it, Join (Lunar and Feather have it). The button sits under the screen's
 * last button, in the dark vanilla button look every vanilla screen gets.
 *
 * Which server: the address being joined or played, noted every tick while a connect
 * screen is up or a world is loaded (Minecraft.getCurrentServer; a new connect forgets
 * the old one first, so a connect that fails at once never offers the previous server).
 * Singleplayer has none, so its disconnect screen gets no button; Realms use their own
 * screen and are left alone. The reconnect itself is Social.join, the same per-version
 * connect the Friends window's Join uses. Fail-soft: any surprise leaves the screen
 * as vanilla made it.
 */
public final class ReconnectButton {
	private static String lastServer;   // address of the server last joined; null = none

	private ReconnectButton() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ReconnectButton::tick);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			try {
				if (screen instanceof ConnectScreen) {
					lastServer = null;   // a new connect: noted by the next tick
				} else if (screen instanceof DisconnectedScreen) {
					add(client, screen);
				}
			} catch (Throwable t) {
				OriginClient.LOGGER.warn("Origin: no Reconnect button this time", t);
			}
		});
	}

	private static void tick(Minecraft mc) {
		try {
			if (mc.getSingleplayerServer() != null) {
				lastServer = null;
			} else if (mc.level != null || mc.gui.screen() instanceof ConnectScreen) {
				ServerData sd = mc.getCurrentServer();
				if (sd != null && sd.ip != null && !sd.ip.trim().isEmpty()) {
					lastServer = sd.ip.trim();
				}
			}
		} catch (Throwable ignored) {
			// nothing noted: no button
		}
	}

	private static void add(Minecraft client, net.minecraft.client.gui.screens.Screen screen) {
		String server = lastServer;
		if (server == null) {
			return;
		}
		List<AbstractWidget> buttons = Screens.getWidgets(screen);
		// Fabric fires AFTER_INIT again on a resize, and the 1.20.2+ disconnect screen only
		// re-lays its widgets out then (they are not cleared): never add a second one.
		for (AbstractWidget b : buttons) {
			if ("Reconnect".equals(b.getMessage().getString())) {
				return;
			}
		}
		AbstractWidget last = null;
		for (AbstractWidget b : buttons) {
			if (last == null || b.getY() > last.getY()) {
				last = b;
			}
		}
		int bw = last != null ? last.getWidth() : 200;
		int bh = last != null ? last.getHeight() : 20;
		int x = last != null ? last.getX() : screen.width / 2 - bw / 2;
		int y = last != null ? last.getY() + bh + 4 : screen.height / 2 + 30;
		if (y + bh > screen.height - 4 && last != null) {
			y = last.getY() - bh - 4;   // no room under it (a tiny window): above it
		}
		buttons.add(net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal("Reconnect"),
				b -> com.origin.client.client.social.Social.join(server)).bounds(x, y, bw, bh).build());
	}
}
