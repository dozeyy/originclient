package com.origin.client.client.hud;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.Pose;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The Scoreboard mod's own sidebar draw, called from GuiIngameScoreboardMixin
 * (GuiIngame.renderScoreboard HEAD) while the mod is on; true cancels vanilla's.
 * The pre-1.20 Fabric modules have the same class (their GuiScoreboardMixin
 * calls it the same way).
 *
 * Rows and width are vanilla 1.8.9's renderScoreboard: at most 15 scores (names
 * starting with '#' hidden), lowest score at the bottom, the widest "name: score"
 * (or the title) sets the width, the objective title row on top. The board is
 * placed like every other HUD element: at the "scoreboard" HudPos, scaled from its
 * top-left, sized from the REAL board, so it lands exactly on the HUD-editor box
 * (a pivot from the editor sample's size put wide Hypixel boards off-screen).
 * Options: Hide Numbers drops the red score column, Text Shadow, Border (thickness
 * 0 = none), Background Color replaces vanilla's two translucent blacks, Header
 * Color tints the title. Read-only: never touches scoreboard data.
 */
public final class SidebarHud {
	private SidebarHud() {
	}

	/** One measured board. */
	public static final class Board {
		final String title;
		final List<String> names = new ArrayList<String>();   // bottom row first
		final List<String> nums = new ArrayList<String>();
		int inner;   // vanilla's content width: widest title / "name: score"
		int line;    // row height (font height, 9)

		Board(String title) {
			this.title = title;
		}

		public int width() {
			return inner + 4;
		}

		public int height() {
			return names.size() * line + line + 1;
		}
	}

	/** The objective vanilla would show in the sidebar now (team-colour slot first). */
	public static ScoreObjective currentObjective(Minecraft mc) {
		if (mc.theWorld == null || mc.thePlayer == null) {
			return null;
		}
		Scoreboard sb = mc.theWorld.getScoreboard();
		ScoreObjective byTeam = null;
		ScorePlayerTeam team = sb.getPlayersTeam(mc.thePlayer.getName());
		if (team != null) {
			int slot = team.getChatFormat().getColorIndex();
			if (slot >= 0) {
				byTeam = sb.getObjectiveInDisplaySlot(3 + slot);
			}
		}
		return byTeam != null ? byTeam : sb.getObjectiveInDisplaySlot(1);
	}

	/** null = nothing to draw (no objective, or no visible scores: vanilla draws nothing too). */
	public static Board measure(ScoreObjective objective) {
		if (objective == null) {
			return null;
		}
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		Scoreboard sb = objective.getScoreboard();
		List<Score> list = new ArrayList<Score>();
		for (Score s : sb.getSortedScores(objective)) {
			if (s.getPlayerName() != null && !s.getPlayerName().startsWith("#")) {
				list.add(s);
			}
		}
		if (list.isEmpty()) {
			return null;
		}
		if (list.size() > 15) {
			list = new ArrayList<Score>(list.subList(list.size() - 15, list.size()));
		}
		boolean hideNums = Mods.bool("scoreboard", "hideNumbers");
		Board b = new Board(objective.getDisplayName());
		b.line = font.FONT_HEIGHT;
		int w = font.getStringWidth(b.title);
		for (Score s : list) {
			ScorePlayerTeam team = sb.getPlayersTeam(s.getPlayerName());
			String name = ScorePlayerTeam.formatPlayerName(team, s.getPlayerName());
			String num = EnumChatFormatting.RED + "" + s.getScorePoints();
			b.names.add(name);
			b.nums.add(num);
			w = Math.max(w, font.getStringWidth(hideNums ? name : name + ": " + num));
		}
		b.inner = w;
		return b;
	}

	/** The real board's unscaled size, or null when there is none (the editor shows the sample). */
	public static int[] realSize(Minecraft mc) {
		Board b = measure(currentObjective(mc));
		return b == null ? null : new int[]{b.width(), b.height()};
	}

	/** Draws the board at LOCAL (0,0); the caller has translated + scaled. */
	public static void draw(Gfx g, Board b) {
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		boolean hideNums = Mods.bool("scoreboard", "hideNumbers");
		boolean shadow = Mods.bool("scoreboard", "textShadow");
		int w = b.width(), h = b.height(), lh = b.line;
		g.fill(0, 0, w, h, OriginColorPicker.liveColor("scoreboard", "bgColor"));
		int header = OriginColorPicker.liveColor("scoreboard", "headerColor");
		g.drawString(font, b.title, 2 + b.inner / 2 - font.getStringWidth(b.title) / 2, 1, header, shadow);
		for (int i = 0; i < b.names.size(); i++) {
			int y = h - (i + 1) * lh;
			g.drawString(font, b.names.get(i), 2, y, 0xFFFFFFFF, shadow);
			if (!hideNums) {
				String num = b.nums.get(i);
				g.drawString(font, num, w - font.getStringWidth(num), y, 0xFFFFFFFF, shadow);
			}
		}
		border(g, w, h);
	}

	/** The Border option, shared with the editor sample. Thickness 0 = no border. */
	public static void border(Gfx g, int w, int h) {
		if (!Mods.bool("scoreboard", "border")) {
			return;
		}
		int bt = (int) Math.max(0, Math.min(4, Math.round(Mods.num("scoreboard", "borderThickness"))));
		if (bt == 0) {
			return;
		}
		int bc = OriginColorPicker.liveColor("scoreboard", "borderColor");
		// left/right only between top and bottom: corners drawn once (no darker corners)
		g.fill(0, 0, w, bt, bc);
		g.fill(0, h - bt, w, h, bc);
		g.fill(0, bt, bt, h - bt, bc);
		g.fill(w - bt, bt, w, h - bt, bc);
	}

	/**
	 * HEAD of vanilla's sidebar draw, mod on. true = handled, the caller cancels vanilla's:
	 * drawn here, hidden, nothing to draw, or the HUD editor is open (it draws the board in its box).
	 */
	public static boolean render(ScoreObjective objective, ScaledResolution res) {
		if (Mods.bool("scoreboard", "hideScoreboard")) {
			return true;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.currentScreen instanceof HudEditorScreen) {
			return true;
		}
		Board b = measure(objective);
		if (b == null) {
			return true;
		}
		HudPos p = HudElements.scoreboardPos();
		double s = p.scale > 0.05 ? p.scale : 1.0;
		int sw = res.getScaledWidth(), sh = res.getScaledHeight();
		double x = p.x(sw, b.width() * s), y = p.y(sh, b.height() * s);
		Gfx g = new Gfx();
		Pose pose = g.pose();
		pose.pushPose();
		try {
			pose.translate(x, y, 0);
			pose.scale((float) s, (float) s, 1f);
			draw(g, b);
		} finally {
			pose.popAll();
		}
		return true;
	}
}
