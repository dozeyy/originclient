package com.origin.client.client.mods;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Nick Hider (Lunar parity, trimmed by Will 2026-10-12): show a name you pick
// in place of yours everywhere text is drawn, and wear the default skin -- on
// YOUR screen only. The server and other players never see any of this; it's
// for streaming/recording (and Hide Lobby ID stops stream sniping). Other
// players' names and skins are deliberately left alone.
//
// HOW names are replaced on 1.8.9: every string the game draws or measures
// (chat, nametags, tab list, scoreboard, titles) goes through FontRenderer as a
// § String -- there is no component layout layer before 1.16. NickHiderTextMixin
// passes the String argument of FontRenderer's draw / width / trim / wrap entry
// points through replace() at HEAD (HEAD @ModifyVariable is the shape that
// survives OptiFine replacing FontRenderer). Width maths, wrapping and drawing
// therefore all see the same replaced string, so no cut index can land past the
// end of a shorter original. Text the game only READS (chat parsing, the
// Hypixel party roster, AutoGG triggers) keeps the real name -- components are
// never touched. Only whole names match (a name inside a longer word is left
// alone), case-insensitively, and § colour codes are stepped over. Only the
// main thread is touched, so the integrated server in singleplayer never sees
// altered text.
//
// Caches: chat keeps its wrapped lines (GuiNewChat.drawnChatLines); tick()
// re-wraps chat when YOUR replacement changes (not on every lobby join/leave,
// which would reset the chat scroll each time). generation() bumps whenever the
// name map changes, for callers that cache drawn text (1.8.9 itself has no
// per-component layout cache, so no cache mixin is needed here).
public final class NickHider {
	private NickHider() {
	}

	private static final String ID = "nickhider";

	private static volatile Map<String, String> names = java.util.Collections.emptyMap();   // lower-case name -> replacement
	private static volatile int generation = 0;
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	private static final int CACHE_MAX = 4096;
	private static String lastOwnSig = "";
	private static int ticks = 0;

	/** A skin as NetworkPlayerInfo hands it out in this era: texture + model ("default"/"slim"). */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Skin {
		private final ResourceLocation texture;
		private final String model;

		public Skin(ResourceLocation texture, String model) {
			this.texture = texture;
			this.model = model;
		}

		public ResourceLocation texture() {
			return texture;
		}

		public String model() {
			return model;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Skin)) {
				return false;
			}
			Skin other = (Skin) obj;
			return java.util.Objects.equals(this.texture, other.texture)
					&& java.util.Objects.equals(this.model, other.model);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(texture, model);
		}
	}

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
			// Both of your names: the one this server shows (a server nick, when
			// nicked) and your account name, so neither ever slips through.
			if (Mods.bool(ID, "hideName")) {
				putName(next, inGameName(mc), own);
				putName(next, mc.getSession().getUsername(), own);
			}
			ownSig = own + "|" + Mods.bool(ID, "hideName") + "|" + inGameName(mc);
		}
		if (!next.equals(names)) {
			names = java.util.Collections.unmodifiableMap(next);   // next is a fresh map every pass
			CACHE.clear();
			generation++;
		}
		if (!ownSig.equals(lastOwnSig)) {
			lastOwnSig = ownSig;
			try {
				// re-wrap chat history with the new names (wrap widths are measured on drawn text)
				if (mc.ingameGUI != null) {
					mc.ingameGUI.getChatGUI().refreshChat();
				}
			} catch (Throwable t) {
				// chat refresh is cosmetic
			}
		}
	}

	// A replacement is shown wherever a name was, so keep it name-sized and
	// free of § codes (which would recolour the rest of the line).
	private static String cap(String s, int max) {
		s = s.replace("§", "").trim();
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static void putName(Map<String, String> map, String name, String replacement) {
		if (name != null && !name.isEmpty()) {
			map.put(name.toLowerCase(Locale.ROOT), replacement);
		}
	}

	// Your name as this server shows it: your nick when nicked, else your account name.
	private static String inGameName(Minecraft mc) {
		NetHandlerPlayClient conn = mc.getNetHandler();
		UUID id = accountId(mc);
		if (conn != null && id != null) {
			NetworkPlayerInfo self = conn.getPlayerInfo(id);
			if (self != null) {
				return self.getGameProfile().getName();
			}
		}
		return mc.getSession().getUsername();
	}

	private static UUID accountId(Minecraft mc) {
		try {
			GameProfile p = mc.getSession().getProfile();
			return p != null ? p.getId() : null;
		} catch (Throwable t) {
			return null;   // a malformed offline session id
		}
	}

	/** The text with every mapped name replaced (the same string when none match). */
	public static String replace(String s) {
		Map<String, String> map = names;
		if (map.isEmpty() || s == null || s.isEmpty()) {
			return s;
		}
		// 1.8.9: the "render thread" is the client main thread.
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || !mc.isCallingFromMinecraftThread()) {
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

	/**
	 * The skin to show for this tab-list entry instead of its own, or null to
	 * keep it. Every player model and tab-list head reads its skin from its
	 * NetworkPlayerInfo (getLocationSkin + getSkinType, NickHiderSkinMixin); only
	 * YOUR entry is ever changed.
	 */
	public static Skin skinFor(GameProfile profile) {
		if (!Mods.on(ID) || profile == null || profile.getId() == null || !Mods.bool(ID, "hideOwnSkin")) {
			return null;
		}
		UUID account = accountId(Minecraft.getMinecraft());
		return profile.getId().equals(account) ? defaultSkin(profile.getId()) : null;
	}

	// 1.8.9: DefaultPlayerSkin.getDefaultSkin(UUID) / getSkinType(UUID) pick
	// Steve or Alex from the UUID exactly as an account without a skin gets.
	private static Skin defaultSkin(UUID id) {
		return new Skin(DefaultPlayerSkin.getDefaultSkin(id), DefaultPlayerSkin.getSkinType(id));
	}

	/**
	 * Hide Lobby ID: given one formatted scoreboard line (a § String on 1.8.9,
	 * from ScorePlayerTeam.formatPlayerName), returns the line with Hypixel's
	 * server ID removed (keeping the date), or null to leave it.
	 */
	public static String hideLobbyId(String line) {
		if (line == null || !Mods.on(ID) || !Mods.bool(ID, "hideLobbyId")) {
			return null;
		}
		String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
		if (plain == null || plain.length() > 40) {
			return null;
		}
		Matcher m = LOBBY_LINE.matcher(plain);
		if (!m.matches()) {
			return null;
		}
		return EnumChatFormatting.GRAY + m.group(1);
	}
}
