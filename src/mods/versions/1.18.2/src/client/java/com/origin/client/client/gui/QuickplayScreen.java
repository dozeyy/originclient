package com.origin.client.client.gui;

import com.origin.client.client.mods.Quickplay;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

import java.util.List;
import java.util.Locale;

import static com.origin.client.client.gui.OriginDeck.*;

// Quickplay's window, on the shared OriginWindow frame (Will, 2026-10-12: every
// Origin window in the mod menu's style). Hypixel's games are a list on the
// left, the picked game's modes a grid of tiles on the right, its lobby pinned
// underneath. One click joins and closes. Both columns scroll on their own:
// the games list with this class's own scroll, the mode grid with the frame's
// (so its scrollbar sits at the panel edge like every other window's).
// Click targets are recorded while drawing (d.hit), so they always match.
public class QuickplayScreen extends OriginWindow {
	private static final float LIST_W = 232;                           // games column
	private static final float SPLIT = BODY_X + LIST_W + 12;           // the divider line
	private static final float MODE_X = SPLIT + 12;                    // mode grid's left edge
	private static final float MODE_W = BODY_X + BODY_W - MODE_X;
	private static final float TOP = BODY_Y + 24;                      // under the column labels
	private static final float BOTTOM = BODY_Y + BODY_H;
	private static final float ROW_H = 36, ROW_GAP = 4;
	private static final float TILE_H = 52, GAP = 12, LOBBY_H = 40;
	private static final int COLS = 3;

	// The picked game survives reopening the menu.
	private static int selected = 0;

	// games-list scroll (design px), eased like the frame's
	private double listScroll = 0, listScrollTarget = 0;
	private float listMax = 0;

	public QuickplayScreen() {
		super(new TextComponent("Quickplay"), null);
	}

	private static Quickplay.Game game() {
		selected = Math.max(0, Math.min(Quickplay.GAMES.size() - 1, selected));
		return Quickplay.GAMES.get(selected);
	}

	// ---- header ----

	@Override
	protected String heading() {
		return "Quickplay";
	}

	@Override
	protected String subheading() {
		return Quickplay.onHypixel(Minecraft.getInstance()) ? "Hypixel games"
				: "These games are on Hypixel. Join it to play.";
	}

	@Override
	protected String headingIcon() {
		return "quickplay";
	}

	@Override
	protected void drawHeaderActions(OriginDeck d, float right) {
		if (Quickplay.onHypixel(Minecraft.getInstance())) {
			return;
		}
		String t = "NOT ON HYPIXEL";
		float w = 24 + d.textW(t, 11, true, CAPS_TRACK);
		d.rect(right - w, 16, w, 32, 6, GOLD_BG);
		d.textMid(t, right - w + 12, 32, 11, true, CAPS_TRACK, GOLD);
	}

	// ---- body ----

	@Override
	protected void drawBody(OriginDeck d) {
		try {
			drawGames(d);
			d.fill(SPLIT, BODY_Y, 1, BODY_H, LINE);
			drawModes(d);
		} catch (Throwable t) {
			// a draw failure leaves the bare frame; Esc / close still work
		}
	}

	private void drawGames(OriginDeck d) {
		List<Quickplay.Game> games = Quickplay.GAMES;
		label(d, "Games", BODY_X, BODY_Y + 8);

		float contentH = games.size() * (ROW_H + ROW_GAP) - ROW_GAP;
		listMax = Math.max(0, contentH - (BOTTOM - TOP));
		listScrollTarget = Math.max(0, Math.min(listMax, listScrollTarget));
		listScroll += (listScrollTarget - listScroll) * 0.3;
		listScroll = Math.max(0, Math.min(listMax, listScroll));

		d.clip(BODY_X, TOP, LIST_W, BOTTOM - TOP);
		for (int i = 0; i < games.size(); i++) {
			float ry = TOP + i * (ROW_H + ROW_GAP) - (float) listScroll;
			if (ry + ROW_H < TOP || ry > BOTTOM) {
				continue;
			}
			boolean sel = i == selected;
			boolean hov = d.over(BODY_X, ry, LIST_W, ROW_H);
			float hp = OriginUi.hover("deck-qp-game:" + i, hov);
			int fill = sel ? ACC_SOFT : lerp(0x00000000, CARD, hp);
			if ((fill >>> 24) != 0) {
				d.rect(BODY_X, ry, LIST_W, ROW_H, 6, fill);
			}
			String name = d.fit(games.get(i).name(), LIST_W - 28, 13.5f, sel, 0);
			d.textMid(name, BODY_X + 14, ry + ROW_H / 2, 13.5f, sel, 0, sel ? ACC : lerp(DIM, TEXT, hp));
			int idx = i;
			d.hit(BODY_X, ry, LIST_W, ROW_H, () -> {
				if (idx != selected) {
					selected = idx;
					scroll = scrollTarget = 0;
				}
			});
		}
		d.unclip();
		if (listMax > 0) {
			float track = BOTTOM - TOP;
			float thumb = Math.max(28, track * track / (track + listMax));
			d.rect(SPLIT - 6, TOP + (track - thumb) * (float) (listScroll / listMax), 3, thumb, 1.5f, 0x33FFFFFF);
		}
	}

	private void drawModes(OriginDeck d) {
		Quickplay.Game game = game();
		label(d, game.name(), MODE_X, BODY_Y + 8);
		int n = game.modes().size();
		String count = n + (n == 1 ? " mode" : " modes");
		float cw = d.textW(count.toUpperCase(Locale.ROOT), 11, true, CAPS_TRACK);
		label(d, count, MODE_X + MODE_W - cw, BODY_Y + 8);

		float gridBottom = game.lobby() != null ? BOTTOM - LOBBY_H - 16 : BOTTOM;
		float tileW = (MODE_W - (COLS - 1) * GAP) / COLS;
		int rows = (n + COLS - 1) / COLS;
		float contentH = rows * (TILE_H + GAP) - GAP;
		maxScroll = Math.max(0, contentH - (gridBottom - TOP));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);

		d.clip(MODE_X, TOP, MODE_W, gridBottom - TOP);
		for (int i = 0; i < n; i++) {
			float x = MODE_X + (i % COLS) * (tileW + GAP);
			float y = TOP + (i / COLS) * (TILE_H + GAP) - (float) scroll;
			if (y + TILE_H < TOP || y > gridBottom) {
				continue;
			}
			Quickplay.Mode mode = game.modes().get(i);
			boolean hov = d.over(x, y, tileW, TILE_H);
			float hp = OriginUi.hover("deck-qp-mode:" + selected + ":" + i, hov);
			d.rect(x, y, tileW, TILE_H, 6, lerp(CARD, CARD_H, hp), lerp(LINE, 0x29F1E9D2, hp));
			d.textMid(d.fit(mode.name(), tileW - 56, 14, true, 0), x + 18, y + TILE_H / 2, 14, true, 0, TEXT);
			d.icon("@chev-right", x + tileW - 18 - 14, y + TILE_H / 2 - 7, 14, lerp(MUTE, ACC, hp));
			d.hit(x, y, tileW, TILE_H, () -> {
				beginClose();
				Quickplay.play(Minecraft.getInstance(), game, mode);
			});
		}
		d.unclip();
		scrollbar(d, TOP, gridBottom);

		if (game.lobby() != null) {
			button(d, "Go to Lobby", null, MODE_X, BOTTOM - LOBBY_H, MODE_W, LOBBY_H, Style.SECONDARY, true, () -> {
				beginClose();
				Quickplay.lobby(Minecraft.getInstance(), game);
			});
		}
	}

	// ---- input ----

	@Override
	public boolean mouseScrolled(double mx, double my, double sy) {
		if (dX(mx) < SPLIT) {
			listScrollTarget = Math.max(0, Math.min(listMax, listScrollTarget - sy * (ROW_H + ROW_GAP) * 2));
			return true;
		}
		return super.mouseScrolled(mx, my, sy);
	}
}
