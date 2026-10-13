package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.storage.WorldInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * 1.8.9 (the version Hypixel Bed Wars is mostly played on):
 *  - Colored Beds is NOT available: a 1.8.9 bed is a plain block baked into the
 *    chunk mesh with ONE texture (beds only became dyeable, block-entity-drawn
 *    in 1.12), so there is no per-bed draw to recolour and no team texture to
 *    swap in. The spawn-tracking that fed it is left out with it.
 *  - Hide Armor / Hide Food / Hardcore Hearts ride Forge's
 *    RenderGameOverlayEvent (ARMOR / FOOD / HEALTH, ServerHooks) instead of
 *    mixins into the HUD's draw code; the trap title is GuiIngame.displayTitle,
 *    which takes § Strings (HypixelBedwarsGuiMixin); the alert sound is
 *    Forge's PlaySoundEvent.
 *  - Scoreboard text is plain § Strings: a sidebar row is a Score whose player
 *    name is its text, formatted with ScorePlayerTeam.formatPlayerName.
 */
public final class HypixelBedwars {
	public static final String MOD = "hypixelbedwars";

	private HypixelBedwars() {
	}

	// ---- game state (refreshed once per tick by tick(); render thread only) ----

	private static Object sessionLevel = null;
	private static boolean inGame = false, practice = false;
	private static boolean ownBedKnown = false, ownBedAlive = true, ownBedLostChat = false;
	private static EnumChatFormatting ownTeamColor = null;

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
	// Scoreboard display slots (ints on 1.8.9): 0 list, 1 sidebar, 2 below name.
	private static final int SLOT_LIST = 0, SLOT_SIDEBAR = 1, SLOT_BELOW_NAME = 2;

	private static final Pattern TEAM_LINE = Pattern.compile(
			"^([A-Z]) (Red|Blue|Green|Yellow|Aqua|White|Pink|Gray|Grey): ?(.*)$");

	static void tick(Minecraft mc) {
		if (mc.theWorld != sessionLevel) {
			resetSession();
			sessionLevel = mc.theWorld;
		}
		if (mc.thePlayer == null || mc.theWorld == null || !Hypixel.inBedwars()) {
			inGame = practice = false;
			return;
		}
		Scoreboard sb = mc.theWorld.getScoreboard();
		ScoreObjective side = sb.getObjectiveInDisplaySlot(SLOT_SIDEBAR);
		if (side == null) {
			inGame = practice = false;
			return;
		}
		String title = clean(side.getDisplayName()).toUpperCase(Locale.ROOT);
		int teams = 0;
		boolean ownKnown = false, ownAlive = true, practiceLine = false;
		EnumChatFormatting ownColor = null;
		for (Score e : sb.getSortedScores(side)) {
			String owner = e.getPlayerName();
			if (owner == null || owner.startsWith("#")) {
				continue;   // vanilla's sidebar skips these too
			}
			String line = cleanLine(ScorePlayerTeam.formatPlayerName(sb.getPlayersTeam(owner), owner));
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
		inGame = teams >= 2;
		practice = !inGame && (title.contains("PRACTICE") || practiceLine);
		ownBedKnown = ownKnown;
		ownBedAlive = ownAlive;
		EnumChatFormatting mine = teamColor(sb.getPlayersTeam(mc.thePlayer.getName()));
		ownTeamColor = mine != null ? mine : ownColor;
		if (!(inGame || practice)) {
			return;
		}
		trackTeammates(mc, sb);
	}

	private static void resetSession() {
		inGame = practice = false;
		ownBedKnown = false;
		ownBedAlive = true;
		ownBedLostChat = false;
		ownTeamColor = null;
		ROSTER.clear();
	}

	// ---- chat ----

	// Hypixel's bed-break line, e.g. "BED DESTRUCTION > Your Bed was destroyed by Name!"
	// (the verb varies -- "was spontaneously combusted by" and friends -- the prefix doesn't).
	private static final String OWN_BED_LOST = "BED DESTRUCTION > Your Bed";

	/** Watches Hypixel's own chat for your bed breaking (backs up the sidebar's ✔). Never hides anything. */
	static void onMessage(IChatComponent message, boolean overlay) {
		if (overlay || !(inGame || practice) || !Hypixel.onHypixel()) {
			return;
		}
		if (Hypixel.strip(message.getUnformattedText()).trim().startsWith(OWN_BED_LOST)) {
			ownBedLostChat = true;
		}
	}

	// ---- Hardcore Hearts / Hide Food Bar / Hide Armor Bar (ServerHooks overlay events) ----

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

	// 1.8.9: GuiIngameForge.renderHealth picks the hardcore heart row from
	// mc.theWorld.getWorldInfo().isHardcoreModeEnabled(), read inline while it
	// draws. The overlay event pair HEALTH Pre / Post brackets exactly that draw,
	// so the flag is raised for it and put back right after (and again at the
	// end of the HUD pass, should another mod cancel HEALTH between the two).
	// Only reached on Hypixel, where the client world's flag means nothing else.
	private static WorldInfo hardcoreInfo = null;
	private static boolean hardcoreWas = false;

	/** RenderGameOverlayEvent.Pre(HEALTH): show the hardcore hearts for this draw. */
	public static void beginHearts(Minecraft mc) {
		endHearts();
		if (!hardcoreHearts() || mc.theWorld == null) {
			return;
		}
		WorldInfo info = mc.theWorld.getWorldInfo();
		if (info == null || info.isHardcoreModeEnabled()) {
			return;
		}
		hardcoreInfo = info;
		hardcoreWas = false;
		info.setHardcore(true);
	}

	/** RenderGameOverlayEvent.Post(HEALTH) / Post(ALL): put the world's flag back. */
	public static void endHearts() {
		WorldInfo info = hardcoreInfo;
		if (info != null) {
			hardcoreInfo = null;
			info.setHardcore(hardcoreWas);
		}
	}

	// ---- Custom Trap Alert / Mute Alert Sound (HypixelBedwarsGuiMixin, ServerHooks) ----
	//
	// When an enemy sets off one of your traps Hypixel shows the title
	// "TRAP TRIGGERED!" with a subtitle naming the trap, and plays the enderman
	// teleport sound. Custom Trap Alert redraws that title and subtitle in the
	// player's Alert Primary / Sub colours (keeping bold and the text); Mute Alert
	// Sound drops the sound that comes with it. Any other title is left alone.

	private static final long ALERT_SOUND_WINDOW_MS = 3000;
	// 1.8.9's name for the enderman teleport sound (sounds.json, "mob.endermen.portal").
	private static final ResourceLocation ALERT_SOUND = new ResourceLocation("mob.endermen.portal");
	private static long trapAtMs = Long.MIN_VALUE / 2;

	private static boolean isTrapTitle(String title) {
		String s = Hypixel.strip(title).toUpperCase(Locale.ROOT);
		return s.contains("TRAP") && (s.contains("TRIGGERED") || s.contains("SET OFF"));
	}

	/** GuiIngame.displayTitle's title (a § String on 1.8.9): the title to show
	 *  instead (the same one unless it's a trap alert). */
	public static String onTitle(String title) {
		if (title == null || title.isEmpty() || !active() || !isTrapTitle(title)) {
			return title;
		}
		trapAtMs = Minecraft.getSystemTime();
		return Mods.bool(MOD, "customTrapAlert") ? recolor(title, Mods.color(MOD, "alertPrimaryColor")) : title;
	}

	/** displayTitle's subtitle (and the subtitle already showing when a trap title
	 *  lands): Hypixel's trap subtitles all name the trap, so "trap" marks them. */
	public static String onSubtitle(String subtitle) {
		if (subtitle == null || subtitle.isEmpty() || !active() || !Mods.bool(MOD, "customTrapAlert")) {
			return subtitle;
		}
		if (!Hypixel.strip(subtitle).toLowerCase(Locale.ROOT).contains("trap")) {
			return subtitle;
		}
		return recolor(subtitle, Mods.color(MOD, "alertSubColor"));
	}

	/** Forge PlaySoundEvent: true = drop this sound (the trap alert's, with Mute Alert Sound on). */
	public static boolean muteSound(ISound sound) {
		if (sound == null || !active() || !Mods.bool(MOD, "muteAlertSound")) {
			return false;
		}
		return Minecraft.getSystemTime() - trapAtMs < ALERT_SOUND_WINDOW_MS
				&& ALERT_SOUND.equals(sound.getSoundLocation());
	}

	/** s in one colour (the nearest chat colour on 1.8.9), its bold / italic /
	 *  underline / strikethrough kept, every other § code dropped. */
	private static String recolor(String s, int argb) {
		StringBuilder styles = new StringBuilder();
		for (int i = 0; i + 1 < s.length(); i++) {
			if (s.charAt(i) == '§') {
				EnumChatFormatting f = Hypixel.byCode(s.charAt(i + 1));
				if (f != null && f.isFancyStyling() && f != EnumChatFormatting.OBFUSCATED
						&& styles.indexOf(f.toString()) < 0) {
					styles.append(f);
				}
			}
		}
		return Hypixel.nearest(argb).toString() + styles + Hypixel.strip(s);
	}

	// ---- Teammates HUD data ----
	//
	// Everyone seen on your team colour (and not spectating) during this game joins
	// the roster for the rest of it, so a teammate who is final-killed (Hypixel
	// moves them to spectator, and may move them off the team) or who disconnects
	// stays on the list, shown as out instead of vanishing.

	// Java 8 has no records: a final value class with the same canonical
	// constructor and accessors the record had.
	public static final class Teammate {
		private final UUID id;
		private final String name;
		private final int color;
		private final int health;
		private final boolean alive;
		private final boolean online;
		private final NetworkPlayerInfo info;

		public Teammate(UUID id, String name, int color, int health, boolean alive, boolean online, NetworkPlayerInfo info) {
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

		public NetworkPlayerInfo info() {
			return info;
		}
	}

	private static final Map<UUID, String> ROSTER = new LinkedHashMap<>();

	private static void trackTeammates(Minecraft mc, Scoreboard sb) {
		NetHandlerPlayClient conn = mc.getNetHandler();
		if (!inGame || ownTeamColor == null || conn == null) {
			return;
		}
		UUID me = mc.thePlayer.getUniqueID();
		for (NetworkPlayerInfo info : conn.getPlayerInfoMap()) {
			UUID id = info.getGameProfile().getId();
			if (id == null || id.equals(me) || info.getGameType() == WorldSettings.GameType.SPECTATOR
					|| ROSTER.containsKey(id)) {
				continue;
			}
			if (teamColor(sb.getPlayersTeam(info.getGameProfile().getName())) == ownTeamColor) {
				ROSTER.put(id, info.getGameProfile().getName());
			}
		}
	}

	/** Your teammates (you excluded), in the order they were first seen. */
	public static List<Teammate> teammates(Minecraft mc) {
		List<Teammate> out = new ArrayList<>();
		NetHandlerPlayClient conn = mc.getNetHandler();
		if (mc.theWorld == null || conn == null) {
			return out;
		}
		Scoreboard sb = mc.theWorld.getScoreboard();
		int rgb = Hypixel.rgbOf(ownTeamColor);
		int color = 0xFF000000 | (rgb >= 0 ? rgb : 0xFFFFFF);
		for (Map.Entry<UUID, String> e : ROSTER.entrySet()) {
			NetworkPlayerInfo info = conn.getPlayerInfo(e.getKey());
			boolean online = info != null;
			boolean alive = online && info.getGameType() != WorldSettings.GameType.SPECTATOR;
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
			ScoreObjective o = sb.getObjectiveInDisplaySlot(slot);
			if (o == null) {
				continue;
			}
			boolean isHealth = o.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS
					|| (o.getDisplayName() != null && o.getDisplayName().contains("❤"));
			if (!isHealth) {
				continue;
			}
			// 1.8.9: entityHasObjective only reads; getValueFromObjective would
			// CREATE a score row for a name that has none.
			if (sb.entityHasObjective(name, o)) {
				return sb.getValueFromObjective(name, o).getScorePoints();
			}
		}
		EntityPlayer p = mc.theWorld.getPlayerEntityByUUID(id);
		return p != null ? (int) Math.ceil(p.getHealth()) : -1;
	}

	// ---- team colours ----

	/** A scoreboard team's colour: its own colour field, else the first colour in
	 *  its prefix (Hypixel colours Bed Wars teams through the "R " / "B " prefix). */
	static EnumChatFormatting teamColor(ScorePlayerTeam team) {
		if (team == null) {
			return null;
		}
		EnumChatFormatting c = team.getChatFormat();
		if (c != null && c.isColor()) {
			return c;
		}
		return firstColor(team.getColorPrefix());
	}

	// 1.8.9: a team prefix is a § String; its first colour code is the team's.
	private static EnumChatFormatting firstColor(String s) {
		if (s == null) {
			return null;
		}
		for (int i = 0; i + 1 < s.length(); i++) {
			if (s.charAt(i) == '§') {
				EnumChatFormatting f = Hypixel.byCode(s.charAt(i + 1));
				if (f != null && f.isColor()) {
					return f;
				}
			}
		}
		return null;
	}

	private static EnumChatFormatting colorOfTeamName(String name) {
		switch (name) {
			case "Red": return EnumChatFormatting.RED;
			case "Blue": return EnumChatFormatting.BLUE;
			case "Green": return EnumChatFormatting.GREEN;
			case "Yellow": return EnumChatFormatting.YELLOW;
			case "Aqua": return EnumChatFormatting.AQUA;
			case "White": return EnumChatFormatting.WHITE;
			case "Pink": return EnumChatFormatting.LIGHT_PURPLE;
			default: return EnumChatFormatting.DARK_GRAY;   // Gray / Grey
		}
	}

	// ---- text helpers ----

	private static String clean(String s) {
		return Hypixel.strip(s).trim();
	}

	/** A sidebar line with formatting stripped and every character Hypixel uses as
	 *  an invisible spacer (emoji, private-use glyphs) dropped, spaces collapsed. */
	private static String cleanLine(String s) {
		String t = clean(s);
		StringBuilder b = new StringBuilder(t.length());
		for (int i = 0; i < t.length(); i++) {
			char ch = t.charAt(i);
			if ((ch >= 0x20 && ch < 0x7F) || ch == '✔' || ch == '✓' || ch == '✗' || ch == '✘'
					|| ch == '✖') {
				b.append(ch);
			}
		}
		return b.toString().replaceAll("\\s+", " ").trim();
	}
}
