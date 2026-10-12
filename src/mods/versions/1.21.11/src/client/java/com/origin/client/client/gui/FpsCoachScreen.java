package com.origin.client.client.gui;

import com.origin.client.client.mods.FpsCoach;
import com.origin.client.client.mods.FpsCoachTips;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

// The FPS Coach screen, in the Quickplay / Waypoints panel language. Three faces,
// picked live from FpsCoach's state:
//   TIPS    -- your recent average FPS + 1% low, then the ranked tips, each with
//              "Try it" (one change, then a measured before/after) and "Hide"
//              ("don't suggest this again").
//   RESULT  -- the measured outcome of a "Try it": change, FPS difference,
//              before/after, Keep / Undo. Esc keeps it.
//   TESTING -- shown if the coach is opened mid-test: the test is paused while
//              any menu is open; Continue or Stop test.
// Geometry comes from the same helpers for drawing and clicking, so hit boxes
// always match what is drawn.
// PER-VERSION DELTA (1.21.11): GuiGraphics.pose() is a Matrix3x2fStack
// (pushMatrix / 2-arg translate + scale), clicks arrive as a MouseButtonEvent,
// and Screen.render no longer paints the background (vanilla runs
// renderBackground before render), so super.render here only draws widgets.
public class FpsCoachScreen extends Screen {
	private static final int ROW_H = 36, ROW_GAP = 4, BTN_H = 18;

	private final Screen parent;
	private FpsCoach.Stats stats = FpsCoach.Stats.EMPTY;
	private List<FpsCoachTips.Tip> tips = List.of();
	private int hiddenCount = 0;
	private double scroll = 0;

	/** parent = where Esc goes back to (null = the game). */
	public FpsCoachScreen(Screen parent) {
		super(Component.literal("FPS Coach"));
		this.parent = parent;
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
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (FpsCoach.result() != null) {
			FpsCoach.keep();   // Esc on the result card keeps the change
		}
		Minecraft.getInstance().setScreen(parent);
	}

	private enum Face { TIPS, RESULT, TESTING }

	private Face face() {
		if (FpsCoach.result() != null) {
			return Face.RESULT;
		}
		return FpsCoach.testing() ? Face.TESTING : Face.TIPS;
	}

	// ---- geometry: tips face ----
	private int pw() {
		return Math.min(440, (int) (width * 0.86));
	}

	private int ph() {
		return Math.min(300, (int) (height * 0.88));
	}

	private int px() {
		return (width - pw()) / 2;
	}

	private int py() {
		return (height - ph()) / 2;
	}

	private int[] settingsRect() {
		int x1 = px() + pw() - 12;
		return new int[]{x1 - 60, py() + 10, x1, py() + 10 + BTN_H};
	}

	private int statsTop() {
		return py() + 40;
	}

	private int listTop() {
		return statsTop() + 48;
	}

	private int listBottom() {
		return py() + ph() - 24;
	}

	private int listX0() {
		return px() + 12;
	}

	private int listX1() {
		return px() + pw() - 12;
	}

	private int rowY(int i) {
		return listTop() + i * (ROW_H + ROW_GAP) - (int) Math.round(scroll);
	}

	private int[] tryRect(int i) {
		int y = rowY(i) + (ROW_H - BTN_H) / 2;
		return new int[]{listX1() - 8 - 56, y, listX1() - 8, y + BTN_H};
	}

	private int[] hideRect(int i) {
		int y = rowY(i) + (ROW_H - BTN_H) / 2;
		int x1 = tips.get(i).apply() != null ? tryRect(i)[0] - 6 : listX1() - 8;
		return new int[]{x1 - 40, y, x1, y + BTN_H};
	}

	private int[] showHiddenRect() {
		String label = showHiddenLabel();
		int w = OriginText.width(font, label);
		int y = py() + ph() - 18;
		return new int[]{listX1() - w, y - 2, listX1(), y + 10};
	}

	private String showHiddenLabel() {
		return "Show " + hiddenCount + " hidden " + (hiddenCount == 1 ? "tip" : "tips");
	}

	private double maxScroll() {
		return Math.max(0, tips.size() * (ROW_H + ROW_GAP) - ROW_GAP - (listBottom() - listTop()));
	}

	// ---- geometry: result / testing cards ----
	private int cw() {
		return Math.min(320, (int) (width * 0.86));
	}

	private int ch() {
		return face() == Face.RESULT ? 158 : 118;
	}

	private int cx() {
		return (width - cw()) / 2;
	}

	private int cy() {
		return (height - ch()) / 2;
	}

	private int[] cardButton(boolean left) {
		int mid = width / 2, y = cy() + ch() - 30;
		return left ? new int[]{mid - 4 - 84, y, mid - 4, y + 20} : new int[]{mid + 4, y, mid + 4 + 84, y + 20};
	}

	// ---- render ----
	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		try {
			switch (face()) {
				case RESULT -> renderResult(g, mouseX, mouseY);
				case TESTING -> renderTesting(g, mouseX, mouseY);
				default -> renderTips(g, mouseX, mouseY);
			}
		} catch (Throwable t) {
			// a draw failure leaves a plain screen; Esc still closes it
		}
	}

	private void header(GuiGraphics g, int x, int y, String title, String sub) {
		OriginUi.logo(g, x + 22, y + 20, 22, 1f);
		OriginText.drawBold(g, font, title, x + 42, y + 10, OriginTheme.TEXT, true);
		OriginText.draw(g, font, sub, x + 42, y + 22, OriginTheme.TEXT_DIM, true);
	}

	private void renderTips(GuiGraphics g, int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		int x = px(), y = py(), w = pw(), h = ph();
		int target = FpsCoach.target();
		OriginUi.panel(g, x, y, w, h, 10, 0xC80E0E0E, OriginTheme.STROKE);
		header(g, x, y, "FPS Coach", "Your target: " + target + " FPS");
		int[] sr = settingsRect();
		button(g, sr, "Settings", "fpscoach:settings", in(mouseX, mouseY, sr), false, true);

		// stats row: two boxes, then a plain-English verdict
		int sy = statsTop();
		boolean hasStats = stats.frames() > 0;
		statBox(g, listX0(), sy, "AVERAGE", hasStats ? Math.round(stats.avg()) + " FPS" : "--");
		statBox(g, listX0() + 104, sy, "1% LOW", hasStats && stats.low() > 0 ? Math.round(stats.low()) + " FPS" : "--");
		String line1, line2;
		int color1;
		double tol = Math.max(2.0, target * 0.05);
		if (mc.player == null) {
			line1 = "Join a world to measure";
			line2 = "FPS Coach counts frames while you play.";
			color1 = OriginTheme.MUTED;
		} else if (!Mods.on(FpsCoach.ID)) {
			line1 = "FPS Coach is off";
			line2 = "Turn it on to measure your FPS.";
			color1 = OriginTheme.MUTED;
		} else if (!hasStats) {
			line1 = "Still measuring...";
			line2 = "It starts 30 s after you join a world.";
			color1 = OriginTheme.TEXT_DIM;
		} else if (stats.avg() < target - tol) {
			line1 = "Below your target";
			line2 = "Try a tip to see what really helps.";
			color1 = OriginTheme.WARNING;
		} else {
			line1 = "On target";
			line2 = "These could still give you more FPS.";
			color1 = OriginTheme.SUCCESS;
		}
		int tx = listX0() + 208, tw = listX1() - tx;
		OriginText.drawBold(g, font, OriginText.ellipsize(font, line1, tw), tx, sy + 8, color1, true);
		OriginText.draw(g, font, OriginText.ellipsize(font, line2, tw), tx, sy + 21, OriginTheme.TEXT_DIM, true);

		// tips
		String hint = "Try it changes one setting and measures the difference.";
		int top = listTop(), bottom = listBottom();
		if (tips.isEmpty()) {
			String empty = "No tips right now. Your settings already look light.";
			OriginText.draw(g, font, empty, x + (w - OriginText.width(font, empty)) / 2, top + 24, OriginTheme.TEXT_DIM, true);
		}
		boolean canTry = mc.player != null;
		g.enableScissor(listX0(), top, listX1(), bottom);
		for (int i = 0; i < tips.size(); i++) {
			int ry = rowY(i);
			if (ry + ROW_H < top || ry > bottom) {
				continue;
			}
			FpsCoachTips.Tip tip = tips.get(i);
			boolean inList = mouseY >= top && mouseY <= bottom;
			OriginUi.panel(g, listX0(), ry, listX1() - listX0(), ROW_H, 5, OriginTheme.BOX_FILL, OriginTheme.BOX_BORDER);
			int[] hr = hideRect(i);
			int textW = hr[0] - 8 - (listX0() + 8);
			OriginText.drawBold(g, font, OriginText.ellipsize(font, tip.title(), textW), listX0() + 8, ry + 7, OriginTheme.TEXT, true);
			OriginText.draw(g, font, OriginText.ellipsize(font, tip.detail(), textW), listX0() + 8, ry + 20, OriginTheme.TEXT_DIM, true);
			boolean hideHover = inList && in(mouseX, mouseY, hr);
			button(g, hr, "Hide", "fpscoach:hide:" + tip.id(), hideHover, false, true);
			if (hideHover) {
				hint = "Don't suggest this tip again.";
			}
			if (tip.apply() != null) {
				int[] tr = tryRect(i);
				boolean tryHover = inList && in(mouseX, mouseY, tr);
				button(g, tr, "Try it", "fpscoach:try:" + tip.id(), tryHover && canTry, true, canTry);
				if (tryHover) {
					hint = canTry ? tip.change() + ", then measures your FPS for ~15 s. You can undo it."
							: "Join a world to try it.";
				}
			} else if (inList && in(mouseX, mouseY, new int[]{listX0(), ry, listX1(), ry + ROW_H})) {
				hint = tip.detail();
			}
		}
		g.disableScissor();

		// footer: hover hint on the left, "show hidden" on the right
		int fy = y + h - 18;
		int hintW = w - 24 - (hiddenCount > 0 ? OriginText.width(font, showHiddenLabel()) + 12 : 0);
		OriginText.draw(g, font, OriginText.ellipsize(font, hint, hintW), listX0(), fy, OriginTheme.MUTED, true);
		if (hiddenCount > 0) {
			int[] r = showHiddenRect();
			boolean hover = in(mouseX, mouseY, r);
			OriginText.draw(g, font, showHiddenLabel(), r[0], fy, hover ? OriginTheme.ACCENT_2 : OriginTheme.ACCENT, true);
		}
	}

	private void statBox(GuiGraphics g, int x, int y, String label, String value) {
		OriginUi.panel(g, x, y, 96, 36, 5, OriginTheme.BOX_FILL, OriginTheme.BOX_BORDER);
		OriginText.drawBold(g, font, label, x + 8, y + 6, OriginTheme.MUTED, true);
		g.pose().pushMatrix();
		g.pose().translate(x + 8, y + 17);
		g.pose().scale(1.5f, 1.5f);
		OriginText.drawBold(g, font, value, 0, 0, OriginTheme.TEXT, true);
		g.pose().popMatrix();
	}

	private void renderResult(GuiGraphics g, int mouseX, int mouseY) {
		FpsCoach.Result r = FpsCoach.result();
		int x = cx(), y = cy(), w = cw(), h = ch();
		OriginUi.panel(g, x, y, w, h, 10, 0xE00E0E0E, OriginTheme.STROKE_STRONG);
		header(g, x, y, "FPS Coach", "Test result");
		String esc = "Esc keeps it";
		OriginText.draw(g, font, esc, x + w - 10 - OriginText.width(font, esc), y + 10, OriginTheme.MUTED, true);

		centered(g, OriginText.ellipsize(font, r.change(), w - 24), y + 40, OriginTheme.TEXT, true);
		String big, note = null;
		int bigColor;
		if (r.after() == null || r.before() == null || r.after().frames() == 0) {
			big = "Test stopped";
			bigColor = OriginTheme.MUTED;
			note = "You left the world before it finished. The change is still on.";
		} else {
			double before = r.before().avg(), after = r.after().avg();
			double d = after - before, noise = Math.max(2.0, before * 0.03);
			if (d >= noise) {
				big = "+" + Math.round(d) + " FPS";
				bigColor = OriginTheme.SUCCESS;
			} else if (d <= -noise) {
				big = Math.round(d) + " FPS";
				bigColor = OriginTheme.DANGER;
				note = "It got slower. Undo puts it back.";
			} else {
				big = "No real change";
				bigColor = OriginTheme.TEXT_DIM;
				note = "Your FPS stayed about the same.";
			}
			if (r.moved()) {
				note = "You moved during the test, so this may be off.";
			}
			String b = "Before: " + Math.round(before) + " FPS  ·  1% low " + Math.round(r.before().low());
			String a = "After: " + Math.round(after) + " FPS  ·  1% low " + Math.round(r.after().low());
			centered(g, b, y + 82, OriginTheme.TEXT_DIM, false);
			centered(g, a, y + 94, OriginTheme.TEXT_DIM, false);
		}
		g.pose().pushMatrix();
		int bw = OriginText.widthBold(font, big) * 2;
		g.pose().translate(x + (w - bw) / 2f, y + 56);
		g.pose().scale(2f, 2f);
		OriginText.drawBold(g, font, big, 0, 0, bigColor, true);
		g.pose().popMatrix();
		if (note != null) {
			centered(g, OriginText.ellipsize(font, note, w - 24), y + h - 46, OriginTheme.WARNING, false);
		}
		int[] keep = cardButton(true), undo = cardButton(false);
		button(g, keep, "Keep", "fpscoach:keep", in(mouseX, mouseY, keep), true, true);
		button(g, undo, "Undo", "fpscoach:undo", in(mouseX, mouseY, undo), false, true);
	}

	private void renderTesting(GuiGraphics g, int mouseX, int mouseY) {
		int x = cx(), y = cy(), w = cw(), h = ch();
		OriginUi.panel(g, x, y, w, h, 10, 0xE00E0E0E, OriginTheme.STROKE_STRONG);
		header(g, x, y, "FPS Coach is testing", "About " + FpsCoach.secondsLeft() + " s left");
		centered(g, OriginText.ellipsize(font, FpsCoach.testingChange(), w - 24), y + 44, OriginTheme.TEXT, true);
		centered(g, "Paused while a menu is open.", y + 58, OriginTheme.TEXT_DIM, false);
		centered(g, "Close this and stay where you are.", y + 70, OriginTheme.TEXT_DIM, false);
		int[] cont = cardButton(true), stop = cardButton(false);
		button(g, cont, "Continue", "fpscoach:continue", in(mouseX, mouseY, cont), true, true);
		button(g, stop, "Stop test", "fpscoach:stop", in(mouseX, mouseY, stop), false, true);
	}

	private void centered(GuiGraphics g, String s, int y, int color, boolean bold) {
		int tw = bold ? OriginText.widthBold(font, s) : OriginText.width(font, s);
		if (bold) {
			OriginText.drawBold(g, font, s, (width - tw) / 2, y, color, true);
		} else {
			OriginText.draw(g, font, s, (width - tw) / 2, y, color, true);
		}
	}

	private void button(GuiGraphics g, int[] r, String label, String id, boolean hover, boolean primary, boolean enabled) {
		float hp = OriginUi.hover(id, hover && enabled);
		int fill = primary && enabled ? OriginTheme.ACCENT_SOFT
				: OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, hp);
		int border = primary && enabled
				? OriginTheme.lerpColor(OriginTheme.ACCENT_BORDER, OriginTheme.ACCENT, hp)
				: OriginTheme.lerpColor(OriginTheme.BOX_BORDER, OriginTheme.BOX_BORDER_HOVER, hp);
		OriginUi.bevelPanel(g, r[0], r[1], r[2] - r[0], r[3] - r[1], 3, fill, border);
		String shown = OriginText.ellipsize(font, label, r[2] - r[0] - 8);
		OriginText.draw(g, font, shown, r[0] + (r[2] - r[0] - OriginText.width(font, shown)) / 2,
				r[1] + (r[3] - r[1] - 8) / 2, enabled ? OriginTheme.TEXT : OriginTheme.MUTED, true);
	}

	// ---- input ----
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x(), my = event.y();
		if (event.button() != 0) {
			return super.mouseClicked(event, doubled);
		}
		Minecraft mc = Minecraft.getInstance();
		switch (face()) {
			case RESULT -> {
				if (in(mx, my, cardButton(true))) {
					FpsCoach.keep();
					onClose();
					return true;
				}
				if (in(mx, my, cardButton(false))) {
					FpsCoach.undo();
					onClose();
					return true;
				}
			}
			case TESTING -> {
				if (in(mx, my, cardButton(true))) {
					mc.setScreen(null);
					return true;
				}
				if (in(mx, my, cardButton(false))) {
					FpsCoach.stopTest();
					onClose();
					return true;
				}
			}
			default -> {
				if (in(mx, my, settingsRect())) {
					mc.setScreen(new OriginModMenuScreen(null, FpsCoach.ID));
					return true;
				}
				if (hiddenCount > 0 && in(mx, my, showHiddenRect())) {
					FpsCoachTips.unhideAll();
					refresh();
					return true;
				}
				if (my >= listTop() && my <= listBottom()) {
					for (int i = 0; i < tips.size(); i++) {
						FpsCoachTips.Tip tip = tips.get(i);
						if (in(mx, my, hideRect(i))) {
							FpsCoachTips.hide(tip.id());
							refresh();
							return true;
						}
						if (tip.apply() != null && mc.player != null && in(mx, my, tryRect(i))) {
							mc.setScreen(null);   // the test only counts frames with no menu open
							FpsCoach.startTest(tip);
							return true;
						}
					}
				}
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (face() == Face.TIPS) {
			scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * (ROW_H + ROW_GAP)));
		}
		return true;
	}

	private static boolean in(double mx, double my, int[] r) {
		return mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3];
	}
}
