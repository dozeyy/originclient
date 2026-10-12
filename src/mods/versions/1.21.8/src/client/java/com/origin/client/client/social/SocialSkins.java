package com.origin.client.client.social;

import com.mojang.authlib.GameProfile;
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
 *
 * PER-VERSION (1.21.6-1.21.8): the background lookup only fetches the profile
 * (authlib 6 fetchProfile); the skin comes from the skin manager's own cache via
 * getInsecureSkin(profile) each frame -- the call OriginScreenRenderer's account
 * chip already uses here -- which returns Steve/Alex until the texture is in.
 * Faces take their fade in the blit's ARGB (deferred GUI, no shader colour).
 */
public final class SocialSkins {
	private SocialSkins() {
	}

	private static final Map<UUID, GameProfile> CACHE = new ConcurrentHashMap<>();
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
			GameProfile profile = CACHE.get(id);
			if (profile != null) {
				return mc.getSkinManager().getInsecureSkin(profile);
			}
			if (!SocialClient.DEMO && ASKED.size() < MAX_LOOKUPS && ASKED.putIfAbsent(id, Boolean.TRUE) == null) {
				CompletableFuture.supplyAsync(() -> mc.getMinecraftSessionService().fetchProfile(id, false))
						.thenAccept(r -> {
							if (r != null && r.profile() != null) {
								CACHE.put(id, r.profile());
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
		face(g, undashed, x, y, size, 0xFFFFFFFF);
	}

	/** As above, tinted / faded by `argb`. */
	public static void face(GuiGraphics g, String undashed, int x, int y, int size, int argb) {
		try {
			PlayerFaceRenderer.draw(g, skin(undashed), x, y, size, argb);
		} catch (Throwable t) {
			// a missing face leaves a gap, never a crash
		}
	}
}
