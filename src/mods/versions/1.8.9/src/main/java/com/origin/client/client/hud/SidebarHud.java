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
import java.util.Collection;
import java.util.List;

/**
 * 1.8.9-only helper (no 1.16.5 file): the Scoreboard mod's own sidebar draw,
 * called from GuiIngameScoreboardMixin (GuiIngame.renderScoreboard HEAD) while
 * the mod is on. See that mixin for why 1.8.9 draws the board itself instead of
 * 1.16.5's push/redirect/pop.
 *
 * Geometry is vanilla 1.8.9's renderScoreboard, line for line: at most 15 scores
 * (names starting with '#' hidden), the widest "name: score" row sets the width,
 * the board sits 3px from the right edge with its bottom at height/2 + rows*9/3,
 * the objective title row above the scores. On top of that, exactly like 1.16.5:
 *  - the whole board is translated from vanilla's default spot to the
 *    "scoreboard" element's HudPos (the same sample-size delta the HUD editor
 *    preview uses, so dragging the sample moves the real board), and
 *  - scaled about the board's own centre (Bug-audit #4 from 1.16.5).
 * Options: Hide Numbers drops the red score column, Background Color replaces
 * vanilla's two translucent blacks (1.16.5's fill redirect), Header Color tints
 * the title. Text Shadow and Border stay editor-sample-only, as on every other
 * version: the in-game board keeps vanilla's unshadowed text and no outline, so
 * it looks the same on 1.8.9 as everywhere else. Read-only: never touches
 * scoreboard data.
 */
public final class SidebarHud {
	private SidebarHud() {
	}

	/** @return true when the board was handled (drawn, or hidden on purpose). */
	public static boolean render(ScoreObjective objective, ScaledResolution res) {
		if (Mods.bool("scoreboard", "hideScoreboard")) {
			return true;
		}
		if (objective == null) {
			return false;
		}
		Minecraft mc = Minecraft.getMinecraft();
		FontRenderer font = mc.fontRendererObj;
		Scoreboard scoreboard = objective.getScoreboard();
		Collection<Score> all = scoreboard.getSortedScores(objective);
		List<Score> list = new ArrayList<Score>();
		for (Score s : all) {
			if (s.getPlayerName() != null && !s.getPlayerName().startsWith("#")) {
				list.add(s);
			}
		}
		if (list.size() > 15) {
			list = new ArrayList<Score>(list.subList(list.size() - 15, list.size()));
		}
		boolean hideNums = Mods.bool("scoreboard", "hideNumbers");
		boolean shadow = false;   // vanilla's sidebar text is unshadowed (see the class comment)
		int bg = OriginColorPicker.liveColor("scoreboard", "bgColor");
		int headerColor = OriginColorPicker.liveColor("scoreboard", "headerColor");

		String title = objective.getDisplayName();
		int w = font.getStringWidth(title);
		for (Score s : list) {
			ScorePlayerTeam team = scoreboard.getPlayersTeam(s.getPlayerName());
			String row = ScorePlayerTeam.formatPlayerName(team, s.getPlayerName());
			if (!hideNums) {
				row += ": " + EnumChatFormatting.RED + s.getScorePoints();
			}
			w = Math.max(w, font.getStringWidth(row));
		}
		if (list.isEmpty()) {
			return true;   // vanilla draws nothing for an objective with no visible scores
		}
		int lh = font.FONT_HEIGHT;
		int rowsH = list.size() * lh;
		int sw = res.getScaledWidth(), sh = res.getScaledHeight();
		int bottom = sh / 2 + rowsH / 3;
		int right = sw - 3 + 2;              // vanilla: l = width - k1 + 2
		int left = sw - w - 3;               // vanilla: l1 = width - i - k1

		Gfx g = new Gfx();
		Pose pose = g.pose();
		pose.pushPose();
		try {
			// Reposition + rescale exactly as 1.16.5's GuiScoreboardMixin does.
			HudPos p = HudElements.scoreboardPos();
			int[] sz = HudElements.sampleScoreboardSize();
			double tx = p.x(sw, sz[0]), ty = p.y(sh, sz[1]);
			double vx = sw - sz[0] - 3, vy = (sh - sz[1]) / 2.0;
			pose.translate(tx - vx, ty - vy, 0);
			float s = (float) p.scale;
			if (s > 0.05f) {
				float ax = (float) (vx + sz[0] / 2.0), ay = (float) (vy + sz[1] / 2.0);
				pose.translate(ax, ay, 0);
				pose.scale(s, s, 1f);
				pose.translate(-ax, -ay, 0);
			}

			int j = 0;
			for (Score s1 : list) {
				j++;
				ScorePlayerTeam team = scoreboard.getPlayersTeam(s1.getPlayerName());
				String name = ScorePlayerTeam.formatPlayerName(team, s1.getPlayerName());
				int y = bottom - j * lh;
				g.fill(left - 2, y, right, y + lh, bg);
				g.drawString(font, name, left, y, 0xFFFFFFFF, shadow);
				if (!hideNums) {
					String num = EnumChatFormatting.RED + "" + s1.getScorePoints();
					g.drawString(font, num, right - font.getStringWidth(num), y, 0xFFFFFFFF, shadow);
				}
				if (j == list.size()) {
					g.fill(left - 2, y - lh - 1, right, y - 1, bg);
					g.fill(left - 2, y - 1, right, y, bg);
					g.drawString(font, title, left + w / 2 - font.getStringWidth(title) / 2, y - lh, headerColor, shadow);
				}
			}
		} finally {
			pose.popAll();
		}
		return true;
	}
}
