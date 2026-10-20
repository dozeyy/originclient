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
	private static final Map<UUID, Boolean> ASKED = new ConcurrentHashMap<>();
	private static final int MAX_LOOKUPS = 64;

	public static ResourceLocation skin(String undashed) {
		UUID id = SocialClient.toUuid(undashed);
		if (id == null) {
			return DefaultPlayerSkin.getDefaultSkin(UUID.nameUUIDFromBytes(String.valueOf(undashed).getBytes()));
		}
		return skin(id);
	}

	/**
	 * Same lookup by UUID, with no string work: the title screen's own player chip
	 * calls this every frame. Resolved once per UUID (SkinManager.registerSkins
	 * fetches the textures off-thread and calls back on the render thread, the
	 * result is cached), the default skin until then.
	 */
	public static ResourceLocation skin(UUID id) {
		if (id == null) {
			return DefaultPlayerSkin.getDefaultSkin(new UUID(0L, 0L));
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
			if (!SocialClient.DEMO && ASKED.size() < MAX_LOOKUPS && ASKED.putIfAbsent(id, Boolean.TRUE) == null) {
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
