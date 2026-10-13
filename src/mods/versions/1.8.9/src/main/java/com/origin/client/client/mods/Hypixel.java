package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel (Lunar parity): Lunar's Hypixel mod, client side. Everything here is
 * gated on {@link #active()} -- the mod on AND connected to *.hypixel.net -- so on
 * any other server it does nothing at all.
 *
 * Three jobs:
 *  - CHAT: hides / shortens Hypixel's own system lines (ChatEvents feeds every
 *    incoming game message through {@link #allowMessage} / {@link #modifyMessage}).
 *    The match patterns are Hypixel's line formats; they are plain-text checks
 *    on the formatting-stripped line.
 *  - AUTOMATION: Auto Friend / Auto Tip / Auto Who send only the one command the
 *    player switched on, never anything else.
 *  - LEVELS: Level Head on nametags (the world area's nametag hook calls
 *    {@link #levelHeadText}) and level / Bed Wars stats in the tab list
 *    (OriginTabList -> {@link #decorateTabName}); the numbers come from
 *    {@link HypixelApi} with the player's own API key.
 *
 * It also keeps the party roster Team View marks (Hypixel's party lines are the
 * only client-side source for it -- see {@link #partyMembers}).
 *
 * 1.8.9: chat is IChatComponent + ChatStyle, whose colour is one of the 16
 * EnumChatFormatting colours only (no RGB TextColor before 1.16), so the Level
 * Head colour options are matched to the nearest of the 16 (see {@link #nearest}).
 * Scoreboard text (objective names, team prefixes, tab names) is plain String
 * with § codes. Hypixel's 1.8 lines often carry § codes INSIDE component text,
 * so every plain-text check strips them after flattening.
 */
public final class Hypixel {
	private static final String MOD = "hypixel";

	private Hypixel() {
	}

	// ---- where we are (refreshed once per tick by tick(), read everywhere) ----

	private static boolean onHypixel = false, inBedwars = false, inLobby = false, inSkyblock = false;

	/** Connected to *.hypixel.net (regardless of whether the mod is on). */
	public static boolean onHypixel() {
		return onHypixel;
	}

	/** The Hypixel mod is on and we're on Hypixel: the gate for every feature here. */
	public static boolean active() {
		return onHypixel && Mods.on(MOD);
	}

	/** In a Bed Wars game or lobby (the sidebar reads "BED WARS"). */
	public static boolean inBedwars() {
		return onHypixel && inBedwars;
	}

	/** In a hub/lobby (the "Game Menu" compass is in the hotbar) or in SkyBlock --
	 *  the places Lunar shows party members without it being an unfair advantage. */
	public static boolean inSafeArea() {
		return onHypixel && (inLobby || inSkyblock);
	}

	static boolean isHypixelHost(ServerData server) {
		if (server == null || server.serverIP == null) {
			return false;
		}
		String host = server.serverIP.trim().toLowerCase(Locale.ROOT);
		int colon = host.lastIndexOf(':');
		if (colon > 0 && host.indexOf(':') == colon) {
			host = host.substring(0, colon);            // "mc.hypixel.net:25565"
		}
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
	}

	// ---- per tick ----

	private static final long TIP_EVERY_MS = 15L * 60L * 1000L;
	private static long nextTipAt = 0;
	private static boolean keyHintShown = false;
	private static int whoInTicks = -1;

	static void tick(Minecraft mc) {
		boolean was = onHypixel;
		onHypixel = mc.thePlayer != null && mc.theWorld != null && isHypixelHost(mc.getCurrentServerData());
		if (!onHypixel) {
			if (was) {
				onLeave();
			}
			return;
		}
		HypixelApi.optionKey = Mods.text(MOD, "apiKey").trim();
		if (!was) {
			nextTipAt = System.currentTimeMillis() + 30_000L;   // first tip shortly after joining
		}
		String title = sidebarTitle(mc);
		inBedwars = title.contains("BED WARS");
		inSkyblock = title.contains("SKYBLOCK");
		inLobby = hotbarHas(mc, "Game Menu");

		if (!Mods.on(MOD)) {
			return;
		}
		// Levels/stats switched on but no key yet: say where to put one, once a session.
		if (!keyHintShown && HypixelApi.optionKey.isEmpty()
				&& (Mods.bool(MOD, "levelHead") || Mods.bool(MOD, "levelsInTab") || Mods.bool(MOD, "bedwarsStats"))) {
			keyHintShown = true;
			mc.thePlayer.addChatMessage(new ChatComponentText(
					"Hypixel levels and stats need your Hypixel API key: Right Shift > Mods > Hypixel > API Key."));
		}
		if (Mods.bool(MOD, "autoTip") && System.currentTimeMillis() >= nextTipAt) {
			nextTipAt = System.currentTimeMillis() + TIP_EVERY_MS;
			command(mc, "tip all");
		}
		if (whoInTicks > 0 && --whoInTicks == 0) {
			whoInTicks = -1;
			if (Mods.bool(MOD, "autoWho")) {
				command(mc, "who");
			}
		}
	}

	private static void onLeave() {
		inBedwars = inLobby = inSkyblock = false;
		whoInTicks = -1;
		synchronized (PARTY) {
			PARTY.clear();
		}
		HypixelApi.clear();
	}

	// 1.8.9: display slots are ints (0 list, 1 sidebar, 2 below name) and an
	// objective's display name is a plain § string.
	private static String sidebarTitle(Minecraft mc) {
		ScoreObjective o = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
		if (o == null) {
			return "";
		}
		return strip(o.getDisplayName()).toUpperCase(Locale.ROOT);
	}

	// 1.8.9: inventory slots hold null when empty (no ItemStack.EMPTY yet).
	private static boolean hotbarHas(Minecraft mc, String name) {
		for (int i = 0; i < 9; i++) {
			ItemStack st = mc.thePlayer.inventory.getStackInSlot(i);
			if (st != null && strip(st.getDisplayName()).contains(name)) {
				return true;
			}
		}
		return false;
	}

	private static void command(Minecraft mc, String cmd) {
		if (mc.thePlayer != null && mc.getNetHandler() != null) {
			mc.thePlayer.sendChatMessage("/" + cmd);   // 1.8.9: commands go out as "/" chat
		}
	}

	// ---- chat ----

	private static final Pattern FRIEND_REQUEST = Pattern.compile("^Friend request from (?:\\[[^\\]]+\\] )?(\\w{1,16})");
	private static final Pattern GG = Pattern.compile(
			"^(?:\\[[^\\]]+\\] )*\\w{1,16}: (?:gg|good game|gg wp|ggwp|gf|ggs)[!. ]*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern FRIEND_GUILD_JOIN = Pattern.compile("^(?:Friend|Guild) > \\w{1,16} joined\\.$");
	private static final Pattern FRIEND_GUILD_LEAVE = Pattern.compile("^(?:Friend|Guild) > \\w{1,16} left\\.$");

	/** False = drop this incoming game message. Also drives the automation and
	 *  the party roster, which need to see every line (even ones it then hides). */
	static boolean allowMessage(IChatComponent message, boolean overlay) {
		if (overlay || !onHypixel) {
			return true;
		}
		String plain = strip(message.getUnformattedText()).trim();
		trackParty(plain);   // Team View reads the roster even with the Hypixel mod off
		if (!Mods.on(MOD)) {
			return true;
		}
		Minecraft mc = Minecraft.getMinecraft();

		Matcher fr = FRIEND_REQUEST.matcher(plain);
		if (fr.find() && Mods.bool(MOD, "autoFriend")) {
			command(mc, "friend accept " + fr.group(1));
		}
		if (plain.equals("The game starts in 1 second!") && Mods.bool(MOD, "autoWho")) {
			whoInTicks = 30;   // once everyone has been teleported in
		}

		if (Mods.bool(MOD, "hidePrivateMessages") && (plain.startsWith("From ") || plain.startsWith("To "))
				&& plain.contains(": ")) {
			return false;
		}
		if (Mods.bool(MOD, "hidePartyChat") && plain.startsWith("Party > ")) {
			return false;
		}
		if (Mods.bool(MOD, "hideGuildChat") && (plain.startsWith("Guild > ") || plain.startsWith("Officer > "))
				&& plain.contains(": ")) {
			return false;
		}
		if (Mods.bool(MOD, "hideTeamChat") && plain.startsWith("[TEAM] ")) {
			return false;
		}
		if (Mods.bool(MOD, "hideShout") && plain.startsWith("[SHOUT] ")) {
			return false;
		}
		if (Mods.bool(MOD, "hideSpectatorChat") && plain.startsWith("[SPECTATOR] ")) {
			return false;
		}
		if (Mods.bool(MOD, "removeLobbyStatuses")
				&& (plain.endsWith("joined the lobby!") || plain.contains(" into the lobby!"))) {
			return false;
		}
		if (Mods.bool(MOD, "hideJoinMessages") && FRIEND_GUILD_JOIN.matcher(plain).matches()) {
			return false;
		}
		if (Mods.bool(MOD, "hideLeaveMessages") && FRIEND_GUILD_LEAVE.matcher(plain).matches()) {
			return false;
		}
		if (Mods.bool(MOD, "hideSoulWell") && plain.contains("Soul Well") && plain.contains(" found ")) {
			return false;
		}
		if (Mods.bool(MOD, "hideMysteryBox") && plain.contains("Mystery Box") && plain.contains(" found ")) {
			return false;
		}
		if (Mods.bool(MOD, "antiGg") && GG.matcher(plain).matches()) {
			return false;
		}
		return true;
	}

	private static final String[][] CHANNELS = {
			{"Party > ", "P > "}, {"Guild > ", "G > "}, {"Officer > ", "O > "}};

	/** Short Channel Names: rewrites the channel prefix, keeping every style. */
	static IChatComponent modifyMessage(IChatComponent message, boolean overlay) {
		if (overlay || !active() || !Mods.bool(MOD, "shortChannelNames")) {
			return message;
		}
		String raw = message.getUnformattedText();
		if (raw.indexOf('§') >= 0) {
			return message;   // legacy colour codes in the text: character offsets wouldn't line up
		}
		for (String[] ch : CHANNELS) {
			if (raw.startsWith(ch[0])) {
				return replaceLeading(message, ch[0].length(), ch[1]);
			}
		}
		return message;
	}

	// ---- party roster (for Team View) ----

	private static final Set<String> PARTY = new HashSet<>();
	private static final Pattern RANK = Pattern.compile("\\[[^\\]]*\\]");
	private static final Pattern P_JOINED_THEIRS = Pattern.compile("^You have joined (.+?)'s? party!$");
	private static final Pattern P_JOINED = Pattern.compile("^(.+?) joined the party\\.$");
	private static final Pattern P_LEFT = Pattern.compile(
			"^(.+?) (?:has left the party|has been removed from the party|was removed from your party because they disconnected)\\.?$");
	private static final Pattern P_KICKED_OFFLINE = Pattern.compile("^Kicked (.+?) because they were offline\\.?$");
	private static final Pattern P_WITH = Pattern.compile("^You'll be partying with: (.+)$");
	private static final Pattern P_LIST = Pattern.compile("^Party (?:Leader|Moderators|Members): (.+)$");

	/** Lower-case names of the players in your Hypixel party (yourself excluded). */
	public static Set<String> partyMembers() {
		synchronized (PARTY) {
			return new HashSet<>(PARTY);
		}
	}

	private static void trackParty(String line) {
		if (line.startsWith("You left the party") || line.startsWith("The party was disbanded")
				|| line.startsWith("You have been kicked from the party") || line.startsWith("You are not currently in a party")
				|| line.startsWith("You are not in a party") || line.startsWith("Party Members (")) {
			synchronized (PARTY) {
				PARTY.clear();   // "Party Members (N)" heads a fresh /party list: rebuild from it
			}
			return;
		}
		Matcher m;
		if ((m = P_JOINED_THEIRS.matcher(line)).matches() || (m = P_JOINED.matcher(line)).matches()) {
			addNames(m.group(1));
		} else if ((m = P_WITH.matcher(line)).matches()) {
			addNames(m.group(1).replace(',', ' '));
		} else if ((m = P_LIST.matcher(line)).matches()) {
			addNames(m.group(1));
		} else if ((m = P_LEFT.matcher(line)).matches() || (m = P_KICKED_OFFLINE.matcher(line)).matches()) {
			String n = lastName(m.group(1));
			if (n != null) {
				synchronized (PARTY) {
					PARTY.remove(n);
				}
			}
		}
	}

	// "[MVP+] Name ● [VIP] Other ●" / "[MVP+] Name" / "Name, Other" -> each name.
	private static void addNames(String s) {
		Minecraft mc = Minecraft.getMinecraft();
		String me = mc.thePlayer != null ? mc.thePlayer.getGameProfile().getName().toLowerCase(Locale.ROOT) : "";
		String cleaned = RANK.matcher(s).replaceAll(" ").replace('●', ' ');
		synchronized (PARTY) {
			for (String tok : cleaned.split("\\s+")) {
				if (tok.matches("\\w{1,16}") && !tok.equalsIgnoreCase(me)) {
					PARTY.add(tok.toLowerCase(Locale.ROOT));
				}
			}
		}
	}

	private static String lastName(String s) {
		String[] toks = RANK.matcher(s).replaceAll(" ").trim().split("\\s+");
		String n = toks.length == 0 ? "" : toks[toks.length - 1];
		return n.matches("\\w{1,16}") ? n.toLowerCase(Locale.ROOT) : null;
	}

	// ---- tab list ----

	private static final Pattern GUILD_TAG = Pattern.compile("\\s\\[[A-Za-z0-9 _+\\-]{1,12}\\]\\s*$");

	/**
	 * OriginTabList's per-row name hook: strips the guild tag and appends the
	 * level / Bed Wars stats the player switched on. Any failure returns the
	 * name untouched.
	 *
	 * 1.8.9: a tab row's name is a § string (GuiPlayerTabOverlay.getPlayerName is
	 * the display name's formatted text, or the team-formatted profile name), so
	 * this is the String form; the IChatComponent overload below wraps it.
	 */
	public static String decorateTabName(NetworkPlayerInfo info, String name) {
		if (!active() || info == null || name == null) {
			return name;
		}
		try {
			String out = name;
			if (Mods.bool(MOD, "removeGuildOnTab")) {
				Matcher m = GUILD_TAG.matcher(strip(out));
				if (m.find() && m.start() > 0) {
					out = truncateFormatted(out, m.start());
				}
			}
			boolean levels = Mods.bool(MOD, "levelsInTab");
			boolean stats = Mods.bool(MOD, "bedwarsStats") && inBedwars();
			if (!levels && !stats) {
				return out;
			}
			HypixelApi.Stats s = HypixelApi.get(info.getGameProfile().getId());
			if (s == null) {
				return out;
			}
			StringBuilder row = new StringBuilder(out).append(EnumChatFormatting.RESET);
			if (levels) {
				row.append(' ').append(levelBadge(s)).append(EnumChatFormatting.RESET);
			}
			if (stats) {
				row.append(EnumChatFormatting.GRAY).append(" FKDR ")
						.append(fkdrColor(s.finalKills(), s.finalDeaths())).append(ratio(s.finalKills(), s.finalDeaths()))
						.append(EnumChatFormatting.GRAY).append(" WLR ")
						.append(EnumChatFormatting.WHITE).append(ratio(s.wins(), s.losses()));
			}
			return row.toString();
		} catch (Throwable t) {
			return name;
		}
	}

	/** The component form of {@link #decorateTabName(NetworkPlayerInfo, String)}
	 *  (tab names carry no click/hover events, so the formatted text is enough). */
	public static IChatComponent decorateTabName(NetworkPlayerInfo info, IChatComponent name) {
		if (!active() || name == null) {
			return name;
		}
		try {
			String in = name.getFormattedText();
			String out = decorateTabName(info, in);
			return out == null || out.equals(in) ? name : new ChatComponentText(out);
		} catch (Throwable t) {
			return name;
		}
	}

	// ---- levels ----

	/** The Level Head text for a player, or null while unknown (no key / still
	 *  loading / not a real Hypixel account). Requests the stats on first sight.
	 *  1.8.9: the two colour options are matched to the nearest chat colour. */
	public static IChatComponent levelHeadText(UUID id) {
		if (!active() || !Mods.bool(MOD, "levelHead")) {
			return null;
		}
		HypixelApi.Stats s = HypixelApi.get(id);
		if (s == null) {
			return null;
		}
		if (Mods.bool(MOD, "useBedwarsFormat") && inBedwars()) {
			return new ChatComponentText(star(s.bedwarsStar()));
		}
		IChatComponent out = new ChatComponentText("Level: ")
				.setChatStyle(new ChatStyle().setColor(nearest(Mods.color(MOD, "levelColor"))));
		out.appendSibling(new ChatComponentText(Integer.toString(s.networkLevel()))
				.setChatStyle(new ChatStyle().setColor(nearest(Mods.color(MOD, "levelNumberColor")))));
		return out;
	}

	/** {@link #levelHeadText} as a § string (for callers that draw plain FontRenderer text), or null. */
	public static String levelHeadFormatted(UUID id) {
		IChatComponent c = levelHeadText(id);
		return c == null ? null : c.getFormattedText();
	}

	private static String levelBadge(HypixelApi.Stats s) {
		if (Mods.bool(MOD, "useBedwarsFormat") && inBedwars()) {
			return star(s.bedwarsStar());
		}
		return nearest(Mods.color(MOD, "levelNumberColor")) + Integer.toString(s.networkLevel());
	}

	// Bed Wars prestige colours, one per hundred stars (1000+ stays gold).
	private static final EnumChatFormatting[] PRESTIGE = {
			EnumChatFormatting.GRAY, EnumChatFormatting.WHITE, EnumChatFormatting.GOLD, EnumChatFormatting.AQUA,
			EnumChatFormatting.DARK_GREEN, EnumChatFormatting.DARK_AQUA, EnumChatFormatting.DARK_RED,
			EnumChatFormatting.LIGHT_PURPLE, EnumChatFormatting.BLUE, EnumChatFormatting.DARK_PURPLE};

	// The star renders from the unicode glyph page (Hypixel uses the same glyph on 1.8).
	private static String star(int stars) {
		int tier = Math.max(0, stars) / 100;
		EnumChatFormatting c = tier < PRESTIGE.length ? PRESTIGE[tier] : EnumChatFormatting.GOLD;
		return c + "[" + stars + "✫]";
	}

	private static String ratio(int a, int b) {
		return String.format(Locale.ROOT, "%.2f", a / (double) Math.max(1, b));
	}

	private static EnumChatFormatting fkdrColor(int fk, int fd) {
		double r = fk / (double) Math.max(1, fd);
		return r >= 10 ? EnumChatFormatting.DARK_PURPLE : r >= 5 ? EnumChatFormatting.RED : r >= 3 ? EnumChatFormatting.GOLD
				: r >= 1 ? EnumChatFormatting.YELLOW : EnumChatFormatting.WHITE;
	}

	// ---- 1.8.9 text helpers (shared with HypixelBedwars / NickHider) ----

	/** s with every § code removed; "" for null. */
	static String strip(String s) {
		if (s == null) {
			return "";
		}
		String t = EnumChatFormatting.getTextWithoutFormattingCodes(s);
		return t == null ? "" : t;
	}

	// The vanilla chat palette by EnumChatFormatting.getColorIndex() (0..15) --
	// FontRenderer's colour table at full brightness. EnumChatFormatting has no
	// RGB of its own on 1.8.9.
	private static final int[] RGB = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	/** A chat colour's RGB, or -1 for a style code / reset. */
	static int rgbOf(EnumChatFormatting f) {
		if (f == null || !f.isColor()) {
			return -1;
		}
		int i = f.getColorIndex();
		return i >= 0 && i < RGB.length ? RGB[i] : -1;
	}

	/** The chat colour closest to an (A)RGB value. */
	static EnumChatFormatting nearest(int argb) {
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		EnumChatFormatting best = EnumChatFormatting.WHITE;
		long bestD = Long.MAX_VALUE;
		for (EnumChatFormatting f : EnumChatFormatting.values()) {
			int rgb = rgbOf(f);
			if (rgb < 0) {
				continue;
			}
			long dr = r - ((rgb >> 16) & 0xFF), dg = g - ((rgb >> 8) & 0xFF), db = b - (rgb & 0xFF);
			long d = dr * dr + dg * dg + db * db;
			if (d < bestD) {
				bestD = d;
				best = f;
			}
		}
		return best;
	}

	/** The formatting a § code character stands for, or null. */
	static EnumChatFormatting byCode(char c) {
		char lc = Character.toLowerCase(c);
		for (EnumChatFormatting f : EnumChatFormatting.values()) {
			String s = f.toString();   // "§c": the control string
			if (s.length() == 2 && s.charAt(1) == lc) {
				return f;
			}
		}
		return null;
	}

	/** The first n VISIBLE characters of a § string, its codes kept. */
	static String truncateFormatted(String s, int n) {
		StringBuilder out = new StringBuilder(s.length());
		int left = n;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < s.length()) {
				out.append(c).append(s.charAt(++i));
				continue;
			}
			if (left <= 0) {
				break;
			}
			out.append(c);
			left--;
		}
		return out.toString();
	}

	// ---- component surgery (keeps every style) ----
	//
	// 1.8.9: iterating an IChatComponent yields itself and deep copies of every
	// sibling (a translation yields its resolved children), each with a style
	// whose parent chain is intact, exactly what getFormattedText walks. Each
	// piece's own text is getUnformattedTextForChat(); createDeepCopy() flattens
	// its inherited style into explicit values, so a piece keeps its look when
	// re-parented under a new root.

	// Java 8 has no records: a final value class.
	private static final class Seg {
		private final ChatStyle style;
		private final String text;

		private Seg(ChatStyle style, String text) {
			this.style = style;
			this.text = text;
		}

		ChatStyle style() {
			return style.createShallowCopy();   // each use gets its own (appendSibling re-parents it)
		}

		String text() {
			return text;
		}
	}

	private static List<Seg> segments(IChatComponent c) {
		List<Seg> out = new ArrayList<>();
		for (IChatComponent part : c) {
			String text = part.getUnformattedTextForChat();
			if (text != null && !text.isEmpty()) {
				out.add(new Seg(part.getChatStyle().createDeepCopy(), text));
			}
		}
		return out;
	}

	/** c with its first n characters replaced by `with` (in the first segment's style). */
	private static IChatComponent replaceLeading(IChatComponent c, int n, String with) {
		List<Seg> segs = segments(c);
		if (segs.isEmpty()) {
			return c;
		}
		IChatComponent out = new ChatComponentText("");
		out.appendSibling(new ChatComponentText(with).setChatStyle(segs.get(0).style()));
		int skip = n;
		for (Seg s : segs) {
			String t = s.text();
			if (skip >= t.length()) {
				skip -= t.length();
				continue;
			}
			out.appendSibling(new ChatComponentText(t.substring(skip)).setChatStyle(s.style()));
			skip = 0;
		}
		return out;
	}
}
