package com.origin.client.client.social;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Friends' faces for the Friends window, toasts and the Party HUD. A friend you
 * can see on the current server uses that server's skin (no lookup at all);
 * anyone else is looked up once in the background (Mojang profile -> vanilla
 * skin manager) and shows Steve/Alex until it arrives. Never blocks a frame,
 * never throws: a failed lookup just keeps the default skin.
 */
public final class SocialSkins {
	private SocialSkins() {
	}

	private static final Map<UUID, PlayerSkin> CACHE = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> ASKED = new ConcurrentHashMap<>();
	private static final int MAX_LOOKUPS = 64;

	public static PlayerSkin skin(String undashed) {
		UUID id = SocialClient.toUuid(undashed);
		if (id == null) {
			return DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(String.valueOf(undashed).getBytes()));
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.getConnection() != null) {
				PlayerInfo info = mc.getConnection().getPlayerInfo(id);
				if (info != null) {
					return info.getSkin();
				}
			}
			PlayerSkin s = CACHE.get(id);
			if (s != null) {
				return s;
			}
			if (!SocialClient.DEMO && ASKED.size() < MAX_LOOKUPS && ASKED.putIfAbsent(id, Boolean.TRUE) == null) {
				CompletableFuture.supplyAsync(() -> mc.getMinecraftSessionService().fetchProfile(id, false))
						.thenCompose(r -> r == null ? CompletableFuture.completedFuture(null)
								: mc.getSkinManager().getOrLoad(r.profile()))
						.thenAccept(skin -> {
							if (skin != null) {
								CACHE.put(id, skin);
							}
						})
						.exceptionally(t -> null);
			}
		} catch (Throwable t) {
			// default skin below
		}
		return DefaultPlayerSkin.get(id);
	}

	/** The face at (x, y), `size` px, in the current pose's units. */
	public static void face(GuiGraphics g, String undashed, int x, int y, int size) {
		try {
			PlayerFaceRenderer.draw(g, skin(undashed), x, y, size);
		} catch (Throwable t) {
			// a missing face leaves a gap, never a crash
		}
	}
}
