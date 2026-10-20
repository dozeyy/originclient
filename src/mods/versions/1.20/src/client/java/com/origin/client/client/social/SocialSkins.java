package com.origin.client.client.social;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;

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
 * 1.20 / 1.20.1: no PlayerSkin record (1.20.2+) and authlib 4 (no fetchProfile),
 * so a skin is its texture ResourceLocation: the profile's signed `textures`
 * property is filled off-thread (fillProfileProperties), then SkinManager
 * registers the texture (it downloads in the background) on the next frame.
 */
public final class SocialSkins {
	private SocialSkins() {
	}

	private static final Map<UUID, ResourceLocation> CACHE = new ConcurrentHashMap<>();
	private static final Map<UUID, CompletableFuture<GameProfile>> ASKED = new ConcurrentHashMap<>();
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
	 * calls this every frame. Resolved once per UUID (profile properties fetched
	 * off-thread, texture id cached), the default skin until then.
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
			CompletableFuture<GameProfile> f = ASKED.get(id);
			if (f == null && !SocialClient.DEMO && ASKED.size() < MAX_LOOKUPS) {
				GameProfile bare = new GameProfile(id, null);
				f = CompletableFuture.supplyAsync(
						() -> mc.getMinecraftSessionService().fillProfileProperties(bare, false), Util.backgroundExecutor());
				CompletableFuture<GameProfile> prev = ASKED.putIfAbsent(id, f);
				if (prev != null) {
					f = prev;
				}
			}
			if (f != null && f.isDone() && !f.isCompletedExceptionally()) {
				GameProfile full = f.getNow(null);
				if (full != null) {
					SkinManager skins = mc.getSkinManager();
					MinecraftProfileTexture tex = skins.getInsecureSkinInformation(full).get(MinecraftProfileTexture.Type.SKIN);
					// registerTexture downloads in the background and returns its id at once
					ResourceLocation loc = tex != null ? skins.registerTexture(tex, MinecraftProfileTexture.Type.SKIN)
							: DefaultPlayerSkin.getDefaultSkin(id);
					CACHE.put(id, loc);
					return loc;
				}
			}
		} catch (Throwable t) {
			// default skin below
		}
		return DefaultPlayerSkin.getDefaultSkin(id);
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
