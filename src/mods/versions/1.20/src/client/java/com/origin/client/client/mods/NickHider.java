package com.origin.client.client.mods;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Nick Hider (Lunar parity): show a name you pick in place of yours -- and
// optionally a stand-in for everyone else's -- everywhere text is drawn, and
// swap skins for the default ones.
//
// HOW names are replaced: every piece of literal text Minecraft draws (chat,
// nametags, tab list, scoreboard, titles) is a PlainTextContents.LiteralContents
// handing its string to whoever visits it; NickHiderTextMixin passes that string
// through replace(). Replacing at the visit -- not inside the glyph loop --
// matters: line wrapping and width maths visit the same text, so every caller
// sees one consistent, already-replaced string and no cut index can land past
// the end of a shorter original. Only whole names match (a name inside a longer
// word is left alone), case-insensitively, and § colour codes are stepped over.
// Only the render thread is touched, so the integrated server in singleplayer
// never sees altered text.
//
// Caches: a component remembers its laid-out text (MutableComponent's visual
// order), and chat keeps its wrapped lines. generation() bumps whenever the
// name map changes; NickHiderCacheMixin drops stale layouts and tick() re-wraps
// chat when YOUR replacement changes (not on every lobby join/leave, which
// would reset the chat scroll each time).
public final class NickHider {
	private NickHider() {
	}

	private static final String ID = "nickhider";

	private static volatile Map<String, String> names = Map.of();   // lower-case name -> replacement
	private static volatile int generation = 0;
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	private static final int CACHE_MAX = 4096;
	private static String lastOwnSig = "";
	private static int ticks = 0;
	// Your real account skin (texture, "default"/"slim", cape) for Use Real Skin,
	// fetched once in the background by registerSkins; null until it lands.
	private static volatile boolean realSkinRequested = false;
	private static volatile ResourceLocation realSkin = null, realCape = null;
	private static volatile String realModel = null;

	// Hypixel's scoreboard date line: "10/11/26 m215AH" -> the trailing server ID.
	private static final Pattern LOBBY_LINE = Pattern.compile("^\\s*(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+[A-Za-z0-9]{2,12}\\s*$");

	public static int generation() {
		return generation;
	}

	/** Rebuilds the name map twice a second, so a settings change or a player
	 *  joining shows up within half a second. */
	public static void tick(Minecraft mc) {
		if (++ticks % 10 != 0) {
			return;
		}
		Map<String, String> next = new HashMap<>();
		String ownSig = "";
		if (Mods.on(ID)) {
			String own = cap(Mods.text(ID, "name"), 16);
			if (own.isEmpty()) {
				own = "You";
			}
			if (Mods.bool(ID, "customSuffix")) {
				own = own + cap(Mods.text(ID, "suffix"), 8);
			}
			ClientPacketListener conn = mc.getConnection();
			UUID self = mc.getUser().getProfileId();
			if (Mods.bool(ID, "hideOthersNames") && conn != null) {
				String other = cap(Mods.text(ID, "othersName"), 16);
				if (other.isEmpty()) {
					other = "Player";
				}
				for (PlayerInfo info : conn.getOnlinePlayers()) {
					if (!info.getProfile().getId().equals(self)) {
						putName(next, info.getProfile().getName(), other);
					}
				}
			}
			// Your own names go in last so they win over an "others" entry.
			if (Mods.bool(ID, "hideName")) {
				putName(next, inGameName(mc), own);
			}
			if (Mods.bool(ID, "hideRealName")) {
				putName(next, mc.getUser().getName(), own);
			}
			ownSig = own + "|" + Mods.bool(ID, "hideName") + "|" + Mods.bool(ID, "hideRealName")
					+ "|" + inGameName(mc) + "|" + Mods.bool(ID, "hideOthersNames");
		}
		if (!next.equals(names)) {
			names = Map.copyOf(next);
			CACHE.clear();
			generation++;
		}
		if (!ownSig.equals(lastOwnSig)) {
			lastOwnSig = ownSig;
			try {
				mc.gui.getChat().rescaleChat();   // re-wrap chat history with the new names
			} catch (Throwable t) {
				// chat refresh is cosmetic
			}
		}
	}

	// A replacement is shown wherever a name was, so keep it name-sized and
	// free of § codes (which would recolour the rest of the line).
	private static String cap(String s, int max) {
		s = s.replace("\u00a7", "").trim();
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static void putName(Map<String, String> map, String name, String replacement) {
		if (name != null && !name.isEmpty()) {
			map.put(name.toLowerCase(Locale.ROOT), replacement);
		}
	}

	// Your name as this server shows it: your nick when nicked, else your account name.
	private static String inGameName(Minecraft mc) {
		ClientPacketListener conn = mc.getConnection();
		if (conn != null) {
			PlayerInfo self = conn.getPlayerInfo(mc.getUser().getProfileId());
			if (self != null) {
				return self.getProfile().getName();
			}
		}
		return mc.getUser().getName();
	}

	/** The text with every mapped name replaced (the same string when none match). */
	public static String replace(String s) {
		Map<String, String> map = names;
		if (map.isEmpty() || s == null || s.isEmpty() || !RenderSystem.isOnRenderThread()) {
			return s;
		}
		String hit = CACHE.get(s);
		if (hit != null) {
			return hit;
		}
		String out = scan(s, map);
		if (CACHE.size() > CACHE_MAX) {
			CACHE.clear();
		}
		CACHE.put(s, out);
		return out;
	}

	private static String scan(String s, Map<String, String> map) {
		StringBuilder sb = null;
		int n = s.length();
		int i = 0;
		while (i < n) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < n) {   // a § colour code: never part of a name
				if (sb != null) {
					sb.append(c).append(s.charAt(i + 1));
				}
				i += 2;
				continue;
			}
			if (!isNameChar(c)) {
				if (sb != null) {
					sb.append(c);
				}
				i++;
				continue;
			}
			int j = i;
			while (j < n && isNameChar(s.charAt(j))) {
				j++;
			}
			String rep = j - i <= 16 ? map.get(s.substring(i, j).toLowerCase(Locale.ROOT)) : null;
			if (rep != null && sb == null) {
				sb = new StringBuilder(n + 16).append(s, 0, i);
			}
			if (sb != null) {
				if (rep != null) {
					sb.append(rep);
				} else {
					sb.append(s, i, j);
				}
			}
			i = j;
		}
		return sb == null ? s : sb.toString();
	}

	private static boolean isNameChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
	}

	// Which skin a profile shows: KEEP its own, DEFAULT (the UUID's default skin,
	// no cape) or REAL (your account's own skin while you're nicked).
	private enum Skin { KEEP, DEFAULT, REAL }

	private static Skin choice(GameProfile profile) {
		if (!Mods.on(ID) || profile == null) {
			return Skin.KEEP;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!profile.getId().equals(mc.getUser().getProfileId())) {
			return Mods.bool(ID, "hideOthersSkin") ? Skin.DEFAULT : Skin.KEEP;
		}
		if (Mods.bool(ID, "hideOwnSkin")) {
			return Skin.DEFAULT;
		}
		boolean nicked = !profile.getName().equalsIgnoreCase(mc.getUser().getName());
		if (nicked && Mods.bool(ID, "useRealSkin")) {
			requestRealSkin(mc);
			return realSkin != null ? Skin.REAL : Skin.KEEP;
		}
		return Skin.KEEP;
	}

	// 1.20 has no PlayerSkin / SkinManager.lookupInsecure (1.20.2+). Its
	// registerSkins does the same job: for your own profile it reads the textures
	// property Minecraft fetched for your account (filling it on the background
	// executor if empty -- never on the render thread), registers the textures and
	// calls back on the render thread with each one.
	private static void requestRealSkin(Minecraft mc) {
		if (realSkinRequested) {
			return;
		}
		realSkinRequested = true;
		GameProfile own = new GameProfile(mc.getUser().getProfileId(), mc.getUser().getName());
		mc.getSkinManager().registerSkins(own, (type, location, texture) -> {
			if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN) {
				String model = texture.getMetadata("model");
				realModel = "slim".equals(model) ? "slim" : "default";
				realSkin = location;
			} else if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.CAPE) {
				realCape = location;
			}
		}, false);
	}

	/**
	 * The skin texture to show for this tab-list entry instead of its own, or null
	 * to keep it. Every player model and tab-list head reads its skin from here.
	 */
	public static ResourceLocation skinFor(GameProfile profile) {
		return switch (choice(profile)) {
			case DEFAULT -> DefaultPlayerSkin.getDefaultSkin(profile.getId());
			case REAL -> realSkin;
			default -> null;
		};
	}

	/** The arm model ("default" / "slim") matching skinFor, or null to keep it. */
	public static String modelFor(GameProfile profile) {
		return switch (choice(profile)) {
			case DEFAULT -> DefaultPlayerSkin.getSkinModelName(profile.getId());
			case REAL -> realModel;
			default -> null;
		};
	}

	/** The cape matching skinFor as a one-slot array (slot null = no cape), or null to keep it. */
	public static ResourceLocation[] capeFor(GameProfile profile) {
		return switch (choice(profile)) {
			case DEFAULT -> new ResourceLocation[]{null};
			case REAL -> new ResourceLocation[]{realCape};
			default -> null;
		};
	}

	/**
	 * Hide Lobby ID: given one formatted scoreboard line, returns the line with
	 * Hypixel's server ID removed (keeping the date), or null to leave it.
	 */
	public static MutableComponent hideLobbyId(Component line) {
		if (line == null || !Mods.on(ID) || !Mods.bool(ID, "hideLobbyId")) {
			return null;
		}
		String plain = ChatFormatting.stripFormatting(line.getString());
		if (plain == null || plain.length() > 40) {
			return null;
		}
		Matcher m = LOBBY_LINE.matcher(plain);
		if (!m.matches()) {
			return null;
		}
		return Component.literal(m.group(1)).withStyle(ChatFormatting.GRAY);
	}
}
