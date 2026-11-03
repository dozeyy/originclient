package com.origin.client.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;

/**
 * The Scoreboard mod's own sidebar (pre-1.20 Fabric; one identical file in 1.16.5-1.19.4,
 * written Java 8 style so 1.16.5 compiles it too). GuiScoreboardMixin calls render() at the
 * head of vanilla's Gui.displayScoreboardSidebar while the mod is on and cancels vanilla's
 * draw when it returns true; a throw here leaves vanilla's board drawing (fail-soft).
 *
 * Why the board is drawn here and not nudged: Hide Numbers, Text Shadow, Border and Header
 * Color need the rows themselves, and the HUD editor box has to be the REAL board's size (a
 * wide Hypixel board is far wider than the editor sample, so a pivot computed from the sample
 * put it off-screen / away from the preview).
 *
 * Rows and width follow vanilla 1.16-1.19 exactly: at most 15 scores, owners starting with '#'
 * hidden, lowest score at the bottom, the widest "name: score" (or title) sets the width, the
 * title row on top. The board is placed like every other HUD element: at the "scoreboard"
 * HudPos, scaled from its top-left, so it lands exactly on the editor box.
 */
public final class SidebarHud {
	private SidebarHud() {
	}

	private static final int LINE = 9;   // vanilla's sidebar row height

	/** One measured board. */
	public static final class Board {
		final Component title;
		final List<Component> names = new ArrayList<Component>();   // bottom row first
		final List<String> nums = new ArrayList<String>();
		int inner;   // vanilla's content width: widest title / "name: score"

		Board(Component title) {
			this.title = title;
		}

		public int width() {
			return inner + 4;
		}

		public int height() {
			return names.size() * LINE + LINE + 1;
		}
	}

	/** The objective vanilla's Gui would show in the sidebar now (team-colour slot first). */
	public static Objective currentObjective(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return null;
		}
		Scoreboard sb = mc.level.getScoreboard();
		Objective byTeam = null;
		PlayerTeam team = sb.getPlayersTeam(mc.player.getScoreboardName());
		if (team != null) {
			int slot = team.getColor().getId();
			if (slot >= 0) {
				byTeam = sb.getDisplayObjective(3 + slot);
			}
		}
		return byTeam != null ? byTeam : sb.getDisplayObjective(1);
	}

	/** null = nothing to draw (no objective, or no visible scores: vanilla draws nothing too). */
	public static Board measure(Objective objective) {
		if (objective == null) {
			return null;
		}
		Font font = Minecraft.getInstance().font;
		Scoreboard sb = objective.getScoreboard();
		List<Score> list = new ArrayList<Score>();
		for (Score s : sb.getPlayerScores(objective)) {
			if (s.getOwner() != null && !s.getOwner().startsWith("#")) {
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
		int w = font.width(b.title);
		int sep = font.width(": ");
		for (Score s : list) {
			PlayerTeam team = sb.getPlayersTeam(s.getOwner());
			Component name = PlayerTeam.formatNameForTeam(team, Component.nullToEmpty(s.getOwner()));
			String num = ChatFormatting.RED + "" + s.getScore();
			b.names.add(name);
			b.nums.add(num);
			w = Math.max(w, font.width(name) + (hideNums ? 0 : sep + font.width(num)));
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
		Font font = Minecraft.getInstance().font;
		boolean hideNums = Mods.bool("scoreboard", "hideNumbers");
		boolean shadow = Mods.bool("scoreboard", "textShadow");
		int w = b.width(), h = b.height();
		// Background Color replaces vanilla's two translucent blacks (title row and rows)
		g.fill(0, 0, w, h, OriginColorPicker.liveColor("scoreboard", "bgColor"));
		int header = OriginColorPicker.liveColor("scoreboard", "headerColor");
		g.drawString(font, b.title, 2 + b.inner / 2 - font.width(b.title) / 2, 1, header, shadow);
		for (int i = 0; i < b.names.size(); i++) {
			int y = h - (i + 1) * LINE;
			g.drawString(font, b.names.get(i), 2, y, 0xFFFFFFFF, shadow);
			if (!hideNums) {
				String num = b.nums.get(i);
				g.drawString(font, num, w - font.width(num), y, 0xFFFFFFFF, shadow);
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
	 * drawn here, nothing to draw, or the HUD editor is open (it draws the board in its box).
	 */
	public static boolean render(PoseStack poseStack, Objective objective) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof HudEditorScreen) {
			return true;
		}
		Board b = measure(objective);
		if (b == null) {
			return true;
		}
		Gfx g = new Gfx(poseStack);
		HudPos p = HudElements.scoreboardPos();
		double s = p.scale > 0.05 ? p.scale : 1.0;
		int sw = g.guiWidth(), sh = g.guiHeight();
		double x = p.x(sw, b.width() * s), y = p.y(sh, b.height() * s);
		poseStack.pushPose();
		try {
			poseStack.translate(x, y, 0);
			poseStack.scale((float) s, (float) s, 1f);
			draw(g, b);
		} finally {
			poseStack.popPose();
		}
		return true;
	}
}
