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
 *
 * PER-VERSION (1.21.4, covers 1.21.2-1.21.4): the background lookup only fetches
 * the profile; the skin itself comes from SkinManager.getInsecureSkin(profile)
 * (the call this module's account chip and cosmetics preview already use), which
 * hands back the default skin until vanilla's own load finishes, then the real one.
 * getOrLoad's result type moved in this line, so it isn't named here.
 */
public final class SocialSkins {
	private SocialSkins() {
	}

	private static final Map<UUID, com.mojang.authlib.GameProfile> CACHE = new ConcurrentHashMap<>();
	// When each lookup last started. A failed lookup (or one that came back with no
	// skin) is retried after RETRY_MS; before, one failure meant Steve/Alex all session.
	private static final Map<UUID, Long> ASKED = new ConcurrentHashMap<>();
	private static final long RETRY_MS = 60_000L;
	// The lookup budget is a RATE: at most MAX_LOOKUPS starts per minute. It used to
	// be 64 per session, so a long session ran dry and new friends never got a face.
	private static final int MAX_LOOKUPS = 64;
	private static long windowStart;
	private static int windowCount;

	/** True (and the start is recorded) if a lookup for `id` may start now. */
	private static synchronized boolean mayLookUp(UUID id) {
		long now = System.currentTimeMillis();
		Long last = ASKED.get(id);
		if (last != null && now - last < RETRY_MS) {
			return false;
		}
		if (now - windowStart >= 60_000L) {
			windowStart = now;
			windowCount = 0;
		}
		if (windowCount >= MAX_LOOKUPS) {
			return false;
		}
		windowCount++;
		ASKED.put(id, now);
		return true;
	}

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
			com.mojang.authlib.GameProfile known = CACHE.get(id);
			if (known != null) {
				return mc.getSkinManager().getInsecureSkin(known);
			}
			if (!SocialClient.DEMO && mayLookUp(id)) {
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
		try {
			PlayerFaceRenderer.draw(g, skin(undashed), x, y, size);
		} catch (Throwable t) {
			// a missing face leaves a gap, never a crash
		}
	}

	/** The same face with an ARGB tint (fades ride the blit here: the GUI is batched). */
	public static void face(GuiGraphics g, String undashed, int x, int y, int size, int argb) {
		try {
			PlayerFaceRenderer.draw(g, skin(undashed), x, y, size, argb);
		} catch (Throwable t) {
			// a missing face leaves a gap, never a crash
		}
	}
}
