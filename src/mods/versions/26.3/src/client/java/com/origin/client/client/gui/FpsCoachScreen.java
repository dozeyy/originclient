package com.origin.client.client.gui;

import com.origin.client.client.mods.FpsCoach;
import com.origin.client.client.mods.FpsCoachTips;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.origin.client.client.gui.OriginDeck.*;

// The FPS Coach window, on the shared OriginWindow frame (Will, 2026-10-12: every
// Origin window in the mod menu's style). Three faces, picked live from FpsCoach's
// state:
//   TIPS    -- your recent average FPS + 1% low, a plain-English verdict, then the
//              ranked tips, each with "Try it" (one change, then a measured
//              before/after) and "Hide" ("don't suggest this again").
//   RESULT  -- the measured outcome of a "Try it": change, FPS difference,
//              before/after, Keep / Undo. Esc (and back / close) keeps it.
//   TESTING -- shown if the coach is opened mid-test: the test is paused while
//              any menu is open; Continue (back to the game) or Stop test.
// Everything is drawn in OriginDeck design px; click targets are recorded while
// drawing (d.hit), so what you see and what you click never disagree.
public class FpsCoachScreen extends OriginWindow {
	// tips face layout (design px; the body is BODY_X..BODY_X+BODY_W, BODY_Y..BODY_Y+BODY_H)
	private static final float STATS_H = 80, STAT_W = 196, GAP = 16;
	private static final float LIST_TOP = BODY_Y + STATS_H + 40;     // under the SUGGESTIONS label
	private static final float LIST_BOTTOM = BODY_Y + BODY_H - 40;   // above the footer row
	private static final float ROW_H = 64, ROW_GAP = 8;

	private FpsCoach.Stats stats = FpsCoach.Stats.EMPTY;
	private List<FpsCoachTips.Tip> tips = List.of();
	private int hiddenCount = 0;

	// While the close fade plays, the face (and what it showed) is frozen: Keep /
	// Undo / Stop clear FpsCoach's state at once, and the fade must not flash the
	// tips list in its place.
	private Face frozen = null;
	private FpsCoach.Result frozenResult = null;
	private String frozenChange = "";
	private int frozenSecs = 0;

	/** parent = where Esc goes back to (null = the game). */
	public FpsCoachScreen(Screen parent) {
		super(Component.literal("FPS Coach"), parent);
	}

	@Override
	protected void init() {
		super.init();
		refresh();
	}

	private void refresh() {
		stats = FpsCoach.recent();
		tips = FpsCoach.tips(Minecraft.getInstance());
		hiddenCount = FpsCoachTips.hiddenCount();
	}

	private enum Face { TIPS, RESULT, TESTING }

	private Face face() {
		if (frozen != null) {
			return frozen;
		}
		if (FpsCoach.result() != null) {
			return Face.RESULT;
		}
		return FpsCoach.testing() ? Face.TESTING : Face.TIPS;
	}

	private void freeze() {
		if (frozen == null) {
			frozen = face();
			frozenResult = FpsCoach.result();
			frozenChange = FpsCoach.testingChange();
			frozenSecs = FpsCoach.testing() ? FpsCoach.secondsLeft() : 0;
		}
	}

	@Override
	public void beginClose() {
		freeze();
		if (FpsCoach.result() != null) {
			FpsCoach.keep();   // leaving the result card any way but Undo keeps the change
		}
		super.beginClose();
	}

	@Override
	public void onClose() {
		beginClose();
	}

	// ---- header ----

	@Override
	protected String heading() {
		return face() == Face.TESTING ? "FPS Coach is testing" : "FPS Coach";
	}

	@Override
	protected String subheading() {
		return switch (face()) {
			case RESULT -> "Test result";
			case TESTING -> "About " + (frozen != null ? frozenSecs : FpsCoach.secondsLeft()) + " s left";
			default -> "Your target: " + FpsCoach.target() + " FPS";
		};
	}

	@Override
	protected String headingIcon() {
		return FpsCoach.ID;
	}

	@Override
	protected void drawHeaderActions(OriginDeck d, float right) {
		if (face() != Face.TIPS) {
			return;
		}
		float bw = buttonW(d, "Settings", "@title-options");
		button(d, "Settings", "@title-options", right - bw, 16, bw, 32, Style.SECONDARY, true,
				() -> Minecraft.getInstance().setScreenAndShow(new OriginModMenuScreen(null, FpsCoach.ID)));
	}

	// ---- body ----

	@Override
	protected void drawBody(OriginDeck d) {
		try {
			switch (face()) {
				case RESULT -> drawResult(d);
				case TESTING -> drawTesting(d);
				default -> drawTips(d);
			}
		} catch (Throwable t) {
			// a draw failure leaves the bare frame; Esc / close still work
		}
	}

	private void drawTips(OriginDeck d) {
		Minecraft mc = Minecraft.getInstance();
		int target = FpsCoach.target();

		// stats row: two number cards, then the verdict card
		boolean hasStats = stats.frames() > 0;
		statCard(d, BODY_X, "Average", hasStats ? Math.round(stats.avg()) + " FPS" : "--");
		statCard(d, BODY_X + STAT_W + GAP, "1% low",
				hasStats && stats.low() > 0 ? Math.round(stats.low()) + " FPS" : "--");
		String line1, line2;
		int color1;
		double tol = Math.max(2.0, target * 0.05);
		if (mc.player == null) {
			line1 = "Join a world to measure";
			line2 = "FPS Coach counts frames while you play.";
			color1 = DIM;
		} else if (!Mods.on(FpsCoach.ID)) {
			line1 = "FPS Coach is off";
			line2 = "Turn it on to measure your FPS.";
			color1 = DIM;
		} else if (!hasStats) {
			line1 = "Still measuring...";
			line2 = "It starts 30 s after you join a world.";
			color1 = TEXT;
		} else if (stats.avg() < target - tol) {
			line1 = "Below your target";
			line2 = "Try a tip to see what really helps.";
			color1 = GOLD;
		} else {
			line1 = "On target";
			line2 = "These could still give you more FPS.";
			color1 = ACC;
		}
		float vx = BODY_X + 2 * (STAT_W + GAP), vw = BODY_X + BODY_W - vx;
		card(d, vx, BODY_Y, vw, STATS_H, false);
		d.circle(vx + 24, BODY_Y + 31, 4, color1);
		d.text(d.fit(line1, vw - 60, 15, true, 0), vx + 40, BODY_Y + 36, 15, true, 0, color1);
		d.text(d.fit(line2, vw - 60, 13, false, 0), vx + 40, BODY_Y + 58, 13, false, 0, DIM);

		// tips
		label(d, "Suggestions", BODY_X, LIST_TOP - 14);
		String hint = "Try it changes one setting and measures the difference.";
		if (tips.isEmpty()) {
			d.textCenter("No tips right now. Your settings already look light.", W / 2, LIST_TOP + 48, 14, false, 0, MUTE);
		}
		float contentH = tips.isEmpty() ? 0 : tips.size() * (ROW_H + ROW_GAP) - ROW_GAP;
		maxScroll = Math.max(0, contentH - (LIST_BOTTOM - LIST_TOP));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);

		boolean canTry = mc.player != null;
		d.clip(0, LIST_TOP - 4, W, LIST_BOTTOM - LIST_TOP + 4);
		for (int i = 0; i < tips.size(); i++) {
			float ry = LIST_TOP + i * (ROW_H + ROW_GAP) - (float) scroll;
			if (ry + ROW_H < LIST_TOP - 4 || ry > LIST_BOTTOM) {
				continue;
			}
			FpsCoachTips.Tip tip = tips.get(i);
			boolean rowHov = d.over(BODY_X, ry, BODY_W, ROW_H);
			card(d, BODY_X, ry, BODY_W, ROW_H, rowHov);

			// buttons first (right to left) so the text knows its edge
			float bx = BODY_X + BODY_W - 16, by = ry + (ROW_H - 32) / 2;
			if (tip.apply() != null) {
				float tw = Math.max(96, buttonW(d, "Try it", null));
				bx -= tw;
				button(d, "Try it", null, bx, by, tw, 32, Style.PRIMARY, canTry, () -> {
					Minecraft.getInstance().setScreenAndShow(null);   // the test only counts frames with no menu open
					FpsCoach.startTest(tip);
				});
				if (d.over(bx, by, tw, 32)) {
					hint = canTry ? tip.change() + ", then measures your FPS for ~15 s. You can undo it."
							: "Join a world to try it.";
				}
				bx -= 8;
			}
			float hw = Math.max(72, buttonW(d, "Hide", null));
			bx -= hw;
			button(d, "Hide", null, bx, by, hw, 32, Style.SECONDARY, true, () -> {
				FpsCoachTips.hide(tip.id());
				refresh();
			});
			if (d.over(bx, by, hw, 32)) {
				hint = "Don't suggest this tip again.";
			} else if (rowHov && tip.apply() == null) {
				hint = tip.detail();
			}

			float textW = bx - 16 - (BODY_X + 20);
			d.text(d.fit(tip.title(), textW, 14, true, 0), BODY_X + 20, ry + 27, 14, true, 0, TEXT);
			d.text(d.fit(tip.detail(), textW, 12.5f, false, 0), BODY_X + 20, ry + 46, 12.5f, false, 0, DIM);
		}
		d.unclip();
		scrollbar(d, LIST_TOP, LIST_BOTTOM);

		// footer: hover hint on the left, "show hidden" on the right
		float fy = BODY_Y + BODY_H - 28;
		float right = BODY_X + BODY_W;
		if (hiddenCount > 0) {
			String sh = "Show " + hiddenCount + " hidden " + (hiddenCount == 1 ? "tip" : "tips");
			float sw = buttonW(d, sh, "@reset");
			right -= sw;
			button(d, sh, "@reset", right, fy, sw, 28, Style.GHOST, true, () -> {
				FpsCoachTips.unhideAll();
				refresh();
			});
			right -= 16;
		}
		d.textMid(d.fit(hint, right - BODY_X, 12.5f, false, 0), BODY_X, fy + 14, 12.5f, false, 0, MUTE);
	}

	private void statCard(OriginDeck d, float x, String name, String value) {
		card(d, x, BODY_Y, STAT_W, STATS_H, false);
		label(d, name, x + 20, BODY_Y + 28);
		d.text(value, x + 20, BODY_Y + 62, 24, true, 0, TEXT);
	}

	private void drawResult(OriginDeck d) {
		FpsCoach.Result r = frozenResult != null ? frozenResult : FpsCoach.result();
		if (r == null) {
			return;
		}
		float cx = W / 2;
		float y = BODY_Y + 48;
		d.textCenter(d.fit(r.change(), BODY_W - 48, 15, true, 0), cx, y, 15, true, 0, TEXT);

		String big, note = null;
		int bigColor;
		boolean measured = r.after() != null && r.before() != null && r.after().frames() > 0;
		if (!measured) {
			big = "Test stopped";
			bigColor = DIM;
			note = "You left the world before it finished. The change is still on.";
		} else {
			double before = r.before().avg(), after = r.after().avg();
			double diff = after - before, noise = Math.max(2.0, before * 0.03);
			if (diff >= noise) {
				big = "+" + Math.round(diff) + " FPS";
				bigColor = ACC;
			} else if (diff <= -noise) {
				big = Math.round(diff) + " FPS";
				bigColor = RED;
				note = "It got slower. Undo puts it back.";
			} else {
				big = "No real change";
				bigColor = DIM;
				note = "Your FPS stayed about the same.";
			}
			if (r.moved()) {
				note = "You moved during the test, so this may be off.";
			}
		}
		d.textCenter(big, cx, y + 64, 44, true, 0, bigColor);

		y += 112;
		if (measured) {
			float cw = 220;
			beforeAfter(d, cx - GAP / 2 - cw, y, cw, "Before", r.before());
			beforeAfter(d, cx + GAP / 2, y, cw, "After", r.after());
			y += 96 + 24;
		} else {
			y += 24;
		}
		if (note != null) {
			d.textCenter(d.fit(note, BODY_W - 48, 13, false, 0), cx, y, 13, false, 0, GOLD);
		}
		y += 28;

		float bw = 140;
		button(d, "Keep", "@check", cx - GAP / 2 - bw, y, bw, 40, Style.PRIMARY, true, this::beginClose);
		button(d, "Undo", "@reset", cx + GAP / 2, y, bw, 40, Style.SECONDARY, true, () -> {
			freeze();
			FpsCoach.undo();
			beginClose();
		});
		d.textCenter("Esc keeps it", cx, y + 40 + 24, 12, false, 0, MUTE);
	}

	private void beforeAfter(OriginDeck d, float x, float y, float w, String name, FpsCoach.Stats s) {
		card(d, x, y, w, 96, false);
		label(d, name, x + 20, y + 28);
		d.text(Math.round(s.avg()) + " FPS", x + 20, y + 62, 24, true, 0, TEXT);
		d.text("1% low " + Math.round(s.low()), x + 20, y + 82, 12.5f, false, 0, DIM);
	}

	private void drawTesting(OriginDeck d) {
		float cx = W / 2;
		float y = BODY_Y + 96;
		d.icon(FpsCoach.ID, cx - 24, y, 48, TEXT);
		y += 48 + 40;
		String change = frozen != null ? frozenChange : FpsCoach.testingChange();
		d.textCenter(d.fit(change, BODY_W - 48, 15, true, 0), cx, y, 15, true, 0, TEXT);
		d.textCenter("Paused while a menu is open.", cx, y + 30, 13, false, 0, DIM);
		d.textCenter("Close this and stay where you are.", cx, y + 50, 13, false, 0, DIM);
		y += 88;
		float bw = 140;
		button(d, "Continue", null, cx - GAP / 2 - bw, y, bw, 40, Style.PRIMARY, true,
				() -> Minecraft.getInstance().setScreenAndShow(null));
		button(d, "Stop test", null, cx + GAP / 2, y, bw, 40, Style.SECONDARY, true, () -> {
			freeze();
			FpsCoach.stopTest();
			beginClose();
		});
	}

	// ---- input ----

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (face() == Face.TIPS) {
			return super.mouseScrolled(mx, my, sx, sy);
		}
		return true;
	}
}
