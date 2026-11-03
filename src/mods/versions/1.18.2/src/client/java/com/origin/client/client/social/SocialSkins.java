package com.origin.client.client.social;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Friends' faces for the Friends window, toasts and the Party HUD. A friend you
 * can see on the current server uses that server's skin (no lookup at all);
 * anyone else is looked up once in the background and shows Steve/Alex until
 * it arrives. Never blocks a frame, never throws: a failed lookup just keeps
 * the default skin.
 *
 * Pre-1.20 port: there is no PlayerSkin / SkinManager.getOrLoad yet, so a skin
 * is its texture's ResourceLocation, and the lookup is this era's
 * SkinManager.registerSkins on a uuid-only profile (it fetches the profile's
 * textures from Mojang itself, off-thread, and calls back on the render
 * thread) -- the same call the account chip and MenuPlayerPreview use.
 */
public final class SocialSkins {
	private SocialSkins() {
	}

	private static final Map<UUID, ResourceLocation> CACHE = new ConcurrentHashMap<>();
	// When each uuid was last looked up. A lookup that never produced a skin (network
	// error, Mojang rate limit, no textures) is tried again after RETRY_MS; a found skin
	// sits in CACHE and is never looked up again.
	private static final Map<UUID, Long> ASKED = new ConcurrentHashMap<>();
	private static final long RETRY_MS = 60_000L;
	// The lookup budget is a RATE (MAX_LOOKUPS per minute, refilled), not a lifetime cap:
	// a once-spent budget used to leave every later friend on Steve/Alex all session.
	private static final int MAX_LOOKUPS = 64;
	private static final long BUDGET_WINDOW_MS = 60_000L;
	private static long budgetStart = 0L;
	private static int budgetUsed = 0;

	/** True when `id` may be looked up now (not asked within RETRY_MS, budget left); records the ask. */
	private static synchronized boolean takeLookup(UUID id) {
		long now = System.currentTimeMillis();
		Long last = ASKED.get(id);
		if (last != null && now - last < RETRY_MS) {
			return false;
		}
		if (now - budgetStart >= BUDGET_WINDOW_MS) {
			budgetStart = now;
			budgetUsed = 0;
		}
		if (budgetUsed >= MAX_LOOKUPS) {
			return false;
		}
		budgetUsed++;
		ASKED.put(id, now);
		return true;
	}

	public static ResourceLocation skin(String undashed) {
		UUID id = SocialClient.toUuid(undashed);
		if (id == null) {
			return DefaultPlayerSkin.getDefaultSkin(UUID.nameUUIDFromBytes(String.valueOf(undashed).getBytes()));
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.getConnection() != null) {
				PlayerInfo info = mc.getConnection().getPlayerInfo(id);
				if (info != null) {
					return info.getSkinLocation();
				}
			}
			ResourceLocation s = CACHE.get(id);
			if (s != null) {
				return s;
			}
			if (!SocialClient.DEMO && takeLookup(id)) {
				mc.getSkinManager().registerSkins(new GameProfile(id, null), (type, location, texture) -> {
					if (type == MinecraftProfileTexture.Type.SKIN && location != null) {
						CACHE.put(id, location);
					}
				}, true);
			}
		} catch (Throwable t) {
			// default skin below
		}
		return DefaultPlayerSkin.getDefaultSkin(id);
	}

	/** The face at (x, y), `size` px, in the current pose's units. */
	public static void face(Gfx g, String undashed, int x, int y, int size) {
		try {
			g.face(skin(undashed), x, y, size);
		} catch (Throwable t) {
			// a missing face leaves a gap, never a crash
		}
	}
}
