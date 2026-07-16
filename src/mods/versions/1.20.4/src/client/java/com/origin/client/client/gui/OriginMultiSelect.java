package com.origin.client.client.gui;

import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The shared ordered multi-select overlay — the multi-value analogue of
 * {@link OriginColorPicker}, built the same way: a modal with static working
 * state that the mod menu draws last and feeds input to first.
 *
 * A value is an ORDERED subset of a fixed choice set (JEI's sort stages, tooltip
 * features, ...). The panel shows the chosen items in order at the top — each with
 * up/down to reorder and ✕ to remove — and the remaining choices below, each a
 * click away from being appended. Every edit calls {@code onChange} with the live
 * ordered selection, so the backing store (JEI) updates immediately, matching how
 * the color picker commits live.
 *
 * Tokens are stored RAW (e.g. MOD_NAME); only the display is prettified.
 */
public final class OriginMultiSelect {
	private OriginMultiSelect() {
	}

	private static boolean open = false;
	private static String title;
	private static List<String> all = List.of();          // full choice set, canonical order
	private static final List<String> selected = new ArrayList<>(); // working ordered subset
	private static Consumer<List<String>> onChange = c -> {
	};

	private static final int PW = 240;
	private static final int ROW = 20;
	private static final int HEADER_H = 28;
	private static final int DIV_H = 16;
	private static final int PAD = 10;

	private static int px, py, ph;

	public static boolean isOpen() {
		return open;
	}

	public static void open(String title, List<String> all, List<String> selected, Consumer<List<String>> onChange) {
		OriginMultiSelect.title = title;
		OriginMultiSelect.all = new ArrayList<>(all);
		OriginMultiSelect.selected.clear();
		// keep only known tokens, preserving the caller's order
		for (String s : selected) {
			if (all.contains(s) && !OriginMultiSelect.selected.contains(s)) {
				OriginMultiSelect.selected.add(s);
			}
		}
		OriginMultiSelect.onChange = onChange;
		open = true;
	}

	public static void close() {
		open = false;
	}

	private static List<String> available() {
		List<String> out = new ArrayList<>();
		for (String s : all) {
			if (!selected.contains(s)) {
				out.add(s);
			}
		}
		return out;
	}

	private static void fire() {
		onChange.accept(new ArrayList<>(selected));
	}

	// ---- render ----

	public static void render(GuiGraphics g, int mx, int my) {
		if (!open) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();

		List<String> avail = available();
		ph = HEADER_H + selected.size() * ROW + DIV_H + avail.size() * ROW + PAD;
		px = Math.max(2, (sw - PW) / 2);
		py = Math.max(2, (sh - ph) / 2);

		g.fill(0, 0, sw, sh, 0x66000000);
		OriginUi.panel(g, px, py, PW, ph, 12, 0xF2101010, OriginTheme.STROKE_STRONG);

		g.drawString(font, title == null ? "Select" : title, px + 12, py + 10, OriginTheme.TEXT, false);
		boolean closeHover = in(mx, my, px + PW - 26, py + 8, px + PW - 8, py + 24);
		g.drawString(font, "✕", px + PW - 22, py + 10, closeHover ? OriginTheme.TEXT : OriginTheme.MUTED, false);

		int x0 = px + 10, x1 = px + PW - 10;
		int y = py + HEADER_H;

		// selected, in order — badge, label, [▲ ▼ ✕]
		for (int i = 0; i < selected.size(); i++) {
			String raw = selected.get(i);
			boolean rowHover = in(mx, my, x0, y, x1, y + ROW - 2);
			OriginUi.panel(g, x0, y, x1 - x0, ROW - 2, 6,
					rowHover ? 0x1EFFFFFF : 0x12FFFFFF, OriginTheme.STROKE);
			g.drawString(font, String.valueOf(i + 1), x0 + 6, y + 5, OriginTheme.MUTED, false);
			g.drawString(font, JeiSettings.prettify(raw), x0 + 20, y + 5, OriginTheme.TEXT, false);

			int upX = x1 - 56, dnX = x1 - 38, rmX = x1 - 18;
			drawGlyph(g, font, "▲", upX, y, mx, my, i > 0);
			drawGlyph(g, font, "▼", dnX, y, mx, my, i < selected.size() - 1);
			drawGlyph(g, font, "✕", rmX, y, mx, my, true);
			y += ROW;
		}

		// divider
		g.drawString(font, selected.isEmpty() ? "PICK ONE OR MORE" : "ADD MORE",
				x0 + 2, y + 4, OriginTheme.MUTED, false);
		g.fill(x0 + 2 + font.width(selected.isEmpty() ? "PICK ONE OR MORE" : "ADD MORE") + 6, y + 8, x1, y + 9,
				OriginTheme.STROKE);
		y += DIV_H;

		// available — click to append
		for (String raw : avail) {
			boolean rowHover = in(mx, my, x0, y, x1, y + ROW - 2);
			OriginUi.panel(g, x0, y, x1 - x0, ROW - 2, 6,
					rowHover ? 0x1EFFFFFF : 0x0AFFFFFF, OriginTheme.STROKE);
			g.drawString(font, JeiSettings.prettify(raw), x0 + 8, y + 5,
					rowHover ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, false);
			g.drawString(font, "+", x1 - 14, y + 5, rowHover ? OriginTheme.TEXT : OriginTheme.MUTED, false);
			y += ROW;
		}
	}

	private static void drawGlyph(GuiGraphics g, Font font, String s, int x, int y, int mx, int my, boolean enabled) {
		boolean hover = enabled && in(mx, my, x - 2, y, x + 14, y + ROW - 2);
		int color = !enabled ? OriginTheme.STROKE_HOVER : (hover ? OriginTheme.TEXT : OriginTheme.TEXT_DIM);
		g.drawString(font, s, x, y + 5, color, false);
	}

	// ---- input ----

	public static boolean mouseClicked(double mx, double my, int button) {
		if (!open) {
			return false;
		}
		if (in(mx, my, px + PW - 26, py + 8, px + PW - 8, py + 24)) {
			close();
			return true;
		}
		if (!in(mx, my, px, py, px + PW, py + ph)) {
			close(); // click outside dismisses
			return true;
		}

		int x0 = px + 10, x1 = px + PW - 10;
		int y = py + HEADER_H;

		for (int i = 0; i < selected.size(); i++) {
			int upX = x1 - 56, dnX = x1 - 38, rmX = x1 - 18;
			if (i > 0 && in(mx, my, upX - 2, y, upX + 14, y + ROW - 2)) {
				java.util.Collections.swap(selected, i, i - 1);
				fire();
				return true;
			}
			if (i < selected.size() - 1 && in(mx, my, dnX - 2, y, dnX + 14, y + ROW - 2)) {
				java.util.Collections.swap(selected, i, i + 1);
				fire();
				return true;
			}
			if (in(mx, my, rmX - 2, y, rmX + 14, y + ROW - 2)) {
				selected.remove(i);
				fire();
				return true;
			}
			y += ROW;
		}

		y += DIV_H; // skip the divider label row
		for (String raw : available()) {
			if (in(mx, my, x0, y, x1, y + ROW - 2)) {
				selected.add(raw); // append → becomes last in the order
				fire();
				return true;
			}
			y += ROW;
		}
		return true; // modal: swallow everything inside the panel
	}

	public static boolean keyPressed(int keyCode) {
		if (!open) {
			return false;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			close();
		}
		return true; // swallow keys while modal
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}
}
