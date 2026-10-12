package com.origin.client.client.mods;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel Bed Wars (Lunar parity): Lunar's separate "Hypixel Bedwars" mod. Its
 * option set is Lunar's published schema (Apollo mods/impl/ModHypixelBedwars):
 * Colored Beds, Enable Practice Color, Hardcore Hearts, Hide Food Bar, Hide Armor
 * Bar, Custom Trap Alert, Mute Alert Sound, Alert Primary / Sub Color. On top of
 * that, the two HUDs Lunar's own posts list for the mod: the Resource Counter
 * and the Teammates HUD (drawn by hud/BedwarsHud).
 *
 * Everything is gated on {@link #active()}: the mod on, connected to
 * *.hypixel.net (Hypixel.onHypixel), and IN a Bed Wars game -- the sidebar
 * reads "BED WARS" (Hypixel.inBedwars) AND lists the teams with their bed
 * status ("R Red: ✔ YOU"), which only the in-game sidebar does (the pre-game
 * waiting room and the lobby don't). Nowhere else does any of this run.
 *
 * Purely client side: it reads the sidebar, the tab list, the scoreboard teams
 * and Hypixel's own chat / title lines, and never sends anything.
 *
 * 1.16.5: scoreboard display slots are ints (0 list, 1 sidebar, 2 below name;
 * the DisplaySlot enum and PlayerScoreEntry/ScoreHolder are 1.20.2+), a sidebar
 * row is a Score whose owner is its text (rows starting with '#' are hidden, as
 * vanilla's sidebar skips them), and chat arrives through ChatEvents.
 */
public final class HypixelBedwars {
	public static final String MOD = "hypixelbedwars";

	private HypixelBedwars() {
	}

	// ---- game state (refreshed once per tick by tick(); render thread only) ----

	private static ClientLevel sessionLevel = null;
	private static boolean inGame = false, practice = false;
	private static boolean ownBedKnown = false, ownBedAlive = true, ownBedLostChat = false;
	private static ChatFormatting ownTeamColor = null;
	private static int sessionTicks = 0;   // ticks since this game (this world) was first detected

	/** The mod is on, we're on Hypixel, and in a Bed Wars game (or practice). */
	public static boolean active() {
		return (inGame || practice) && Hypixel.onHypixel() && Mods.on(MOD);
	}

	/** In a real Bed Wars game (not practice) -- the HUDs and hearts only make sense there. */
	public static boolean inGame() {
		return inGame && Hypixel.onHypixel() && Mods.on(MOD);
	}

	// Hypixel's in-game sidebar team rows: "R Red: ✔", "B Blue: ✗", "G Green: 2",
	// with " YOU" after your own team. Formatting stripped and Hypixel's invisible
	// spacer glyphs dropped before matching (see cleanLine).
	// Scoreboard display slots before the 1.20.2 DisplaySlot enum.
	private static final int SLOT_LIST = 0, SLOT_SIDEBAR = 1, SLOT_BELOW_NAME = 2;

	private static final Pattern TEAM_LINE = Pattern.compile(
			"^([A-Z]) (Red|Blue|Green|Yellow|Aqua|White|Pink|Gray|Grey): ?(.*)$");

	static void tick(Minecraft mc) {
		if (mc.level != sessionLevel) {
			resetSession();
			sessionLevel = mc.level;
		}
		if (mc.player == null || mc.level == null || !Hypixel.inBedwars()) {
			inGame = practice = false;
			return;
		}
		Scoreboard sb = mc.level.getScoreboard();
		Objective side = sb.getDisplayObjective(SLOT_SIDEBAR);
		if (side == null) {
			inGame = practice = false;
			return;
		}
		String title = clean(side.getDisplayName().getString()).toUpperCase(Locale.ROOT);
		int teams = 0;
		boolean ownKnown = false, ownAlive = true, practiceLine = false;
		ChatFormatting ownColor = null;
		for (Score e : sb.getPlayerScores(side)) {
			String owner = e.getOwner();
			if (owner == null || owner.startsWith("#")) {
				continue;   // vanilla's sidebar skips these too
			}
			String line = cleanLine(PlayerTeam.formatNameForTeam(sb.getPlayersTeam(owner), new TextComponent(owner)).getString());
			if (line.toUpperCase(Locale.ROOT).contains("PRACTICE")) {
				practiceLine = true;
			}
			Matcher m = TEAM_LINE.matcher(line);
			if (!m.matches()) {
				continue;
			}
			teams++;
			String status = m.group(3);
			if (status.endsWith("YOU")) {
				ownKnown = true;
				ownAlive = status.indexOf('✔') >= 0 || status.indexOf('✓') >= 0;
				ownColor = colorOfTeamName(m.group(2));
			}
		}
		boolean wasActive = inGame || practice;
		inGame = teams >= 2;
		practice = !inGame && (title.contains("PRACTICE") || practiceLine);
		ownBedKnown = ownKnown;
		ownBedAlive = ownAlive;
		ChatFormatting mine = teamColor(sb.getPlayersTeam(mc.player.getScoreboardName()));
		ownTeamColor = mine != null ? mine : ownColor;
		if (!(inGame || practice)) {
			return;
		}
		if (!wasActive) {
			sessionTicks = 0;
		}
		sessionTicks++;
		recordSpawns(mc, sb);
		trackTeammates(mc, sb);
	}

	private static void resetSession() {
		inGame = practice = false;
		ownBedKnown = false;
		ownBedAlive = true;
		ownBedLostChat = false;
		ownTeamColor = null;
		sessionTicks = 0;
		SPAWNS.clear();
		BED_TEAM.clear();
		ROSTER.clear();
	}

	// ---- chat ----

	// Hypixel's bed-break line, e.g. "BED DESTRUCTION > Your Bed was destroyed by Name!"
	// (the verb varies -- "was spontaneously combusted by" and friends -- the prefix doesn't).
	private static final String OWN_BED_LOST = "BED DESTRUCTION > Your Bed";

	/** Watches Hypixel's own chat for your bed breaking (backs up the sidebar's ✔). Never hides anything. */
	static void onMessage(Component message, boolean overlay) {
		if (overlay || !(inGame || practice) || !Hypixel.onHypixel()) {
			return;
		}
		String plain = ChatFormatting.stripFormatting(message.getString());
		if (plain != null && plain.trim().startsWith(OWN_BED_LOST)) {
			ownBedLostChat = true;
		}
	}

	// ---- Hardcore Hearts / Hide Food Bar / Hide Armor Bar (HypixelBedwarsGuiMixin) ----

	/** True once your team's bed is gone: the sidebar no longer shows ✔ for YOU, or
	 *  Hypixel announced "Your Bed was destroyed". */
	public static boolean hardcoreHearts() {
		return inGame() && Mods.bool(MOD, "hardcoreHearts")
				&& (ownBedLostChat || (ownBedKnown && !ownBedAlive));
	}

	public static boolean hideFood() {
		return active() && Mods.bool(MOD, "hideFoodBar");
	}

	public static boolean hideArmor() {
		return active() && Mods.bool(MOD, "hideArmorBar");
	}

	// ---- Colored Beds (HypixelBedwarsBedMixin) ----
	//
	// Hypixel's beds reach a modern client all one colour, with nothing on the
	// block saying whose base it is. What the client DOES know is where every
	// player stands when the game starts: Hypixel teleports each team onto its own
	// island, next to its own bed. So for the first few seconds of a game each
	// player's position is recorded with their team colour, and every bed takes
	// the colour of the nearest recorded spawn. The window is short on purpose --
	// later, a rusher bridging over would be "seen" next to someone else's bed.
	// A bed with no recorded spawn near it (a far island whose players were out of
	// entity range at the start) simply keeps its own colour.

	private static final int SPAWN_WINDOW_TICKS = 100;          // 5 s after the game appears
	private static final double SPAWN_RADIUS = 28.0;            // bed <-> spawn, blocks (horizontal)

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Spawn {
		private final double x;
		private final double z;
		private final DyeColor dye;

		private Spawn(double x, double z, DyeColor dye) {
			this.x = x;
			this.z = z;
			this.dye = dye;
		}

		public double x() {
			return x;
		}

		public double z() {
			return z;
		}

		public DyeColor dye() {
			return dye;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Spawn)) {
				return false;
			}
			Spawn other = (Spawn) obj;
			return Double.compare(this.x, other.x) == 0
					&& Double.compare(this.z, other.z) == 0
					&& java.util.Objects.equals(this.dye, other.dye);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(x, z, dye);
		}
	}

	private static final Map<UUID, Spawn> SPAWNS = new HashMap<>();
	private static final Map<Long, Optional<DyeColor>> BED_TEAM = new HashMap<>();

	private static void recordSpawns(Minecraft mc, Scoreboard sb) {
		if (sessionTicks > SPAWN_WINDOW_TICKS) {
			return;
		}
		for (AbstractClientPlayer p : mc.level.players()) {
			if (p.isSpectator() || SPAWNS.containsKey(p.getUUID())) {
				continue;
			}
			ChatFormatting c = p == mc.player && ownTeamColor != null ? ownTeamColor
					: teamColor(sb.getPlayersTeam(p.getScoreboardName()));
			DyeColor dye = dyeOf(c);
			if (dye != null) {
				SPAWNS.put(p.getUUID(), new Spawn(p.getX(), p.getZ(), dye));
				BED_TEAM.clear();   // a new spawn can change which one is nearest
			}
		}
	}

	/** The team colour to draw this bed in, or null to leave it as it is. */
	public static DyeColor bedColor(BedBlockEntity bed) {
		if (!active() || !Mods.bool(MOD, "coloredBeds") || (practice && !Mods.bool(MOD, "practiceColor"))) {
			return null;
		}
		BlockPos pos = bed.getBlockPos();
		Optional<DyeColor> cached = BED_TEAM.get(pos.asLong());
		if (cached == null) {
			cached = Optional.ofNullable(nearestSpawn(pos));
			// Don't pin "unknown" while spawns are still being collected.
			if (cached.isPresent() || sessionTicks > SPAWN_WINDOW_TICKS) {
				BED_TEAM.put(pos.asLong(), cached);
			}
		}
		return cached.orElse(null);
	}

	private static DyeColor nearestSpawn(BlockPos pos) {
		double bx = pos.getX() + 0.5, bz = pos.getZ() + 0.5;
		double best = SPAWN_RADIUS * SPAWN_RADIUS;
		DyeColor out = null;
		for (Spawn s : SPAWNS.values()) {
			double dx = s.x() - bx, dz = s.z() - bz;
			double d = dx * dx + dz * dz;
			if (d < best) {
				best = d;
				out = s.dye();
			}
		}
		return out;
	}

	// ---- Custom Trap Alert / Mute Alert Sound (HypixelBedwarsGuiMixin, HypixelBedwarsSoundMixin) ----
	//
	// When an enemy sets off one of your traps Hypixel shows the title
	// "TRAP TRIGGERED!" with a subtitle naming the trap, and plays the enderman
	// teleport sound. Custom Trap Alert redraws that title and subtitle in the
	// player's Alert Primary / Sub colours (keeping bold and the text); Mute Alert
	// Sound drops the sound that comes with it. Any other title is left alone.

	private static final long ALERT_SOUND_WINDOW_MS = 3000;
	private static final ResourceLocation ALERT_SOUND = new ResourceLocation("entity.enderman.teleport");
	private static long trapAtMs = Long.MIN_VALUE / 2;

	private static boolean isTrapTitle(Component title) {
		String s = ChatFormatting.stripFormatting(title.getString());
		if (s == null) {
			return false;
		}
		s = s.toUpperCase(Locale.ROOT);
		return s.contains("TRAP") && (s.contains("TRIGGERED") || s.contains("SET OFF"));
	}

	/** Gui.setTitle: the title to show instead (the same one unless it's a trap alert). */
	public static Component onTitle(Component title) {
		if (title == null || !active() || !isTrapTitle(title)) {
			return title;
		}
		trapAtMs = Util.getMillis();
		return Mods.bool(MOD, "customTrapAlert") ? recolor(title, Mods.color(MOD, "alertPrimaryColor")) : title;
	}

	/** Gui.setSubtitle (and the subtitle already showing when a trap title lands):
	 *  Hypixel's trap subtitles all name the trap, so "trap" in the text marks them. */
	public static Component onSubtitle(Component subtitle) {
		if (subtitle == null || !active() || !Mods.bool(MOD, "customTrapAlert")) {
			return subtitle;
		}
		String s = ChatFormatting.stripFormatting(subtitle.getString());
		if (s == null || !s.toLowerCase(Locale.ROOT).contains("trap")) {
			return subtitle;
		}
		return recolor(subtitle, Mods.color(MOD, "alertSubColor"));
	}

	/** SoundManager.play: true = drop this sound (the trap alert's, with Mute Alert Sound on). */
	public static boolean muteSound(SoundInstance sound) {
		if (sound == null || !active() || !Mods.bool(MOD, "muteAlertSound")) {
			return false;
		}
		return Util.getMillis() - trapAtMs < ALERT_SOUND_WINDOW_MS && ALERT_SOUND.equals(sound.getLocation());
	}

	/** c in one colour, every other style (bold, italic) kept; legacy § codes in the text dropped. */
	private static Component recolor(Component c, int argb) {
		TextColor color = TextColor.fromRgb(argb & 0xFFFFFF);
		MutableComponent out = new TextComponent("");
		c.visit((style, text) -> {
			String t = ChatFormatting.stripFormatting(text);
			if (t != null && !t.isEmpty()) {
				out.append(new TextComponent(t).setStyle(style.withColor(color)));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	// ---- Teammates HUD data ----
	//
	// Everyone seen on your team colour (and not spectating) during this game joins
	// the roster for the rest of it, so a teammate who is final-killed (Hypixel
	// moves them to spectator, and may move them off the team) or who disconnects
	// stays on the list, shown as out instead of vanishing.

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Teammate {
		private final UUID id;
		private final String name;
		private final int color;
		private final int health;
		private final boolean alive;
		private final boolean online;
		private final PlayerInfo info;

		public Teammate(UUID id, String name, int color, int health, boolean alive, boolean online, PlayerInfo info) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.health = health;
			this.alive = alive;
			this.online = online;
			this.info = info;
		}

		public UUID id() {
			return id;
		}

		public String name() {
			return name;
		}

		public int color() {
			return color;
		}

		public int health() {
			return health;
		}

		public boolean alive() {
			return alive;
		}

		public boolean online() {
			return online;
		}

		public PlayerInfo info() {
			return info;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Teammate)) {
				return false;
			}
			Teammate other = (Teammate) obj;
			return java.util.Objects.equals(this.id, other.id)
					&& java.util.Objects.equals(this.name, other.name)
					&& this.color == other.color
					&& this.health == other.health
					&& this.alive == other.alive
					&& this.online == other.online
					&& java.util.Objects.equals(this.info, other.info);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(id, name, color, health, alive, online, info);
		}
	}

	private static final Map<UUID, String> ROSTER = new LinkedHashMap<>();

	private static void trackTeammates(Minecraft mc, Scoreboard sb) {
		if (!inGame || ownTeamColor == null || mc.getConnection() == null) {
			return;
		}
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			UUID id = info.getProfile().getId();
			if (id.equals(mc.player.getUUID()) || info.getGameMode() == GameType.SPECTATOR || ROSTER.containsKey(id)) {
				continue;
			}
			if (teamColor(sb.getPlayersTeam(info.getProfile().getName())) == ownTeamColor) {
				ROSTER.put(id, info.getProfile().getName());
			}
		}
	}

	/** Your teammates (you excluded), in the order they were first seen. */
	public static List<Teammate> teammates(Minecraft mc) {
		List<Teammate> out = new ArrayList<>();
		if (mc.level == null || mc.getConnection() == null) {
			return out;
		}
		Scoreboard sb = mc.level.getScoreboard();
		Integer rgb = ownTeamColor != null ? ownTeamColor.getColor() : null;
		int color = 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
		for (Map.Entry<UUID, String> e : ROSTER.entrySet()) {
			PlayerInfo info = mc.getConnection().getPlayerInfo(e.getKey());
			boolean online = info != null;
			boolean alive = online && info.getGameMode() != GameType.SPECTATOR;
			out.add(new Teammate(e.getKey(), e.getValue(), color, alive ? health(mc, sb, e.getValue(), e.getKey()) : 0,
					alive, online, info));
		}
		return out;
	}

	/** A player's health in half-hearts as Hypixel shows it: the health objective
	 *  under nametags / in the tab list (scores reach the client for every player,
	 *  near or far), else the entity's own health if it's loaded; -1 = unknown. */
	private static int health(Minecraft mc, Scoreboard sb, String name, UUID id) {
		for (int slot : new int[]{SLOT_BELOW_NAME, SLOT_LIST}) {
			Objective o = sb.getDisplayObjective(slot);
			if (o == null) {
				continue;
			}
			boolean isHealth = o.getRenderType() == ObjectiveCriteria.RenderType.HEARTS
					|| o.getDisplayName().getString().contains("❤");
			if (!isHealth) {
				continue;
			}
			// getPlayerScores(name) only reads (getOrCreatePlayerScore would add a row).
			Score s = sb.getPlayerScores(name).get(o);
			if (s != null) {
				return s.getScore();
			}
		}
		Player p = mc.level.getPlayerByUUID(id);
		return p != null ? Mth.ceil(p.getHealth()) : -1;
	}

	// ---- team colours ----

	/** A scoreboard team's colour: its own colour field, else the first colour in
	 *  its prefix (Hypixel colours Bed Wars teams through the "R " / "B " prefix). */
	static ChatFormatting teamColor(PlayerTeam team) {
		if (team == null) {
			return null;
		}
		ChatFormatting c = team.getColor();
		if (c != null && c.isColor()) {
			return c;
		}
		return firstColor(team.getPlayerPrefix());
	}

	private static ChatFormatting firstColor(Component c) {
		ChatFormatting[] found = {null};
		c.visit((style, text) -> {
			// legacy "§c" codes inside the text come before its letters
			for (int i = 0; i + 1 < text.length(); i++) {
				if (text.charAt(i) == '§') {
					ChatFormatting f = ChatFormatting.getByCode(text.charAt(i + 1));
					if (f != null && f.isColor()) {
						found[0] = f;
						return Optional.of(Boolean.TRUE);
					}
				}
			}
			String visible = ChatFormatting.stripFormatting(text);
			if (visible != null && !visible.trim().isEmpty() && style.getColor() != null) {
				found[0] = byRgb(style.getColor().getValue());
				if (found[0] != null) {
					return Optional.of(Boolean.TRUE);
				}
			}
			return Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}

	private static ChatFormatting byRgb(int rgb) {
		for (ChatFormatting f : ChatFormatting.values()) {
			Integer v = f.getColor();
			if (f.isColor() && v != null && v == (rgb & 0xFFFFFF)) {
				return f;
			}
		}
		return null;
	}

	private static ChatFormatting colorOfTeamName(String name) {
		switch (name) {
			case "Red": return ChatFormatting.RED;
			case "Blue": return ChatFormatting.BLUE;
			case "Green": return ChatFormatting.GREEN;
			case "Yellow": return ChatFormatting.YELLOW;
			case "Aqua": return ChatFormatting.AQUA;
			case "White": return ChatFormatting.WHITE;
			case "Pink": return ChatFormatting.LIGHT_PURPLE;
			default: return ChatFormatting.DARK_GRAY;   // Gray / Grey
		}
	}

	/** The bed dye that matches a team's chat colour (Hypixel's eight teams first). */
	static DyeColor dyeOf(ChatFormatting c) {
		if (c == null) {
			return null;
		}
		switch (c) {
			case RED: case DARK_RED: return DyeColor.RED;
			case BLUE: case DARK_BLUE: return DyeColor.BLUE;
			case GREEN: return DyeColor.LIME;
			case DARK_GREEN: return DyeColor.GREEN;
			case YELLOW: return DyeColor.YELLOW;
			case GOLD: return DyeColor.ORANGE;
			case AQUA: return DyeColor.LIGHT_BLUE;
			case DARK_AQUA: return DyeColor.CYAN;
			case WHITE: return DyeColor.WHITE;
			case LIGHT_PURPLE: return DyeColor.PINK;
			case DARK_PURPLE: return DyeColor.PURPLE;
			case GRAY: case DARK_GRAY: return DyeColor.GRAY;
			case BLACK: return DyeColor.BLACK;
			default: return null;
		}
	}

	// ---- text helpers ----

	private static String clean(String s) {
		String t = ChatFormatting.stripFormatting(s);
		return t == null ? "" : t.trim();
	}

	/** A sidebar line with formatting stripped and every character Hypixel uses as
	 *  an invisible spacer (emoji, private-use glyphs) dropped, spaces collapsed. */
	private static String cleanLine(String s) {
		String t = clean(s);
		StringBuilder b = new StringBuilder(t.length());
		for (int i = 0; i < t.length(); i++) {
			char ch = t.charAt(i);
			if ((ch >= 0x20 && ch < 0x7F) || ch == '✔' || ch == '✓' || ch == '✗' || ch == '✘' || ch == '✖') {
				b.append(ch);
			}
		}
		return b.toString().replaceAll("\\s+", " ").trim();
	}
}
