package com.origin.client.client.hud;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.gui.Pose;
import com.origin.client.client.mods.CombatStats;
import com.origin.client.client.mods.ItemCounter;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.PingSampler;
import com.origin.client.client.mods.Stopwatch;
import com.origin.client.client.waypoints.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.WorldInfo;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The Lunar-parity HUD readouts (batch 1): Clock, Stopwatch, Day Counter, Ping,
// Memory Usage, Saturation HUD, Pack Display, Item Counter, Direction HUD,
// Combo Counter and Reach Display. Registered into HudElements.ALL (so the HUD
// editor drags them like every other element) by HudElements' static block.
//
// Kept out of HudElements.java only for size: the shape is identical -- a
// content builder used by BOTH measure() and render(), options read live from
// Mods every frame, and the same sizing rule (the box is what's shown now).
// Every text readout draws through textBox(), which is where the shared
// textReadout() option block from Mods (shadow / brackets / background /
// border + colours) is honoured, so all of them behave the same.
//
// 1.8.9 port: drawn through the Gfx shim; text is plain formatted String; the
// day count reads WorldInfo (the save data -- Time Changer only overrides the
// client World's clock); Pack Display walks ResourcePackRepository's selected
// entries, whose own lazily-loaded icon texture is bound with
// Entry.bindTexturePackIcon (no NativeImage on 1.8.9).
final class Readouts {
	private Readouts() {
	}

	static void register() {
		// Default placements: a column under the existing top-left readouts,
		// combat readouts at the left edge middle, Pack Display bottom-right,
		// Item Counter top-right under the potion list, Direction HUD top-centre
		// (where Lunar puts it), and the Saturation readout beside the hunger bar.
		textElement("clock", "Clock", new HudPos(0, 6, 100, 1.0), Readouts::clockText);
		textElement("stopwatch", "Stopwatch", new HudPos(0, 6, 114, 1.0), Readouts::stopwatchText);
		textElement("daycounter", "Day Counter", new HudPos(0, 6, 128, 1.0), Readouts::dayText);
		textElement("ping", "Ping", new HudPos(0, 6, 142, 1.0), Readouts::pingText);
		textElement("memory", "Memory", new HudPos(0, 6, 156, 1.0), Readouts::memoryText);
		textElement("combo", "Combo", new HudPos(3, 6, 20, 1.0), Readouts::comboText);
		textElement("reach", "Reach", new HudPos(3, 6, 34, 1.0), Readouts::reachText);

		HudElements.add("saturationhud", "saturation", "Saturation", new HudPos(7, 140, -36, 1.0),
				mc -> Mods.bool("saturation", "showHud") ? textSize(mc, "saturation", saturationText(mc)) : new int[]{0, 0},
				(g, mc, w, h) -> {
					if (Mods.bool("saturation", "showHud")) {
						textBox(g, mc, "saturation", saturationText(mc), w, h);
					}
				});

		HudElements.add("packdisplay", "packdisplay", "Pack Display", new HudPos(8, -6, -6, 1.0),
				Readouts::packSize, Readouts::renderPacks);

		HudElements.add("itemcounter", "itemcounter", "Item Counter", new HudPos(2, -6, 60, 1.0),
				Readouts::itemCounterSize, Readouts::renderItemCounter);

		HudElements.add("directionhud", "directionhud", "Direction", new HudPos(1, 0, 4, 1.0),
				mc -> new int[]{dirWidth(), DIR_H}, Readouts::renderDirection);
	}

	// ---- shared text readout -------------------------------------------------

	/** A readout's text split into a label part (Text Color) and a value part that
	 *  may carry its own colour (Ping's dynamic colour, Memory's usage colour).
	 *  valueColor 0 = use the Text Color too. null text = draw nothing. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	static final class Line {
		private final String label;
		private final String value;
		private final int valueColor;

		Line(String label, String value, int valueColor) {
			this.label = label;
			this.value = value;
			this.valueColor = valueColor;
		}

		public String label() {
			return label;
		}

		public String value() {
			return value;
		}

		public int valueColor() {
			return valueColor;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Line)) {
				return false;
			}
			Line other = (Line) obj;
			return java.util.Objects.equals(this.label, other.label)
					&& java.util.Objects.equals(this.value, other.value)
					&& this.valueColor == other.valueColor;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(label, value, valueColor);
		}

		static Line of(String text) {
			return new Line(text, "", 0);
		}

		String full() {
			return label + value;
		}
	}

	private interface LineSource {
		Line line(Minecraft mc);
	}

	private static void textElement(String modId, String label, HudPos def, LineSource src) {
		HudElements.add(modId, modId, label, def, mc -> {
			Line l = src.line(mc);
			return l == null ? new int[]{0, 0} : textSize(mc, modId, l.full());
		}, (g, mc, w, h) -> {
			Line l = src.line(mc);
			if (l != null) {
				textBox(g, mc, modId, l, w, h);
			}
		});
	}

	private static String bracketed(String modId, String s) {
		return Mods.bool(modId, "showBrackets") ? "[" + s + "]" : s;
	}

	static int[] textSize(Minecraft mc, String modId, String text) {
		return new int[]{mc.fontRendererObj.getStringWidth(bracketed(modId, text)) + 2, 11};
	}

	static void textBox(Gfx g, Minecraft mc, String modId, String text, int w, int h) {
		textBox(g, mc, modId, Line.of(text), w, h);
	}

	/** Background / border, then "[label value]" with each part in its colour. */
	static void textBox(Gfx g, Minecraft mc, String modId, Line line, int w, int h) {
		panel(g, modId, w, h);
		FontRenderer font = mc.fontRendererObj;
		boolean shadow = Mods.bool(modId, "textShadow");
		int color = OriginColorPicker.liveColor(modId, "color");
		int x = 1;
		boolean brackets = Mods.bool(modId, "showBrackets");
		int bracketColor = Mods.hasOption(modId, "bracketColor") ? OriginColorPicker.liveColor(modId, "bracketColor") : color;
		if (brackets) {
			g.drawString(font, "[", x, 1, bracketColor, shadow);
			x += font.getStringWidth("[");
		}
		g.drawString(font, line.label(), x, 1, color, shadow);
		x += font.getStringWidth(line.label());
		if (!line.value().isEmpty()) {
			g.drawString(font, line.value(), x, 1, line.valueColor() != 0 ? line.valueColor() : color, shadow);
			x += font.getStringWidth(line.value());
		}
		if (brackets) {
			g.drawString(font, "]", x, 1, bracketColor, shadow);
		}
	}

	/** The mod's Show Background fill + optional Border, sized to the content box. */
	private static void panel(Gfx g, String modId, int w, int h) {
		boolean bg = Mods.bool(modId, "showBackground");
		boolean border = Mods.hasOption(modId, "border") && Mods.bool(modId, "border");
		if (bg || border) {
			OriginUi.panel(g, -3, -3, w + 6, h + 6, 5,
					bg ? OriginColorPicker.liveColor(modId, "bgColor") : 0,
					border ? OriginColorPicker.liveColor(modId, "borderColor") : 0);
		}
	}

	// ---- content builders ----------------------------------------------------

	private static Line clockText(Minecraft mc) {
		LocalTime t = LocalTime.now();
		boolean secs = Mods.bool("clock", "showSeconds");
		String s;
		if (Mods.bool("clock", "militaryTime")) {
			s = secs ? String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond())
					: String.format("%02d:%02d", t.getHour(), t.getMinute());
		} else {
			int h12 = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
			s = secs ? String.format("%d:%02d:%02d", h12, t.getMinute(), t.getSecond())
					: String.format("%d:%02d", h12, t.getMinute());
			if (Mods.bool("clock", "showAmPm")) {
				s += t.getHour() < 12 ? " AM" : " PM";
			}
		}
		return Line.of(s);
	}

	private static Line stopwatchText(Minecraft mc) {
		if (Mods.bool("stopwatch", "hideWhenStopped") && !Stopwatch.running() && !HudElements.editorPreview) {
			return null;
		}
		return Line.of(Stopwatch.format(Stopwatch.elapsedMs(), Mods.bool("stopwatch", "showMillis")));
	}

	private static Line dayText(Minecraft mc) {
		if (mc.theWorld == null) {
			return Line.of("Day 0");
		}
		// Read the world DATA (WorldInfo), not World.getWorldTime(): the Time Changer
		// mod overrides the latter client-side, and the day must not follow it.
		WorldInfo data = mc.theWorld.getWorldInfo();
		long ticks = Mods.bool("daycounter", "useWorldTime") ? data.getWorldTime() : data.getWorldTotalTime();
		return Line.of("Day " + Math.max(0, ticks / 24000L));
	}

	private static Line pingText(Minecraft mc) {
		int avg = PingSampler.average();
		if (avg < 0) {
			if (!HudElements.editorPreview) {
				return null;   // not connected yet: no box at all
			}
			avg = 42;
		}
		String label = Mods.bool("ping", "showPrefix") ? "Ping: " : "";
		String value = avg + (Mods.bool("ping", "showMs") ? "ms" : "");
		int col = 0;
		if (Mods.bool("ping", "spikeDetection") && PingSampler.spike() >= Mods.num("ping", "largeSpike")) {
			col = OriginColorPicker.liveColor("ping", "largeSpikeColor");
		} else if (Mods.bool("ping", "spikeDetection") && PingSampler.spike() >= Mods.num("ping", "mediumSpike")) {
			col = OriginColorPicker.liveColor("ping", "mediumSpikeColor");
		} else if (Mods.bool("ping", "dynamicColor")) {
			col = OriginColorPicker.liveColor("ping", avg <= 50 ? "lowColor" : avg <= 100 ? "mediumColor"
					: avg <= 200 ? "highColor" : "extremeColor");
		}
		return new Line(label, value, col);
	}

	private static Line memoryText(Minecraft mc) {
		Runtime rt = Runtime.getRuntime();
		long max = rt.maxMemory();
		long used = rt.totalMemory() - rt.freeMemory();
		int pct = (int) Math.round(used * 100.0 / Math.max(1, max));
		String abs = (used >> 20) + "/" + (max >> 20) + "MB";
		String value;
		switch (Mods.mode("memory", "displayMode")) {
			case "Used / Max": value = abs; break;
			case "Both": value = pct + "% " + abs; break;
			default: value = pct + "%"; break;
		}
		int col = 0;
		if (Mods.bool("memory", "colorByUsage")) {
			col = OriginColorPicker.liveColor("memory", pct < 50 ? "lowColor" : pct <= 80 ? "mediumColor" : "highColor");
		}
		return new Line("Mem: ", value, col);
	}

	private static Line comboText(Minecraft mc) {
		int c = CombatStats.combo;
		if (c == 0 && Mods.bool("combo", "hideZero") && !HudElements.editorPreview) {
			return null;
		}
		return Line.of(Mods.bool("combo", "reverseOrder") ? c + " Combo" : "Combo: " + c);
	}

	private static Line reachText(Minecraft mc) {
		double r = CombatStats.lastReach;
		if (r <= 0 && Mods.bool("reach", "hideZero") && !HudElements.editorPreview) {
			return null;
		}
		String v = String.format(Locale.ROOT, "%.2f", r);
		return Line.of(Mods.bool("reach", "reverseOrder") ? v + " blocks" : "Reach: " + v);
	}

	private static String saturationText(Minecraft mc) {
		float sat = mc.thePlayer != null ? mc.thePlayer.getFoodStats().getSaturationLevel() : 5f;
		return String.format(Locale.ROOT, "Saturation: %.1f", sat);
	}

	// ---- Pack Display ---------------------------------------------------------

	private static final ResourceLocation UNKNOWN_PACK = new ResourceLocation("textures/misc/unknown_pack.png");

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	// 1.8.9: `entry` is the pack whose icon to bind (null = the unknown-pack
	// texture), `icon` whether to draw one at all.
	private static final class PackRow {
		private final String name;
		private final List<String> desc;
		private final ResourcePackRepository.Entry entry;
		private final boolean icon;

		private PackRow(String name, List<String> desc, ResourcePackRepository.Entry entry, boolean icon) {
			this.name = name;
			this.desc = desc;
			this.entry = entry;
			this.icon = icon;
		}

		public String name() {
			return name;
		}

		public List<String> desc() {
			return desc;
		}

		public ResourcePackRepository.Entry entry() {
			return entry;
		}

		public boolean icon() {
			return icon;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof PackRow)) {
				return false;
			}
			PackRow other = (PackRow) obj;
			return java.util.Objects.equals(this.name, other.name)
					&& java.util.Objects.equals(this.desc, other.desc)
					&& this.entry == other.entry
					&& this.icon == other.icon;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(name, desc, entry, icon);
		}
	}

	/** The packs to show: the top-most enabled pack, or every one (top first).
	 *  1.8.9: the selected list holds only the player's packs (the default pack is
	 *  never in it), bottom -> top as Minecraft.refreshResources stacks them. */
	private static List<PackRow> packRows(Minecraft mc) {
		List<ResourcePackRepository.Entry> chosen = new ArrayList<>(mc.getResourcePackRepository().getRepositoryEntries());
		java.util.Collections.reverse(chosen);   // selected order is bottom -> top
		if (!Mods.bool("packdisplay", "showAllPacks") && chosen.size() > 1) {
			chosen = chosen.subList(0, 1);
		}
		boolean icon = Mods.bool("packdisplay", "packIcon");
		boolean desc = Mods.bool("packdisplay", "packDescription");
		List<PackRow> rows = new ArrayList<>();
		for (ResourcePackRepository.Entry p : chosen) {
			List<String> lines = java.util.Collections.emptyList();
			if (desc) {
				String d = p.getTexturePackDescription();   // 1.8.9 NAME UNVERIFIED
				lines = d == null ? java.util.Collections.<String>emptyList()
						: mc.fontRendererObj.listFormattedStringToWidth(d, 160);
			}
			rows.add(new PackRow(packName(p), lines, p, icon));
		}
		if (rows.isEmpty()) {
			rows.add(new PackRow("Default", java.util.Collections.<String>emptyList(), null, icon));
		}
		return rows;
	}

	/** A pack's display name: file packs are titled by their file name, so the
	 *  extension and any legacy formatting codes in that name come through here. */
	private static String packName(ResourcePackRepository.Entry p) {
		String n = p.getResourcePackName();   // 1.8.9 NAME UNVERIFIED
		if (n == null) {
			n = "Pack";
		}
		if (!Mods.bool("packdisplay", "packExtension") && n.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			n = n.substring(0, n.length() - 4);
		}
		if (!Mods.bool("packdisplay", "keepStrikethrough")) {
			n = n.replaceAll("(?i)§m", "");
		}
		if (!Mods.bool("packdisplay", "keepObfuscated")) {
			n = n.replaceAll("(?i)§k", "");
		}
		return n;
	}

	private static int packRowHeight(PackRow r) {
		int text = 10 + r.desc().size() * 9;
		return Math.max(r.icon() ? 18 : 0, text);
	}

	private static int[] packSize(Minecraft mc) {
		FontRenderer font = mc.fontRendererObj;
		int w = 1, h = 0;
		for (PackRow r : packRows(mc)) {
			int tw = font.getStringWidth(bracketed("packdisplay", r.name()));
			for (String d : r.desc()) {
				tw = Math.max(tw, font.getStringWidth(d));
			}
			w = Math.max(w, (r.icon() ? 20 : 0) + tw + 2);
			h += packRowHeight(r) + 2;
		}
		return new int[]{w, Math.max(11, h - 2)};
	}

	private static void renderPacks(Gfx g, Minecraft mc, int w, int h) {
		panel(g, "packdisplay", w, h);
		FontRenderer font = mc.fontRendererObj;
		boolean shadow = Mods.bool("packdisplay", "textShadow");
		int color = OriginColorPicker.liveColor("packdisplay", "color");
		int y = 0;
		for (PackRow r : packRows(mc)) {
			int rh = packRowHeight(r);
			int x = 0;
			if (r.icon()) {
				drawPackIcon(g, mc, r.entry(), 0, y + (rh - 16) / 2);
				x = 20;
			}
			int textH = 10 + r.desc().size() * 9;
			int ty = y + (rh - textH) / 2 + 1;
			// The pack name goes through the same bracket/colour path as every
			// readout; its own legacy codes (colours, bold, ...) still apply.
			Pose p = g.pose();
			p.pushPose();
			p.translate(x, ty - 1, 0);
			textBoxNoPanel(g, mc, "packdisplay", r.name());
			p.popPose();
			int dy = ty + 10;
			for (String d : r.desc()) {
				g.drawString(font, d, x + 1, dy, color, shadow);
				dy += 9;
			}
			y += rh + 2;
		}
	}

	/** The pack's own icon (the repository entry loads + binds it), or the unknown-pack art. */
	private static void drawPackIcon(Gfx g, Minecraft mc, ResourcePackRepository.Entry entry, int x, int y) {
		Gfx.resetColor();
		Gfx.defaultBlend();
		try {
			if (entry != null) {
				entry.bindTexturePackIcon(mc.getTextureManager());   // 1.8.9 NAME UNVERIFIED
				g.blitBound(x, y, 16, 16, 0f, 0f, 1f, 1f);
			} else {
				g.blit(UNKNOWN_PACK, x, y, 0f, 0f, 16, 16, 16, 16);
			}
		} catch (Throwable t) {
			g.blit(UNKNOWN_PACK, x, y, 0f, 0f, 16, 16, 16, 16);
		} finally {
			GlStateManager.disableBlend();
		}
	}

	private static void textBoxNoPanel(Gfx g, Minecraft mc, String modId, String text) {
		FontRenderer font = mc.fontRendererObj;
		boolean shadow = Mods.bool(modId, "textShadow");
		int color = OriginColorPicker.liveColor(modId, "color");
		int bracketColor = OriginColorPicker.liveColor(modId, "bracketColor");
		boolean brackets = Mods.bool(modId, "showBrackets");
		int x = 1;
		if (brackets) {
			g.drawString(font, "[", x, 1, bracketColor, shadow);
			x += font.getStringWidth("[");
		}
		g.drawString(font, text, x, 1, color, shadow);
		x += font.getStringWidth(text);
		if (brackets) {
			g.drawString(font, "]", x, 1, bracketColor, shadow);
		}
	}

	// ---- Item Counter ----------------------------------------------------------

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Counted {
		private final ItemStack icon;
		private final String text;

		private Counted(ItemStack icon, String text) {
			this.icon = icon;
			this.text = text;
		}

		public ItemStack icon() {
			return icon;
		}

		public String text() {
			return text;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Counted)) {
				return false;
			}
			Counted other = (Counted) obj;
			return java.util.Objects.equals(this.icon, other.icon)
					&& java.util.Objects.equals(this.text, other.text);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(icon, text);
		}
	}

	private static List<Counted> counted(Minecraft mc) {
		List<Counted> out = new ArrayList<>();
		boolean hideZero = Mods.bool("itemcounter", "hideZero") && !HudElements.editorPreview;
		for (ItemCounter.Entry e : ItemCounter.selected()) {
			int n = mc.thePlayer != null ? ItemCounter.count(mc.thePlayer.inventory, e) : 0;
			if (n == 0 && hideZero) {
				continue;
			}
			out.add(new Counted(e.iconStack(), String.valueOf(n)));
		}
		return out;
	}

	private static final int IC_GAP = 4;   // space between separate (ungrouped) boxes

	private static int cellWidth(Minecraft mc, Counted c) {
		return 18 + mc.fontRendererObj.getStringWidth(c.text()) + 1;
	}

	private static int[] itemCounterSize(Minecraft mc) {
		List<Counted> list = counted(mc);
		if (list.isEmpty()) {
			return new int[]{0, 0};
		}
		boolean vertical = !Mods.mode("itemcounter", "listMode").equals("Horizontal");
		int gap = Mods.bool("itemcounter", "grouped") ? 2 : IC_GAP + 6;
		int w = 0, h = 0;
		for (Counted c : list) {
			int cw = cellWidth(mc, c);
			if (vertical) {
				w = Math.max(w, cw);
				h += 16 + gap;
			} else {
				w += cw + gap;
				h = 16;
			}
		}
		return vertical ? new int[]{w, h - gap} : new int[]{w - gap, h};
	}

	private static void renderItemCounter(Gfx g, Minecraft mc, int w, int h) {
		List<Counted> list = counted(mc);
		if (list.isEmpty()) {
			return;
		}
		boolean grouped = Mods.bool("itemcounter", "grouped");
		boolean vertical = !Mods.mode("itemcounter", "listMode").equals("Horizontal");
		boolean shadow = Mods.bool("itemcounter", "textShadow");
		int color = OriginColorPicker.liveColor("itemcounter", "color");
		int gap = grouped ? 2 : IC_GAP + 6;
		if (grouped) {
			panel(g, "itemcounter", w, h);
		}
		int x = 0, y = 0;
		for (Counted c : list) {
			int cw = vertical ? w : cellWidth(mc, c);
			if (!grouped) {
				Pose p = g.pose();
				p.pushPose();
				p.translate(x, y, 0);
				panel(g, "itemcounter", cw, 16);
				p.popPose();
			}
			g.renderItem(c.icon(), x, y);
			g.drawString(mc.fontRendererObj, c.text(), x + 18, y + 4, color, shadow);
			if (vertical) {
				y += 16 + gap;
			} else {
				x += cw + gap;
			}
		}
	}

	// ---- Direction HUD --------------------------------------------------------
	// A horizontal compass strip. Heading is compass degrees (0 = north,
	// clockwise); Minecraft yaw 0 faces south, so heading = yaw + 180. The strip
	// spans DIR_FOV degrees across its width, centred on where you face.

	private static final int DIR_H = 25;
	private static final double DIR_FOV = 180.0;
	private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

	private static int dirWidth() {
		return (int) Math.max(100, Math.min(300, Mods.num("directionhud", "width")));
	}

	/** Mth.positiveModulo (1.8.9 MathHelper has no double form). */
	private static double positiveModulo(double a, double b) {
		return (a % b + b) % b;
	}

	private static void renderDirection(Gfx g, Minecraft mc, int w, int h) {
		if (mc.thePlayer == null) {
			return;
		}
		if (OriginClientMod.tabListVisible && !Mods.bool("directionhud", "showWithTab") && !HudElements.editorPreview) {
			return;
		}
		panel(g, "directionhud", w, h);
		FontRenderer font = mc.fontRendererObj;
		boolean shadow = Mods.bool("directionhud", "textShadow");
		boolean bold = Mods.bool("directionhud", "boldDirections");
		int dirColor = OriginColorPicker.liveColor("directionhud", "directionColor");
		int markerColor = OriginColorPicker.liveColor("directionhud", "markerColor");
		double heading = positiveModulo(mc.thePlayer.rotationYaw + 180.0, 360.0);
		double ppd = w / DIR_FOV;
		double mid = w / 2.0;

		// minor ticks every 15 degrees, letters every 45
		for (int a = 0; a < 360; a += 15) {
			double dx = MathHelper.wrapAngleTo180_double(a - heading) * ppd;
			if (Math.abs(dx) > mid - 1) {
				continue;
			}
			int alpha = edgeAlpha(dx, mid);
			int x = (int) Math.round(mid + dx);
			if (a % 45 == 0) {
				String label = POINTS[a / 45];
				boolean cardinal = a % 90 == 0;
				int col = withAlpha(dirColor, cardinal ? alpha : alpha * 3 / 4);
				if (((col >>> 24) & 0xFF) < 8) {
					continue;
				}
				String c = bold && cardinal ? EnumChatFormatting.BOLD + label : label;
				g.drawString(font, c, x - font.getStringWidth(c) / 2, 4, col, shadow);
			} else {
				g.fill(x, 9, x + 1, 12, withAlpha(dirColor, alpha / 2));
			}
		}

		// waypoints in this dimension, as coloured dots on the strip
		if (Mods.bool("directionhud", "showWaypoints") && Mods.on("waypoints")) {
			String dim = Waypoints.currentDim();
			for (Waypoints.Waypoint wp : Waypoints.all()) {
				if (!wp.enabled || !dim.equals(wp.dimension)) {
					continue;
				}
				double ddx = wp.x + 0.5 - mc.thePlayer.posX;
				double ddz = wp.z + 0.5 - mc.thePlayer.posZ;
				double bearing = Math.toDegrees(Math.atan2(ddx, -ddz));
				double dx = MathHelper.wrapAngleTo180_double(bearing - heading) * ppd;
				if (Math.abs(dx) > mid - 2) {
					continue;
				}
				int x = (int) Math.round(mid + dx);
				g.fill(x - 2, 13, x + 2, 17, withAlpha(0xFF000000, edgeAlpha(dx, mid) * 3 / 4));
				g.fill(x - 1, 14, x + 1, 16, withAlpha(wp.color, edgeAlpha(dx, mid)));
			}
		}

		int cx = (int) Math.round(mid);
		if (Mods.bool("directionhud", "showMarker")) {
			// a small downward notch at the top edge, pointing at your heading
			g.fill(cx - 2, 0, cx + 3, 1, markerColor);
			g.fill(cx - 1, 1, cx + 2, 2, markerColor);
			g.fill(cx, 2, cx + 1, 3, markerColor);
		}
		if (Mods.bool("directionhud", "showMarkerValue")) {
			String v = String.valueOf((int) Math.round(heading) % 360);
			g.drawString(font, v, cx - font.getStringWidth(v) / 2 + 1, 16, markerColor, shadow);
		}
	}

	/** Labels fade out over the outer 15% of the strip on each side. */
	private static int edgeAlpha(double dx, double mid) {
		double t = (mid - Math.abs(dx)) / Math.max(1.0, mid * 0.15);
		return (int) Math.round(255 * Math.max(0.0, Math.min(1.0, t)));
	}

	private static int withAlpha(int argb, int alpha) {
		int a = ((argb >>> 24) & 0xFF) * Math.max(0, Math.min(255, alpha)) / 255;
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
