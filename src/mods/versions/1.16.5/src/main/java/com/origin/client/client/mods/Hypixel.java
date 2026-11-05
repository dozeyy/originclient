package com.origin.client.client.mods;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 *  - CHAT: hides / shortens Hypixel's own system lines (ServerMods feeds every
 *    incoming game message through {@link #allowMessage} / {@link #modifyMessage}).
 *    Hypixel sends ALL chat as unsigned system messages, so the "game" message
 *    events see every line. The match patterns are Hypixel's line formats; they
 *    are plain-text checks on the formatting-stripped line.
 *  - AUTOMATION: Auto Friend / Auto Tip / Auto Who send only the one command the
 *    player switched on, never anything else.
 *  - LEVELS: Level Head on nametags (LevelHeadMixin) and level / Bed Wars stats in
 *    the tab list (OriginTabList -> {@link #decorateTabName}); the numbers come
 *    from {@link HypixelApi} with the player's own API key.
 *
 * It also keeps the party roster Team View marks (Hypixel's party lines are the
 * only client-side source for it -- see {@link #partyMembers}).
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

	/**
	 * Connected to Hypixel: the typed address OR the server's brand says so
	 * (HypixelDetect), so a numeric IP, a DNS alias or an SRV name still counts --
	 * Freelook must stay off there however the player joined. Read-only.
	 */
	static boolean isHypixelServer(Minecraft mc) {
		ServerData sd = mc.getCurrentServer();
		return HypixelDetect.isHypixel(sd != null ? sd.ip : null, serverBrand(mc));
	}

	// Before 1.20.2 the brand the server sent on join is kept on the player.
	private static String serverBrand(Minecraft mc) {
		try {
			return mc.player != null ? mc.player.getServerBrand() : null;
		} catch (Throwable t) {
			return null;   // fail soft: the address check still applies
		}
	}

	// ---- what Hypixel doesn't allow ----

	private static final String FREELOOK_OFF = "Freelook is off on Hypixel (not allowed there)";

	/** Hypixel's rules ban freelook (looking around without turning), so Origin's
	 *  Freelook stays off while connected here -- whether or not the Hypixel mod is
	 *  on. Reads the server address itself, not the per-tick onHypixel cache, so it
	 *  holds from the very first tick after joining whatever order the end-of-tick
	 *  listeners run in. Only called while freelook would be on, so it costs nothing
	 *  the rest of the time. */
	public static boolean bansFreelook(Minecraft mc) {
		return mc.player != null && isHypixelServer(mc);
	}

	/** The one action-bar line when the Freelook key does nothing here. */
	public static void sayFreelookOff(Minecraft mc) {
		mc.player.displayClientMessage(new TextComponent(FREELOOK_OFF), true);
	}

	// ---- per tick ----

	private static final long TIP_EVERY_MS = 15L * 60L * 1000L;
	private static long nextTipAt = 0;
	private static boolean keyHintShown = false;
	private static int whoInTicks = -1;
	// HYPIXEL-3: Hypixel switches servers (lobby hops, game joins) by sending the player to
	// another backend. On 1.20.2+ that can pass through the configuration phase, where
	// Minecraft has no world or player for a moment. That gap is not leaving Hypixel: the
	// level cache, the party list and the tip timer survive it. Only another server's world,
	// or no world for LEAVE_GRACE_MS (back at the menus), ends the Hypixel session.
	private static final long LEAVE_GRACE_MS = 15_000L;
	private static boolean session = false;   // on Hypixel, short no-world gaps included
	private static long gapSince = 0;

	static void tick(Minecraft mc) {
		boolean inWorld = mc.player != null && mc.level != null;
		onHypixel = inWorld && isHypixelServer(mc);
		if (!onHypixel) {
			if (session) {
				long now = System.currentTimeMillis();
				if (gapSince == 0) {
					gapSince = now;
				}
				// another server's world, or no world for longer than a switch takes: really left
				if (inWorld || now - gapSince >= LEAVE_GRACE_MS) {
					session = false;
					gapSince = 0;
					onLeave();
				}
			}
			return;
		}
		gapSince = 0;
		boolean joined = !session;
		session = true;
		HypixelApi.optionKey = Mods.text(MOD, "apiKey").trim();
		// Waypoints are kept per Hypixel game and map (JG-8): the world's one /locraw (util/
		// HypixelLocraw, asked for Quickplay too) also names the place, so ask it while Waypoints is on.
		com.origin.client.client.util.HypixelLocraw.waypointsWant = Mods.on("waypoints");
		if (joined) {
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
			mc.player.displayClientMessage(new TextComponent(
					"Hypixel levels and stats need your Hypixel API key: " + menuKeyName() + " > Mods > Hypixel > API Key."), false);
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

	/** The player's own Origin-menu key ("Right Shift" unless rebound in Controls), for hints. */
	private static String menuKeyName() {
		try {
			return com.origin.client.client.OriginKeyBindings.openModMenu.getTranslatedKeyMessage().getString();
		} catch (Throwable t) {
			return "Right Shift";
		}
	}

	private static void onLeave() {
		inBedwars = inLobby = inSkyblock = false;
		whoInTicks = -1;
		synchronized (PARTY) {
			PARTY.clear();
		}
		HypixelApi.clear();
		com.origin.client.client.util.HypixelLocraw.waypointsWant = false;
	}

	private static String sidebarTitle(Minecraft mc) {
		Objective o = mc.level.getScoreboard().getDisplayObjective(1);   // 1 = sidebar (no DisplaySlot enum before 1.20.2)
		if (o == null) {
			return "";
		}
		String s = ChatFormatting.stripFormatting(o.getDisplayName().getString());
		return s == null ? "" : s.toUpperCase(Locale.ROOT);
	}

	private static boolean hotbarHas(Minecraft mc, String name) {
		for (int i = 0; i < 9; i++) {
			ItemStack st = mc.player.inventory.getItem(i);
			if (!st.isEmpty()) {
				String n = ChatFormatting.stripFormatting(st.getHoverName().getString());
				if (n != null && n.contains(name)) {
					return true;
				}
			}
		}
		return false;
	}

	private static void command(Minecraft mc, String cmd) {
		if (mc.player != null && mc.getConnection() != null) {
			mc.player.chat("/" + cmd);   // 1.16.5: commands go out as "/" chat (sendCommand is 1.19+)
		}
	}

	// ---- chat ----

	private static final Pattern FRIEND_REQUEST = Pattern.compile("^Friend request from (?:\\[[^\\]]+\\] )?(\\w{1,16})");
	private static final Pattern GG = Pattern.compile(
			"^(?:\\[[^\\]]+\\] )*\\w{1,16}: (?:gg|good game|gg wp|ggwp|gf|ggs)[!. ]*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern FRIEND_GUILD_JOIN = Pattern.compile("^(?:Friend|Guild) > \\w{1,16} joined\\.$");
	private static final Pattern FRIEND_GUILD_LEAVE = Pattern.compile("^(?:Friend|Guild) > \\w{1,16} left\\.$");
	// Hypixel's own lobby announcements only: "[MVP+] Name joined the lobby!",
	// ">>> [MVP++] Name slid into the lobby! <<<". A whole-line match, so a player
	// typing "joined the lobby!" in chat ("[VIP] Name: ...") is never hidden.
	private static final Pattern LOBBY_STATUS = Pattern.compile(
			"^\\s*(?:>>> )?\\[[^\\]]+\\] \\w{1,16} (?:joined|[A-Za-z]+ into) the lobby!(?: <<<)?\\s*$");

	/** False = drop this incoming game message. Also drives the automation and
	 *  the party roster, which need to see every line (even ones it then hides). */
	static boolean allowMessage(Component message, boolean overlay) {
		if (overlay || !onHypixel) {
			return true;
		}
		String plain = ChatFormatting.stripFormatting(message.getString());
		if (plain == null) {
			return true;
		}
		plain = plain.trim();
		// Hypixel sends friend requests and /party lists as ONE boxed message ("-----" rule,
		// lines, "-----" rule): every line is matched on its own, the rules dropped.
		List<String> lines = lines(plain);
		for (String line : lines) {
			trackParty(line);   // Team View reads the roster even with the Hypixel mod off
		}
		if (!Mods.on(MOD)) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();

		if (Mods.bool(MOD, "autoFriend")) {
			for (String line : lines) {
				Matcher fr = FRIEND_REQUEST.matcher(line);
				if (fr.find()) {
					command(mc, "friend accept " + fr.group(1));
					break;
				}
			}
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
				&& LOBBY_STATUS.matcher(plain).matches()) {
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

	// Each short form is the word's first letter + the same " > ": dropVisible keeps that
	// letter and drops the rest of the word.
	private static final String[][] CHANNELS = {
			{"Party > ", "P > "}, {"Guild > ", "G > "}, {"Officer > ", "O > "}};

	/** Short Channel Names: rewrites the channel prefix, keeping every style. */
	static Component modifyMessage(Component message, boolean overlay) {
		if (overlay || !active() || !Mods.bool(MOD, "shortChannelNames")) {
			return message;
		}
		// Matched on the stripped line: Hypixel's channel prefix comes as "§9Party §8> ".
		String plain = ChatFormatting.stripFormatting(message.getString());
		if (plain == null) {
			return message;
		}
		for (String[] ch : CHANNELS) {
			if (plain.startsWith(ch[0])) {
				return dropVisible(message, 1, 1 + ch[0].length() - ch[1].length());
			}
		}
		return message;
	}

	// ---- party roster (for Team View) ----

	private static final Set<String> PARTY = new HashSet<>();
	private static final Pattern RANK = Pattern.compile("\\[[^\\]]*\\]");
	// ONE player as Hypixel's own party lines print them: an optional rank tag, then the name.
	// The patterns that open on a name use it instead of ".+?", so player chat never matches:
	// "[VIP] Bob: x Steve joined the party." has "Bob:" before the words, which no single name
	// does, and a ranked or unranked chat line always starts with its sender (bug sweep 2, F-7:
	// any player could add names to your roster, and Team View marked them in lobbies).
	private static final String ONE = "((?:\\[[^\\]]+\\] )?\\w{1,16})";
	private static final Pattern P_JOINED_THEIRS = Pattern.compile("^You have joined " + ONE + "'s? party!$");
	private static final Pattern P_JOINED = Pattern.compile("^" + ONE + " joined the party\\.$");
	private static final Pattern P_LEFT = Pattern.compile("^" + ONE
			+ " (?:has left the party|has been removed from the party|was removed from your party because they disconnected)\\.?$");
	private static final Pattern P_KICKED_OFFLINE = Pattern.compile("^Kicked " + ONE + " because they were offline\\.?$");
	// the leader's own /p disband (HYPIXEL-2); whole line on one name, so player chat can't fake it
	private static final Pattern P_DISBANDED = Pattern.compile("^" + ONE + " has disbanded the party!$");
	private static final Pattern P_WITH = Pattern.compile("^You'll be partying with: (.+)$");
	private static final Pattern P_LIST = Pattern.compile("^Party (?:Leader|Moderators|Members): (.+)$");

	/** Lower-case names of the players in your Hypixel party (yourself excluded). */
	public static Set<String> partyMembers() {
		synchronized (PARTY) {
			return new HashSet<>(PARTY);
		}
	}

	/** The message's lines, trimmed, without blank lines or Hypixel's "-----" box rules. */
	private static List<String> lines(String plain) {
		List<String> out = new ArrayList<>();
		for (String line : plain.split("\n")) {
			line = line.trim();
			if (!line.isEmpty() && !line.matches("[-\u25AC\\s]+")) {
				out.add(line);
			}
		}
		return out;
	}

	private static void trackParty(String line) {
		if (line.startsWith("You left the party") || line.startsWith("The party was disbanded")
				|| P_DISBANDED.matcher(line).matches()
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
		String me = Minecraft.getInstance().player != null
				? Minecraft.getInstance().player.getGameProfile().getName().toLowerCase(Locale.ROOT) : "";
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

	/** OriginTabList's per-row name hook: strips the guild tag and appends the
	 *  level / Bed Wars stats the player switched on. Any failure returns the
	 *  name untouched. */
	public static Component decorateTabName(PlayerInfo info, Component name) {
		if (!active()) {
			return name;
		}
		try {
			Component out = name;
			if (Mods.bool(MOD, "removeGuildOnTab")) {
				Matcher m = GUILD_TAG.matcher(out.getString());
				if (m.find() && m.start() > 0 && out.getString().indexOf('§') < 0) {
					out = truncate(out, m.start());
				}
			}
			boolean levels = Mods.bool(MOD, "levelsInTab");
			boolean stats = Mods.bool(MOD, "bedwarsStats") && inBedwars();
			if (!levels && !stats) {
				return out;
			}
			HypixelApi.Stats s = HypixelApi.get(info.getProfile().getId());
			if (s == null) {
				return out;
			}
			MutableComponent row = new TextComponent("").append(out);
			if (levels) {
				row.append(new TextComponent(" ")).append(levelBadge(s));
			}
			if (stats) {
				row.append(new TextComponent(" FKDR ").withStyle(ChatFormatting.GRAY))
						.append(new TextComponent(ratio(s.finalKills(), s.finalDeaths()))
								.withStyle(fkdrColor(s.finalKills(), s.finalDeaths())))
						.append(new TextComponent(" WLR ").withStyle(ChatFormatting.GRAY))
						.append(new TextComponent(ratio(s.wins(), s.losses())).withStyle(ChatFormatting.WHITE));
			}
			return row;
		} catch (Throwable t) {
			return name;
		}
	}

	// ---- levels ----

	/** The Level Head text for a player, or null while unknown (no key / still
	 *  loading / not a real Hypixel account). Requests the stats on first sight. */
	public static Component levelHeadText(UUID id) {
		if (!active() || !Mods.bool(MOD, "levelHead")) {
			return null;
		}
		HypixelApi.Stats s = HypixelApi.get(id);
		if (s == null) {
			return null;
		}
		if (Mods.bool(MOD, "useBedwarsFormat") && inBedwars()) {
			return star(s.bedwarsStar());
		}
		return new TextComponent("Level: ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(Mods.color(MOD, "levelColor") & 0xFFFFFF)))
				.append(new TextComponent(Integer.toString(s.networkLevel()))
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(Mods.color(MOD, "levelNumberColor") & 0xFFFFFF))));
	}

	private static Component levelBadge(HypixelApi.Stats s) {
		if (Mods.bool(MOD, "useBedwarsFormat") && inBedwars()) {
			return star(s.bedwarsStar());
		}
		return new TextComponent(Integer.toString(s.networkLevel()))
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(Mods.color(MOD, "levelNumberColor") & 0xFFFFFF)));
	}

	// Bed Wars prestige colours, one per hundred stars (1000+ stays gold).
	private static final ChatFormatting[] PRESTIGE = {
			ChatFormatting.GRAY, ChatFormatting.WHITE, ChatFormatting.GOLD, ChatFormatting.AQUA,
			ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA, ChatFormatting.DARK_RED,
			ChatFormatting.LIGHT_PURPLE, ChatFormatting.BLUE, ChatFormatting.DARK_PURPLE};

	private static Component star(int stars) {
		int tier = Math.max(0, stars) / 100;
		ChatFormatting c = tier < PRESTIGE.length ? PRESTIGE[tier] : ChatFormatting.GOLD;
		return new TextComponent("[" + stars + "✫]").withStyle(c);
	}

	// Two decimals by hand: the tab list calls this twice per row every frame in
	// Bed Wars, and String.format builds a Formatter each call. Stats are never negative.
	private static String ratio(int a, int b) {
		long n = Math.round(Math.max(0, a) * 100.0 / Math.max(1, b));
		long frac = n % 100;
		return (n / 100) + (frac < 10 ? ".0" : ".") + frac;
	}

	private static ChatFormatting fkdrColor(int fk, int fd) {
		double r = fk / (double) Math.max(1, fd);
		return r >= 10 ? ChatFormatting.DARK_PURPLE : r >= 5 ? ChatFormatting.RED : r >= 3 ? ChatFormatting.GOLD
				: r >= 1 ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
	}

	// ---- component surgery (keeps every style) ----

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Seg {
		private final Style style;
		private final String text;

		private Seg(Style style, String text) {
			this.style = style;
			this.text = text;
		}

		public Style style() {
			return style;
		}

		public String text() {
			return text;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Seg)) {
				return false;
			}
			Seg other = (Seg) obj;
			return java.util.Objects.equals(this.style, other.style)
					&& java.util.Objects.equals(this.text, other.text);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(style, text);
		}
	}

	private static List<Seg> segments(Component c) {
		List<Seg> out = new ArrayList<>();
		c.visit((style, text) -> {
			if (!text.isEmpty()) {
				out.add(new Seg(style, text));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	/** The first n characters of c, styles intact. */
	private static Component truncate(Component c, int n) {
		MutableComponent out = new TextComponent("");
		int left = n;
		for (Seg s : segments(c)) {
			if (left <= 0) {
				break;
			}
			String t = s.text().length() <= left ? s.text() : s.text().substring(0, left);
			out.append(new TextComponent(t).setStyle(s.style()));
			left -= t.length();
		}
		return out;
	}

	/**
	 * c without its visible characters [from, to), every style and § code kept in place. Short
	 * Channel Names drops "arty" from "Party > ", so "P" keeps the word's colour and " > " its own.
	 * Hypixel's lines carry § codes inside the text, so a code is stepped over, never counted or
	 * dropped (bug sweep 2, F-3: the old prefix swap gave up on any line holding a §, which is
	 * every Hypixel channel line).
	 */
	private static Component dropVisible(Component c, int from, int to) {
		MutableComponent out = new TextComponent("");
		int seen = 0;
		for (Seg s : segments(c)) {
			String t = s.text();
			StringBuilder kept = new StringBuilder(t.length());
			for (int i = 0; i < t.length(); i++) {
				if (isCode(t, i)) {
					kept.append(t, i, i + 2);
					i++;
					continue;
				}
				if (seen < from || seen >= to) {
					kept.append(t.charAt(i));
				}
				seen++;
			}
			if (kept.length() > 0) {
				out.append(new TextComponent(kept.toString()).setStyle(s.style()));
			}
		}
		return out;
	}

	/** A § colour/format code starts at t[i]: exactly what stripFormatting removes. */
	private static boolean isCode(String t, int i) {
		return t.charAt(i) == '§' && i + 1 < t.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(t.charAt(i + 1)) >= 0;
	}
}
