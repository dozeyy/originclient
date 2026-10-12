package com.origin.client.client.mods;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
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

	static boolean isHypixelHost(ServerData server) {
		if (server == null || server.ip == null) {
			return false;
		}
		String host = server.ip.trim().toLowerCase(Locale.ROOT);
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
		onHypixel = mc.player != null && mc.level != null && isHypixelHost(mc.getCurrentServer());
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
			mc.player.displayClientMessage(Component.literal(
					"Hypixel levels and stats need your Hypixel API key: Right Shift > Mods > Hypixel > API Key."), false);
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

	private static String sidebarTitle(Minecraft mc) {
		Objective o = Era.sidebar(mc.level.getScoreboard());
		if (o == null) {
			return "";
		}
		String s = ChatFormatting.stripFormatting(o.getDisplayName().getString());
		return s == null ? "" : s.toUpperCase(Locale.ROOT);
	}

	private static boolean hotbarHas(Minecraft mc, String name) {
		for (int i = 0; i < 9; i++) {
			ItemStack st = mc.player.getInventory().getItem(i);
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
			Era.sendCommand(mc, cmd);
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
	static boolean allowMessage(Component message, boolean overlay) {
		if (overlay || !onHypixel) {
			return true;
		}
		String plain = ChatFormatting.stripFormatting(message.getString());
		if (plain == null) {
			return true;
		}
		plain = plain.trim();
		trackParty(plain);   // Team View reads the roster even with the Hypixel mod off
		if (!Mods.on(MOD)) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();

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
	static Component modifyMessage(Component message, boolean overlay) {
		if (overlay || !active() || !Mods.bool(MOD, "shortChannelNames")) {
			return message;
		}
		String raw = message.getString();
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
			MutableComponent row = Component.empty().append(out);
			if (levels) {
				row.append(Component.literal(" ")).append(levelBadge(s));
			}
			if (stats) {
				row.append(Component.literal(" FKDR ").withStyle(ChatFormatting.GRAY))
						.append(Component.literal(ratio(s.finalKills(), s.finalDeaths()))
								.withStyle(fkdrColor(s.finalKills(), s.finalDeaths())))
						.append(Component.literal(" WLR ").withStyle(ChatFormatting.GRAY))
						.append(Component.literal(ratio(s.wins(), s.losses())).withStyle(ChatFormatting.WHITE));
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
		return Component.literal("Level: ").withStyle(Style.EMPTY.withColor(Mods.color(MOD, "levelColor") & 0xFFFFFF))
				.append(Component.literal(Integer.toString(s.networkLevel()))
						.withStyle(Style.EMPTY.withColor(Mods.color(MOD, "levelNumberColor") & 0xFFFFFF)));
	}

	private static Component levelBadge(HypixelApi.Stats s) {
		if (Mods.bool(MOD, "useBedwarsFormat") && inBedwars()) {
			return star(s.bedwarsStar());
		}
		return Component.literal(Integer.toString(s.networkLevel()))
				.withStyle(Style.EMPTY.withColor(Mods.color(MOD, "levelNumberColor") & 0xFFFFFF));
	}

	// Bed Wars prestige colours, one per hundred stars (1000+ stays gold).
	private static final ChatFormatting[] PRESTIGE = {
			ChatFormatting.GRAY, ChatFormatting.WHITE, ChatFormatting.GOLD, ChatFormatting.AQUA,
			ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA, ChatFormatting.DARK_RED,
			ChatFormatting.LIGHT_PURPLE, ChatFormatting.BLUE, ChatFormatting.DARK_PURPLE};

	private static Component star(int stars) {
		int tier = Math.max(0, stars) / 100;
		ChatFormatting c = tier < PRESTIGE.length ? PRESTIGE[tier] : ChatFormatting.GOLD;
		return Component.literal("[" + stars + "✫]").withStyle(c);
	}

	private static String ratio(int a, int b) {
		return String.format(Locale.ROOT, "%.2f", a / (double) Math.max(1, b));
	}

	private static ChatFormatting fkdrColor(int fk, int fd) {
		double r = fk / (double) Math.max(1, fd);
		return r >= 10 ? ChatFormatting.DARK_PURPLE : r >= 5 ? ChatFormatting.RED : r >= 3 ? ChatFormatting.GOLD
				: r >= 1 ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
	}

	// ---- component surgery (keeps every style) ----

	private record Seg(Style style, String text) {
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
		MutableComponent out = Component.empty();
		int left = n;
		for (Seg s : segments(c)) {
			if (left <= 0) {
				break;
			}
			String t = s.text().length() <= left ? s.text() : s.text().substring(0, left);
			out.append(Component.literal(t).setStyle(s.style()));
			left -= t.length();
		}
		return out;
	}

	/** c with its first n characters replaced by `with` (in the first segment's style). */
	private static Component replaceLeading(Component c, int n, String with) {
		List<Seg> segs = segments(c);
		if (segs.isEmpty()) {
			return c;
		}
		MutableComponent out = Component.empty();
		out.append(Component.literal(with).setStyle(segs.get(0).style()));
		int skip = n;
		for (Seg s : segs) {
			String t = s.text();
			if (skip >= t.length()) {
				skip -= t.length();
				continue;
			}
			out.append(Component.literal(t.substring(skip)).setStyle(s.style()));
			skip = 0;
		}
		return out;
	}
}
