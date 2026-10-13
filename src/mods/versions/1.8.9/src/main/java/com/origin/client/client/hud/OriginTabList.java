package com.origin.client.client.hud;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Tab Editor's player-list overlay — a full custom draw that REPLACES vanilla's
 * {@code GuiPlayerTabOverlay.renderPlayerlist} (HudHooks cancels
 * RenderGameOverlayEvent.Pre(PLAYER_LIST)). Drawing it ourselves is what makes every
 * option — heads on/off, ping bars vs number vs hidden, per-element colours,
 * header/footer disable+tint, highlight-own, move-self-to-top, hide-NPCs —
 * reliable without fragile hooks into vanilla's render internals.
 *
 * Layout mirrors vanilla: one column up to 20 players, then more columns; each row is
 * a translucent bar with an optional head, the display name, the server's tab-list
 * scoreboard objective (health hearts / a number), and a ping readout; a centered
 * header sits above and footer below. The objective is drawn in BOTH modes so the list
 * still shows the server's hearts/numbers when the Tab Editor mod is off.
 *
 * 1.8.9: rows are NetworkPlayerInfo; a name is a § string -- vanilla's own
 * GuiPlayerTabOverlay.getPlayerName (the display name, else the TEAM-formatted
 * profile name, which is where 1.8.9 servers put their rank prefixes); scores are
 * Scoreboard.getValueFromObjective; ping is getResponseTime; header/footer come
 * from GuiPlayerTabOverlayAccessor.
 */
public final class OriginTabList {
	private OriginTabList() {
	}

	private static final int ROW_H = 9;

	/** The player list in vanilla 1.8.9's own tab order (GuiPlayerTabOverlay's
	 *  ordering: non-spectators first, then team name, then name), capped at 80
	 *  like vanilla. */
	public static List<NetworkPlayerInfo> vanillaPlayerList(Minecraft mc) {
		List<NetworkPlayerInfo> list = new ArrayList<NetworkPlayerInfo>();
		if (mc.thePlayer == null || mc.thePlayer.sendQueue == null) {
			return list;
		}
		list.addAll(mc.thePlayer.sendQueue.getPlayerInfoMap());
		list.sort(new java.util.Comparator<NetworkPlayerInfo>() {
			@Override
			public int compare(NetworkPlayerInfo a, NetworkPlayerInfo b) {
				boolean sa = a.getGameType() == WorldSettings.GameType.SPECTATOR;
				boolean sb = b.getGameType() == WorldSettings.GameType.SPECTATOR;
				if (sa != sb) {
					return sa ? 1 : -1;
				}
				ScorePlayerTeam ta = a.getPlayerTeam(), tb = b.getPlayerTeam();
				int c = (ta != null ? ta.getRegisteredName() : "").compareTo(tb != null ? tb.getRegisteredName() : "");
				return c != 0 ? c : a.getGameProfile().getName().compareTo(b.getGameProfile().getName());
			}
		});
		return list.size() > 80 ? new ArrayList<NetworkPlayerInfo>(list.subList(0, 80)) : list;
	}

	// Hearts live on the classic icons sheet (no GUI sprite atlas before 1.20.2).
	private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");

	/**
	 * @param customize when false (Tab Editor mod OFF) the list renders in a plain,
	 *                  vanilla-like default — heads on, ping bars, standard background,
	 *                  no filters/reorder/recolour — so the tab still works and looks
	 *                  normal without the mod. When true, every option is applied.
	 */
	public static void render(Gfx g, int screenWidth, List<NetworkPlayerInfo> allInfos,
							  IChatComponent header, IChatComponent footer, boolean customize) {
		Minecraft mc = Minecraft.getMinecraft();
		FontRenderer font = mc.fontRendererObj;
		UUID self = mc.thePlayer != null ? mc.thePlayer.getUniqueID() : null;

		// The server's tab-list objective (health hearts or a number beside each name).
		// Read straight from the scoreboard so it shows in both plain and custom modes.
		Scoreboard scoreboard = mc.theWorld != null ? mc.theWorld.getScoreboard() : null;
		ScoreObjective objective = scoreboard != null ? scoreboard.getObjectiveInDisplaySlot(0) /* slot 0 = player list */ : null;
		boolean hearts = objective != null && objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS;

		// Options only apply when customizing; otherwise use vanilla-like defaults.
		boolean heads = !customize || Mods.bool("tablist", "displayHeads");
		// Live health hearts, read off each player's entity (see drawHealth), and the
		// combat highlight fed by CombatTracker.
		boolean showHealth = customize && Mods.bool("tablist", "showHealth");
		boolean combatOn = customize && Mods.bool("tablist", "combatHighlight");
		long combatMs = (long) (Mods.num("tablist", "combatSeconds") * 1000.0);
		int combatCol = Mods.color("tablist", "combatColor");
		boolean hidePing = customize && Mods.bool("tablist", "hidePing");
		boolean pingNum = customize && Mods.bool("tablist", "pingAsNumber");
		boolean highlightSelf = customize && Mods.bool("tablist", "highlightSelf");
		boolean disableHeader = customize && Mods.bool("tablist", "disableHeader");
		boolean disableFooter = customize && Mods.bool("tablist", "disableFooter");
		// Our live hearts supersede the server's tab objective when that objective is
		// itself HEARTS — same information, and two heart strips per row is noise.
		if (showHealth && hearts) {
			objective = null;
			hearts = false;
		}
		int bg = customize ? Mods.color("tablist", "backgroundColor") : 0x66000000;
		int headerCol = customize ? Mods.color("tablist", "headerColor") : 0xFFFFFFFF;
		int footerCol = customize ? Mods.color("tablist", "footerColor") : 0xFFFFFFFF;

		// filter + reorder (customize only)
		List<NetworkPlayerInfo> list = new ArrayList<>(allInfos);
		if (customize && Mods.bool("tablist", "hideNpcs")) {
			// Real Mojang accounts use version-4 UUIDs; offline/NPC entities (Citizens
			// etc.) use version-2/3 offline UUIDs — a reliable "fake player" tell.
			list.removeIf(p -> p.getGameProfile().getId() == null || p.getGameProfile().getId().version() != 4);
		}
		if (customize && Mods.bool("tablist", "moveSelfTop") && self != null) {
			final UUID me = self;
			list.sort((a, b) -> Boolean.compare(
					me.equals(b.getGameProfile().getId()), me.equals(a.getGameProfile().getId())));
		}
		// Whoever you're fighting rides at the very top, most recently hit first. This
		// runs AFTER move-self-to-top so a live fight always outranks it, and List.sort
		// is stable, so everyone outside the combat window keeps the order above.
		if (combatOn) {
			list.sort((a, b) -> Long.compare(
					CombatTracker.lastHit(b.getGameProfile().getId(), combatMs),
					CombatTracker.lastHit(a.getGameProfile().getId(), combatMs)));
		}

		int n = Math.min(80, list.size());
		if (n == 0) {
			return;
		}

		int headW = heads ? ROW_H : 0;

		String[] names = new String[n];
		int[] scores = objective != null ? new int[n] : null;
		int maxName = 0;
		int scoreW = 0;
		for (int i = 0; i < n; i++) {
			names[i] = displayName(mc, list.get(i));
			maxName = Math.max(maxName, font.getStringWidth(names[i]));
			if (objective != null) {
				scores[i] = scoreboard.getValueFromObjective(list.get(i).getGameProfile().getName(), objective).getScorePoints();
				if (!hearts) {
					scoreW = Math.max(scoreW, font.getStringWidth(Integer.toString(scores[i])));
				}
			}
		}
		if (objective != null) {
			scoreW = hearts ? 82 : scoreW + 8; // hearts row up to ~10 wide; number + gap
		}

		int pingW = hidePing ? 0 : (pingNum ? font.getStringWidth("999ms") : 11);
		int healthW = showHealth ? font.getStringWidth("20") + 4 : 0; // widest number + a hair of gap
		int cellW = headW + maxName + 6 + healthW + scoreW + pingW;

		// columns: <=20 per column, then widen
		int rows = n, cols = 1;
		while (rows > 20) {
			cols++;
			rows = (n + cols - 1) / cols;
		}
		int gap = 5;
		int totalW = cols * cellW + (cols - 1) * gap;
		int cx = screenWidth / 2;
		int left = cx - totalW / 2;
		int y = 10;

		// header
		if (header != null && !disableHeader) {
			y = drawCentered(g, font, header, cx, y, headerCol) + 1;
		}
		int listTop = y;

		for (int i = 0; i < n; i++) {
			int col = i / rows, row = i % rows;
			int x = left + col * (cellW + gap);
			int ry = listTop + row * ROW_H;
			NetworkPlayerInfo info = list.get(i);

			g.fill(x - 1, ry - 1, x + cellW + 1, ry + 8, bg);

			int px = x;
			if (heads) {
				// face (8,8) + hat (40,8) straight from the 64x64 skin, like this
				// era's own GuiPlayerTabOverlay (1.8.9 upgrades legacy 64x32 skins to
				// 64x64 when it downloads them).
				ResourceLocation skin = info.getLocationSkin();
				Gfx.resetColor();
				g.blit(skin, px, ry, 8, 8, 8f, 8f, 8, 8, 64, 64);
				Gfx.defaultBlend();
				g.blit(skin, px, ry, 8, 8, 40f, 8f, 8, 8, 64, 64);
				GlStateManager.disableBlend();
				px += ROW_H;
			}
			UUID id = info.getGameProfile().getId();
			boolean isSelf = self != null && self.equals(id);
			boolean fighting = combatOn && id != null && CombatTracker.lastHit(id, combatMs) > 0L;
			int nameCol = fighting ? combatCol
					: (isSelf && highlightSelf) ? Mods.color("tablist", "selfColor") : 0xFFFFFFFF;
			g.drawString(font, names[i], px, ry, nameCol, false);

			// Live health readout, packed tight against the objective/ping column.
			if (showHealth) {
				drawHealth(g, font, mc, id, x + cellW - pingW - scoreW - 2, ry);
			}

			// objective (server hearts / number), right-aligned just left of the ping.
			if (objective != null) {
				int scoreRight = x + cellW - pingW - 2;
				if (hearts) {
					drawHearts(g, scoreRight, ry, scores[i]);
				} else {
					String s = Integer.toString(scores[i]);
					g.drawString(font, s, scoreRight - font.getStringWidth(s), ry, 0xFFFFFF55, false);
				}
			}

			if (!hidePing) {
				int ping = info.getResponseTime();
				if (pingNum) {
					String s = ping < 0 ? "?" : ping + "ms";
					g.drawString(font, s, x + cellW - font.getStringWidth(s), ry, pingColor(ping), false);
				} else {
					drawPingBars(g, x + cellW - 10, ry, ping);
				}
			}
		}

		if (footer != null && !disableFooter) {
			int fy = listTop + rows * ROW_H + 1;
			drawCentered(g, font, footer, cx, fy, footerCol);
		}
	}

	/** Vanilla's row name (display name, else the team-formatted name) + the Hypixel decoration. */
	private static String displayName(Minecraft mc, NetworkPlayerInfo info) {
		String name;
		try {
			name = mc.ingameGUI.getTabList().getPlayerName(info);
		} catch (Throwable t) {
			IChatComponent d = info.getDisplayName();
			name = d != null ? d.getFormattedText()
					: ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), info.getGameProfile().getName());
		}
		// Hypixel mod: guild-tag removal + level / Bed Wars stats (no-op elsewhere).
		return com.origin.client.client.mods.Hypixel.decorateTabName(info, name);
	}

	/**
	 * A player's real health as a bare 0-20 number, right-aligned to end at
	 * {@code rightX} so the column stays as narrow as possible — a wide tab list on a
	 * full server costs screen, so this readout is digits only, sat right next to ping.
	 *
	 * Health is synced entity data, so the client already knows it for every player
	 * whose entity it is tracking — no server support needed. Players outside tracking
	 * range have no entity at all, and their health is genuinely unknown: those get a
	 * dim "?" instead of a misleading number. Ceil so one sliver of health still reads
	 * as 1, matching the vanilla HUD.
	 */
	private static void drawHealth(Gfx g, FontRenderer font, Minecraft mc, UUID id, int rightX, int y) {
		EntityPlayer p = mc.theWorld != null && id != null ? mc.theWorld.getPlayerEntityByUUID(id) : null;
		if (p == null) {
			g.drawString(font, "?", rightX - font.getStringWidth("?"), y, 0x55FFFFFF, false);
			return;
		}
		// Clamped to the 0-20 scale: attribute-buffed servers can push a player past 20,
		// and the point of this readout is "how many of the usual 20 are left".
		String s = Integer.toString(Math.min(20, Math.max(0, (int) Math.ceil(p.getHealth()))));
		g.drawString(font, s, rightX - font.getStringWidth(s), y, 0xFFFFFFFF, false);
	}

	/** Health hearts for the server's tab objective (score = health), right-aligned so
	 *  the row ends at `rightX`. Mirrors the HUD look: a container outline per heart with
	 *  a full/half heart on top, up to 10. */
	private static void drawHearts(Gfx g, int rightX, int y, int score) {
		int hp = Math.max(0, score);
		int full = Math.min(10, hp / 2);
		boolean half = (hp % 2 != 0) && full < 10;
		int shown = full + (half ? 1 : 0);
		if (shown == 0) {
			return;
		}
		int sx = rightX - shown * 8;
		Gfx.resetColor();
		Gfx.defaultBlend();
		for (int i = 0; i < full; i++) {
			drawHeartCell(g, sx + i * 8, y, 2);
		}
		if (half) {
			drawHeartCell(g, sx + full * 8, y, 1);
		}
		GlStateManager.disableBlend();
	}

	/** One 9x9 heart: the container outline, plus a full (fill 2) or half (fill 1)
	 *  heart on top; fill 0 leaves it empty. The single place this file touches the
	 *  sprite blit API, which is what changes between versions. */
	private static void drawHeartCell(Gfx g, int x, int y, int fill) {
		g.blit(ICONS, x, y, 16, 0, 9, 9);
		if (fill >= 2) {
			g.blit(ICONS, x, y, 52, 0, 9, 9);
		} else if (fill == 1) {
			g.blit(ICONS, x, y, 61, 0, 9, 9);
		}
	}

	/** Draws each line of `text` (split on newlines) centered on `cx`; returns the y
	 *  below the last line. The chosen colour overrides the source formatting so the
	 *  header/footer colour pickers actually take effect. */
	private static int drawCentered(Gfx g, FontRenderer font, IChatComponent text, int cx, int y, int color) {
		for (String line : text.getUnformattedText().split("\n")) {
			g.drawString(font, line, cx - font.getStringWidth(line) / 2, y, color, false);
			y += ROW_H;
		}
		return y;
	}

	private static int pingColor(int ping) {
		if (ping < 0) {
			return 0xFFAAAAAA;
		}
		if (ping < 150) {
			return 0xFF44DD44;
		}
		if (ping < 300) {
			return 0xFF9BDD44;
		}
		if (ping < 600) {
			return 0xFFDDDD44;
		}
		if (ping < 1000) {
			return 0xFFDD9B44;
		}
		return 0xFFDD4444;
	}

	/** Five signal bars, filled by ping level — a self-drawn stand-in for vanilla's
	 *  connection sprite (avoids depending on sprite positions). */
	private static void drawPingBars(Gfx g, int x, int y, int ping) {
		int level = ping < 0 ? 0 : ping < 150 ? 5 : ping < 300 ? 4 : ping < 600 ? 3 : ping < 1000 ? 2 : 1;
		for (int b = 0; b < 5; b++) {
			int h = 2 + b;
			int bx = x + b * 2;
			int col = b < level ? 0xFFFFFFFF : 0x55FFFFFF;
			g.fill(bx, y + 7 - h, bx + 1, y + 7, col);
		}
	}
}
