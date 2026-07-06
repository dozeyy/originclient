package com.origin.client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;

// Minimal, clean HUD overlay: FPS / coordinates / ping. Deliberately just
// text in the corner — no boxes or backgrounds, matching the "simplest" brief.
public final class OriginHud {
	private static final int TEXT_COLOR = 0xE0E0E0;
	private static final int PADDING = 6;
	private static final int LINE_HEIGHT = 10;

	private OriginHud() {
	}

	public static void render(GuiGraphics guiGraphics) {
		if (!OriginClientMod.FEATURES.hudInfoEnabled) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.options.hideGui) {
			return;
		}

		int y = PADDING;
		guiGraphics.drawString(client.font, "FPS: " + client.getFps(), PADDING, y, TEXT_COLOR);
		y += LINE_HEIGHT;

		String coords = String.format("XYZ: %.1f / %.1f / %.1f", player.getX(), player.getY(), player.getZ());
		guiGraphics.drawString(client.font, coords, PADDING, y, TEXT_COLOR);
		y += LINE_HEIGHT;

		ClientPacketListener connection = client.getConnection();
		if (connection != null) {
			PlayerInfo info = connection.getPlayerInfo(player.getUUID());
			if (info != null) {
				guiGraphics.drawString(client.font, "Ping: " + info.getLatency() + "ms", PADDING, y, TEXT_COLOR);
			}
		}
	}
}
