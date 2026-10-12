package com.origin.client.client.hud;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.PortalCalc;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Portal Calculator's HUD element: the matching spot in the other dimension
 * ("Nether  X 125  Y 64  Z -40") and, at a portal, where it will take you.
 * The numbers come from PortalCalc's per-tick View; this class only lays them
 * out. Same shape as the other text readouts: one content builder used by both
 * measure() and render(), options read live, background transparent by default.
 */
final class PortalCalcHud {
	private static final String MOD = PortalCalc.ID;
	private static final int LINE_H = 10;

	private PortalCalcHud() {
	}

	// Default: the top-left readout column, under Memory (Readouts stops at y 156).
	static void register() {
		HudElements.add("portalcalc", MOD, "Portal Calculator", new HudPos(0, 6, 170, 1.0),
				PortalCalcHud::size, PortalCalcHud::render);
	}

	/** One coloured run of text; a line is a list of them. */
	private record Seg(String text, int color) {
	}

	private static List<List<Seg>> lines() {
		List<List<Seg>> out = new ArrayList<>();
		PortalCalc.View v = PortalCalc.view();
		if (v == null && HudElements.editorPreview) {
			v = sample();
		}
		if (v == null) {
			return out;
		}
		int text = OriginColorPicker.liveColor(MOD, "color");
		int accent = OriginColorPicker.liveColor(MOD, "accentColor");
		int warn = OriginColorPicker.liveColor(MOD, "warnColor");
		if (v.targetName != null) {
			boolean dec = Mods.bool(MOD, "decimal");
			out.add(List.of(new Seg(v.targetName + "  ", accent),
					new Seg("X " + num(v.x, dec) + "  Y " + num(v.y, dec) + "  Z " + num(v.z, dec), text)));
		}
		String linked = v.lx + " " + v.ly + " " + v.lz;
		String spot = v.px + " " + v.py + " " + v.pz;
		switch (v.link) {
			case PortalCalc.LINK_OK -> out.add(List.of(new Seg("Links to: your portal at " + linked + " ", text),
					new Seg("✓", accent)));
			case PortalCalc.LINK_OTHER -> {
				// Two-way check failed: that portal's way back doesn't return here.
				out.add(List.of(new Seg("Will link to a different portal (" + linked + ")", warn)));
				out.add(List.of(new Seg("Build one at " + spot + " for a matching pair", text)));
			}
			case PortalCalc.LINK_NEW -> {
				out.add(List.of(new Seg("No known portal there", warn)));
				out.add(List.of(new Seg("A new one will be built near " + spot, text)));
			}
			default -> {
			}
		}
		return out;
	}

	private static PortalCalc.View sample() {
		return PortalCalc.sampleView();
	}

	private static String num(double d, boolean decimal) {
		return decimal ? String.format(Locale.ROOT, "%.1f", d) : String.valueOf((int) Math.floor(d));
	}

	private static int width(Minecraft mc, List<Seg> line) {
		int w = 0;
		for (Seg s : line) {
			w += mc.font.width(s.text());
		}
		return w;
	}

	private static int[] size(Minecraft mc) {
		List<List<Seg>> lines = lines();
		if (lines.isEmpty()) {
			return new int[]{0, 0};
		}
		int w = 1;
		for (List<Seg> l : lines) {
			w = Math.max(w, width(mc, l));
		}
		return new int[]{w + 2, lines.size() * LINE_H + 1};
	}

	private static void render(Gfx g, Minecraft mc, int w, int h) {
		List<List<Seg>> lines = lines();
		if (lines.isEmpty()) {
			return;
		}
		boolean bg = Mods.bool(MOD, "showBackground");
		boolean border = Mods.bool(MOD, "border");
		if (bg || border) {
			OriginUi.panel(g, -3, -3, w + 6, h + 6, 5,
					bg ? OriginColorPicker.liveColor(MOD, "bgColor") : 0,
					border ? OriginColorPicker.liveColor(MOD, "borderColor") : 0);
		}
		boolean shadow = Mods.bool(MOD, "textShadow");
		int y = 1;
		for (List<Seg> line : lines) {
			int x = 1;
			for (Seg s : line) {
				g.drawString(mc.font, s.text(), x, y, s.color(), shadow);
				x += mc.font.width(s.text());
			}
			y += LINE_H;
		}
	}
}
