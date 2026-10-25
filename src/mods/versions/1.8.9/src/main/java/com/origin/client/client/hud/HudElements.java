package com.origin.client.client.hud;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.gui.Pose;
import com.origin.client.client.mods.ClickStats;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.ArrayList;
import java.util.List;

// Every movable HUD element: its owning mod, default placement, measured
// size, and renderer. One dispatcher draws them all; the HUD editor drags the
// same list. Settings are read LIVE from the Mods registry on every frame —
// flipping any option updates the element instantly, no restart.
//
// Sizing rule (Will): an element's measured box is always big enough for the
// element with EVERYTHING turned on, so the edit-screen outline never
// mismatches the content no matter which options are active.
//
// 1.8.9 port (from 1.16.5): drawn through the Gfx shim over the fixed-function
// GL matrix stack (Pose); the dispatcher runs from HudHooks at
// RenderGameOverlayEvent.Post(ALL), LOWEST priority = the top-most HUD layer,
// after every other mod's overlay (1.16.5's GuiHudMixin RETURN, order 2000).
// Potions are 1.8.9 Potion ids with icons from the inventory sheet; biomes are
// BiomeGenBase (its own display name, no registry key); the FPS counter is the
// public Minecraft.getDebugFPS().
public final class HudElements {
	public static final int TEXT = 0xFFE0E0E0;
	private static final int PANEL = 0x66101010;

	// Set by the HUD editor for its whole lifetime: elements that need worn
	// armor / an active potion to be visible draw sample content instead, and
	// measure/hit-testing see the same preview the render shows.
	public static volatile boolean editorPreview = false;

	// The mod menu sets this while open: the scoreboard sample sits at the right
	// edge and would stick out past the centered menu panel, so it's suppressed
	// there (the menu is in front of everything). The HUD editor leaves it false.
	public static volatile boolean suppressScoreboard = false;

	public interface Renderer {
		void render(Gfx g, Minecraft mc, int w, int h);
	}

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Element {
		private final String id;
		private final String modId;
		private final String label;
		private final HudPos defaults;
		private final java.util.function.Function<Minecraft, int[]> measure;
		private final Renderer renderer;

		public Element(String id, String modId, String label, HudPos defaults, java.util.function.Function<Minecraft, int[]> measure, Renderer renderer) {
			this.id = id;
			this.modId = modId;
			this.label = label;
			this.defaults = defaults;
			this.measure = measure;
			this.renderer = renderer;
		}

		public String id() {
			return id;
		}

		public String modId() {
			return modId;
		}

		public String label() {
			return label;
		}

		public HudPos defaults() {
			return defaults;
		}

		public java.util.function.Function<Minecraft, int[]> measure() {
			return measure;
		}

		public Renderer renderer() {
			return renderer;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Element)) {
				return false;
			}
			Element other = (Element) obj;
			return java.util.Objects.equals(this.id, other.id)
					&& java.util.Objects.equals(this.modId, other.modId)
					&& java.util.Objects.equals(this.label, other.label)
					&& java.util.Objects.equals(this.defaults, other.defaults)
					&& java.util.Objects.equals(this.measure, other.measure)
					&& java.util.Objects.equals(this.renderer, other.renderer);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(id, modId, label, defaults, measure, renderer);
		}

		public HudPos pos() {
			return HudPos.load(id, new HudPos(defaults.anchor, defaults.dx, defaults.dy, defaults.scale));
		}
	}

	public static final List<Element> ALL = new ArrayList<>();

	private static long fpsSampledAt = 0;
	private static int fpsSample = 0;

	// ---- shared option plumbing ----

	/** The mod's own Scale slider (1.0 when the mod has no such option). */
	private static float ms(String modId) {
		double s = Mods.num(modId, "scale");
		return (float) (s <= 0.05 ? 1.0 : s);
	}

	private static boolean chatOpen(Minecraft mc) {
		return mc.currentScreen instanceof GuiChat;
	}

	/** Standard text row backing, gated by the mod's Show Background toggle. Uses
	 *  the mod's OWN Background Color so the setting works identically on every HUD
	 *  (Will 2026-07-21 — this was a hardcoded PANEL, so fps/cps/server ignored it).
	 *  Every mod routed through here now carries a "bgColor" option (see Mods). */
	private static void bg(Gfx g, String modId, int w, int h) {
		if (Mods.bool(modId, "showBackground")) {
			OriginUi.panel(g, -3, -3, w + 6, h + 6, 5, OriginColorPicker.liveColor(modId, "bgColor"), 0);
		}
	}

	private static void bgColored(Gfx g, String modId, String colorKey, int w, int h) {
		bgColored(g, modId, colorKey, null, w, h);
	}

	/** `borderKey` null = no outline. Passing one makes the mod's Border Color
	 *  option real — Coordinates shipped that picker with nothing reading it. */
	private static void bgColored(Gfx g, String modId, String colorKey, String borderKey, int w, int h) {
		if (Mods.bool(modId, "showBackground")) {
			int border = borderKey == null ? 0 : OriginColorPicker.liveColor(modId, borderKey);
			OriginUi.panel(g, -3, -3, w + 6, h + 6, 5, OriginColorPicker.liveColor(modId, colorKey), border);
		}
	}

	// ---- current-content builders ----
	// measure() and render() both go through these, so the box (and the
	// edit-screen border) is always exactly the size of what's shown right now
	// and grows the instant an option adds content.

	private static String fpsText(Minecraft mc) {
		long now = System.currentTimeMillis();
		int opts = (Mods.bool("fps", "reverseOrder") ? 1 : 0) | (Mods.bool("fps", "showBrackets") ? 2 : 0)
				| (Mods.bool("fps", "showLows") ? 4 : 0) | (Mods.bool("fps", "showFrameTime") ? 8 : 0);
		boolean fresh = fpsText == null || opts != fpsTextOpts;
		fpsTextOpts = opts;
		if (now - fpsSampledAt > 250) {
			fpsSampledAt = now;
			// 1.8.9: the frame counter is public static (no accessor needed).
			fpsSample = Minecraft.getDebugFPS();
			fresh = true;
		}
		if (fresh) {
			String txt = Mods.bool("fps", "reverseOrder") ? fpsSample + " FPS" : "FPS: " + fpsSample;
			if (Mods.bool("fps", "showLows")) {
				int low = FrameStats.onePercentLow();
				txt += Mods.bool("fps", "reverseOrder") ? " | " + (low > 0 ? low : "--") + " 1%" : " | 1%: " + (low > 0 ? low : "--");
			}
			if (Mods.bool("fps", "showFrameTime")) {
				txt += String.format(java.util.Locale.ROOT, " | %.1f ms", FrameStats.averageMs(250));
			}
			fpsText = Mods.bool("fps", "showBrackets") ? "[" + txt + "]" : txt;
		}
		return fpsText;
	}

	// Rebuilt with the sample (4x a second) or when an option flips, not every
	// frame: the 1% low sorts the frame ring.
	private static String fpsText;
	private static int fpsTextOpts = -1;

	private static final int GRAPH_H = 24, GRAPH_MIN_W = 90;
	/** The graph's top line: 30 FPS. Slower frames are clipped to it. */
	private static final float GRAPH_MAX_MS = 1000f / 30f;

	/**
	 * Frame-time graph under the FPS text: one bar per recent frame, newest on the
	 * right, height = frame time (top = 33 ms). Red = a spike, gold = a spike that
	 * a garbage-collection pause overlapped. A faint line marks 60 FPS (16.7 ms).
	 * 1.8.9: same bars through Gfx.fill (the GuiGraphics.fill shape over Gui.drawRect).
	 */
	private static void frameGraph(Gfx g, int x, int y, int w, int color) {
		int n = Math.min(w, FrameStats.count());
		int base = (color & 0x00FFFFFF) | 0x99000000;
		int y60 = y + GRAPH_H - Math.round(GRAPH_H * (16.7f / GRAPH_MAX_MS));
		g.fill(x, y60, x + w, y60 + 1, (color & 0x00FFFFFF) | 0x33000000);
		for (int i = 0; i < n; i++) {
			float ms = FrameStats.ms(i);
			int h = Math.max(1, Math.min(GRAPH_H, Math.round(GRAPH_H * ms / GRAPH_MAX_MS)));
			byte k = FrameStats.kind(i);
			int c = k == FrameStats.GC ? 0xFFE9C46A : k == FrameStats.SPIKE ? 0xFFD9675A : base;
			int bx = x + w - 1 - i;
			g.fill(bx, y + GRAPH_H - h, bx + 1, y + GRAPH_H, c);
		}
	}

	// Both strings are asked for twice a frame (measure + render). They are rebuilt only when one of their inputs
	// changed (a click-rate tick, an option flip, a player joining), so a steady frame allocates nothing here.
	private static String cpsCached;
	private static int cpsKeyL = -1, cpsKeyR = -1, cpsKeyOpts = -1;

	private static String cpsText() {
		int left = ClickStats.leftCps();
		boolean right = Mods.bool("cps", "rightClick");
		int rightCps = right ? ClickStats.rightCps() : -1;
		int opts = (right ? 1 : 0) | (Mods.bool("cps", "showText") ? 2 : 0) | (Mods.bool("cps", "reverseText") ? 4 : 0);
		if (cpsCached != null && left == cpsKeyL && rightCps == cpsKeyR && opts == cpsKeyOpts) {
			return cpsCached;
		}
		String value = String.valueOf(left);
		if (right) {
			value += " | " + rightCps;
		}
		cpsCached = (opts & 2) == 0 ? value : (opts & 4) != 0 ? value + " CPS" : "CPS: " + value;
		cpsKeyL = left;
		cpsKeyR = rightCps;
		cpsKeyOpts = opts;
		return cpsCached;
	}

	private static String serverCached, serverKeyIp;
	private static int serverKeyPlayers = -1;

	private static String serverText(Minecraft mc) {
		ServerData server = mc.getCurrentServerData();
		if (server == null) {
			return "Singleplayer";
		}
		int players = mc.getNetHandler() != null ? mc.getNetHandler().getPlayerInfoMap().size() : 0;
		if (serverCached != null && players == serverKeyPlayers && java.util.Objects.equals(server.serverIP, serverKeyIp)) {
			return serverCached;
		}
		serverKeyIp = server.serverIP;
		serverKeyPlayers = players;
		serverCached = server.serverIP + " (" + players + ")";
		return serverCached;
	}

	// The Coordinates box asks for its lines twice a frame (measure, then render), and
	// building them is String.format + a biome lookup + the F3 chunk string. Position
	// only changes per game tick anyway, so the lines are rebuilt at most 20 times a
	// second and shared by both asks (Lunar's HUD refreshes at the same rate).
	private static List<String> coordsCache = new ArrayList<>();
	private static long coordsCacheAt = 0L;

	private static List<String> coordsLinesCached(Minecraft mc) {
		long now = System.nanoTime();
		if (now - coordsCacheAt >= 50_000_000L || now < coordsCacheAt) {
			coordsCache = coordsLines(mc);
			coordsCacheAt = now;
		}
		return coordsCache;
	}

	private static List<String> coordsLines(Minecraft mc) {
		List<String> lines = new ArrayList<>();
		EntityPlayerSP pl = mc.thePlayer;
		if (pl == null) {
			return lines;
		}
		boolean dec = Mods.bool("coords", "decimal");
		BlockPos bp = new BlockPos(pl);
		String fx = dec ? String.format("%.1f", pl.posX) : String.valueOf(bp.getX());
		String fy = dec ? String.format("%.1f", pl.posY) : String.valueOf(bp.getY());
		String fz = dec ? String.format("%.1f", pl.posZ) : String.valueOf(bp.getZ());
		boolean sx = Mods.bool("coords", "x"), sy = Mods.bool("coords", "y"), sz = Mods.bool("coords", "z");
		if (Mods.mode("coords", "listMode").equals("Horizontal")) {
			if (sx || sy || sz) {
				StringBuilder sb = new StringBuilder("XYZ:");
				if (sx) sb.append(' ').append(fx);
				if (sy) sb.append(sx ? " / " : " ").append(fy);
				if (sz) sb.append(sx || sy ? " / " : " ").append(fz);
				lines.add(sb.toString());
			}
		} else {
			if (sx) lines.add("X: " + fx);
			if (sy) lines.add("Y: " + fy);
			if (sz) lines.add("Z: " + fz);
		}
		if (Mods.bool("coords", "renderers")) {
			int c = renderedChunks(mc);
			if (c >= 0) lines.add("C: " + c);
		}
		if (Mods.bool("coords", "direction")) {
			EnumFacing d = pl.getHorizontalFacing();
			String n = d.getName();
			lines.add(String.format("Facing: %s (%.0f°)",
					n.substring(0, 1).toUpperCase() + n.substring(1),
					MathHelper.wrapAngleTo180_float(pl.rotationYaw)));
		}
		if (Mods.bool("coords", "biome") && mc.theWorld != null) {
			lines.add("Biome: " + biomeName(biomeAt(mc, bp)));
		}
		return lines;
	}

	/**
	 * 1.8.9: the rendered-chunk count has no getter (RenderGlobal keeps its private
	 * renderInfos list); the F3 line RenderGlobal.getDebugInfoRenders() is public and
	 * starts "C: <rendered>/<total>" -- OptiFine keeps that format -- so the number is
	 * read from it. -1 = unknown (line dropped).
	 */
	private static int renderedChunks(Minecraft mc) {
		try {
			String s = mc.renderGlobal.getDebugInfoRenders();
			int i = s.indexOf("C: ");
			if (i < 0) {
				return -1;
			}
			int j = i + 3, k = j;
			while (k < s.length() && Character.isDigit(s.charAt(k))) {
				k++;
			}
			return k > j ? Integer.parseInt(s.substring(j, k)) : -1;
		} catch (Throwable t) {
			return -1;
		}
	}

	/** The biome at a block (null = unknown); 1.8.9 World.getBiomeGenForCoords. */
	public static BiomeGenBase biomeAt(Minecraft mc, BlockPos pos) {
		try {
			return mc.theWorld == null ? null : mc.theWorld.getBiomeGenForCoords(pos);
		} catch (Throwable t) {
			return null;
		}
	}

	// Biome display name. 1.8.9 biomes carry their own English display name
	// (BiomeGenBase.biomeName: "Extreme Hills+", "Roofed Forest", ...) and have no
	// registry key or lang key, so the name is shown as the game itself names it
	// (already Title Case, matching Will's 2026-07-21 rule on the other versions).
	// Public: the Minimap's biome line uses this and biomeColor too.
	public static String biomeName(BiomeGenBase biome) {
		if (biome == null || biome.biomeName == null || biome.biomeName.isEmpty()) {
			return "Unknown";
		}
		return biome.biomeName;
	}

	// Intuitive colour per biome family, keyword-matched on the name (lower case,
	// spaces as '_' so the modern id keywords below match). Oceans scale
	// warm→cold (lighter→darker) and deepen for the deep variants.
	public static int biomeColor(BiomeGenBase biome) {
		if (biome == null || biome.biomeName == null) {
			return TEXT;
		}
		String p = biome.biomeName.toLowerCase(java.util.Locale.ROOT).replace(' ', '_').replace("+", "");
		if (p.contains("warm_ocean")) return 0xFF4FB6FF;
		if (p.contains("lukewarm_ocean")) return p.contains("deep") ? 0xFF2A7FD6 : 0xFF3A97E6;
		if (p.contains("frozen_ocean")) return p.contains("deep") ? 0xFF7FB0DD : 0xFF9FCBF0;
		if (p.contains("cold_ocean")) return p.contains("deep") ? 0xFF163E86 : 0xFF1E56A8;
		if (p.contains("ocean")) return p.contains("deep") ? 0xFF15408F : 0xFF2166CC;
		if (p.contains("frozen_river")) return 0xFFCFE6FF;   // 1.8.9 name; checked before "river"
		if (p.contains("river")) return 0xFF3A97E6;
		if (p.contains("swamp") || p.contains("mangrove")) return 0xFF3A6B2A;
		if (p.contains("jungle")) return 0xFF2FA82F;
		if (p.contains("cold_taiga") || p.contains("ice_plains") || p.contains("ice_mountains")) return 0xFFCFE6FF;   // 1.8.9 snowy names
		if (p.contains("forest") || p.contains("taiga") || p.contains("grove") || p.contains("wooded")) return 0xFF3FA34D;
		if (p.contains("cherry")) return 0xFFFF9CC6;
		if (p.contains("savanna")) return 0xFFC7B24A;
		if (p.contains("plains") || p.contains("meadow") || p.contains("lush")) return 0xFF6FC24A;
		if (p.contains("beach")) return 0xFFEAD9A0;
		if (p.contains("desert")) return 0xFFE0C877;
		if (p.contains("badlands") || p.contains("mesa")) return 0xFFD1662B;
		if (p.contains("warped")) return 0xFF1FB6A6;
		if (p.contains("crimson") || p.contains("nether") || p.contains("hell") || p.contains("basalt") || p.contains("soul")) return 0xFFC0392B;
		if (p.contains("frozen") || p.contains("snow") || p.contains("ice")) return 0xFFCFE6FF;
		if (p.contains("end") || p.contains("void") || p.contains("sky")) return 0xFFCBB0FF;
		if (p.contains("mushroom")) return 0xFFC792E0;
		if (p.contains("dripstone")) return 0xFFB07A55;
		if (p.contains("deep_dark")) return 0xFF2A6E7A;
		if (p.contains("stony") || p.contains("stone") || p.contains("windswept") || p.contains("hills") || p.contains("peaks")) return 0xFFB8B8B8;
		return TEXT;
	}

	/** Keystrokes vertical extent {top, bottom} of the currently-shown rows. */
	private static int[] ksBounds() {
		boolean mv = Mods.bool("keystrokes", "showMovement");
		boolean cl = Mods.bool("keystrokes", "showClicks");
		boolean sp = Mods.bool("keystrokes", "showSpace");
		int top = Integer.MAX_VALUE, bottom = 0;
		if (mv) {
			top = Math.min(top, 0);
			bottom = Math.max(bottom, 46);
		}
		if (cl) {
			top = Math.min(top, 48);
			bottom = Math.max(bottom, 62);
		}
		if (sp) {
			int t = (int) Math.max(1, Math.min(10, Mods.num("keystrokes", "spacebarThickness"))) + 4;
			top = Math.min(top, 64);
			bottom = Math.max(bottom, 64 + t);
		}
		if (top == Integer.MAX_VALUE) {
			top = 0;
			bottom = 10;
		}
		return new int[]{top, bottom};
	}

	private static List<ItemStack> armorItems(Minecraft mc) {
		List<ItemStack> items = new ArrayList<>();
		if (mc.thePlayer != null) {
			// 1.8.9: armorInventory[0] is the boots, [3] the helmet; empty slots are null.
			ItemStack[] armor = mc.thePlayer.inventory.armorInventory;
			for (int i = armor.length - 1; i >= 0; i--) {   // helmet first
				if (armor[i] != null) {
					items.add(armor[i]);
				}
			}
			ItemStack held = mc.thePlayer.getCurrentEquippedItem();
			if (held != null) {
				items.add(held);
			}
		}
		if (items.isEmpty() && editorPreview) {
			items = new ArrayList<>(java.util.Arrays.asList(new ItemStack(Items.diamond_helmet), new ItemStack(Items.diamond_chestplate),
					new ItemStack(Items.diamond_leggings), new ItemStack(Items.diamond_boots), new ItemStack(Items.diamond_sword)));
		}
		return items;
	}

	static {
		// ---- FPS ----
		add("fps", "fps", "FPS", new HudPos(0, 6, 6, 1.0), mc -> {
			float s = ms("fps");
			boolean graph = Mods.bool("fps", "showGraph");
			int tw = mc.fontRendererObj.getStringWidth(fpsText(mc)) + 2;
			return new int[]{(int) ((graph ? Math.max(tw, GRAPH_MIN_W) : tw) * s), (int) ((graph ? 11 + 3 + GRAPH_H : 11) * s)};
		}, (g, mc, w, h) -> {
			float s = ms("fps");
			Pose p = g.pose();
			p.pushPose();
			p.scale(s, s, 1f);
			bg(g, "fps", (int) (w / s), (int) (h / s));
			int color = OriginColorPicker.liveColor("fps", "color");
			g.drawString(mc.fontRendererObj, fpsText(mc), 1, 1, color, Mods.bool("fps", "textShadow"));
			if (Mods.bool("fps", "showGraph")) {
				frameGraph(g, 1, 11 + 3, (int) (w / s) - 2, color);
			}
			p.popPose();
		});

		// ---- CPS ----
		add("cps", "cps", "CPS", new HudPos(0, 6, 22, 1.0), mc -> {
			float s = ms("cps");
			return new int[]{(int) ((mc.fontRendererObj.getStringWidth(cpsText()) + 2) * s), (int) (11 * s)};
		}, (g, mc, w, h) -> {
			float s = ms("cps");
			Pose p = g.pose();
			p.pushPose();
			p.scale(s, s, 1f);
			bg(g, "cps", (int) (w / s), (int) (h / s));
			g.drawString(mc.fontRendererObj, cpsText(), 1, 1, OriginColorPicker.liveColor("cps", "color"), Mods.bool("cps", "textShadow"));
			p.popPose();
		});

		// ---- Coordinates ----
		add("coords", "coords", "Coords", new HudPos(0, 6, 38, 1.0), mc -> {
			float s = ms("coords");
			List<String> lines = coordsLinesCached(mc);
			int wMax = 1;
			for (String l : lines) {
				wMax = Math.max(wMax, mc.fontRendererObj.getStringWidth(l));
			}
			int n = Math.max(1, lines.size());
			return new int[]{(int) ((wMax + 2) * s), (int) ((n * 10 + 2) * s)};
		}, (g, mc, w, h) -> {
			if (mc.thePlayer == null) {
				return;
			}
			if (chatOpen(mc) && !Mods.bool("coords", "showWhileTyping")) {
				return;
			}
			float s = ms("coords");
			Pose p = g.pose();
			p.pushPose();
			p.scale(s, s, 1f);
			bgColored(g, "coords", "bgColor", "borderColor", (int) (w / s), (int) (h / s));
			boolean shadow = Mods.bool("coords", "textShadow");
			BiomeGenBase biomeH = Mods.bool("coords", "biome") ? biomeAt(mc, new BlockPos(mc.thePlayer)) : null;
			int y = 1;
			for (String line : coordsLinesCached(mc)) {
				int col = (biomeH != null && line.startsWith("Biome:")) ? biomeColor(biomeH) : TEXT;
				g.drawString(mc.fontRendererObj, line, 1, y, col, shadow);
				y += 10;
			}
			p.popPose();
		});

		// ---- Key Strokes ----
		add("keystrokes", "keystrokes", "Keystrokes", new HudPos(3, 6, -40, 1.0), mc -> {
			float s = ks();
			int[] b = ksBounds();
			return new int[]{(int) (70 * s), (int) ((b[1] - b[0]) * s)};
		}, HudElements::renderKeystrokes);

		// ---- Potion Effects ----
		add("potionhud", "potionhud", "Potions", new HudPos(2, -6, 6, 1.0), mc -> {
			// width grows to the widest FULL name+time so nothing is ellipsised;
			// right-anchored, so it just extends further left to fit.
			List<PotionRow> rows = potionRows(mc);
			int wMax = 60;
			for (PotionRow row : rows) {
				wMax = Math.max(wMax, 22 + rowTextWidth(mc, row));
			}
			return new int[]{wMax, Math.max(1, rows.size()) * 20};
		}, (g, mc, w, h) -> {
			if (mc.thePlayer == null) {
				return;
			}
			if (chatOpen(mc) && !Mods.bool("potionhud", "showWhileTyping")) {
				return;
			}
			List<PotionRow> rows = potionRows(mc);
			if (rows.isEmpty()) {
				return; // nothing active in-game: no box, no background at all
			}
			// background hugs the actual rows — one effect gets a one-row
			// backing, more effects grow it; never a big empty gray area
			if (Mods.bool("potionhud", "showBackground")) {
				int wAct = 0;
				for (PotionRow row : rows) {
					wAct = Math.max(wAct, 22 + rowTextWidth(mc, row));
				}
				OriginUi.panel(g, -3, -3, wAct + 6, rows.size() * 20 + 4, 5,
						OriginColorPicker.liveColor("potionhud", "bgColor"), 0);
			}
			int y = 0;
			for (PotionRow row : rows) {
				drawEffectRow(g, mc, y, row.effect, row.duration, row.infinite);
				y += 20;
			}
		});

		// ---- Armor Status ----
		add("armorhud", "armorhud", "Armor", new HudPos(6, 6, -24, 1.0), mc -> {
			List<ItemStack> items = armorItems(mc);
			int n = Math.max(1, items.size());
			boolean vertical = Mods.mode("armorhud", "listMode").equals("Vertical");
			boolean dur = !Mods.mode("armorhud", "durabilityPos").equals("Hidden");
			return vertical ? new int[]{19 + (dur ? 34 : 0), n * 19} : new int[]{n * 19, 17 + (dur ? 10 : 0)};
		}, HudElements::renderArmor);

		// ---- Server Address ----
		add("serveraddress", "serveraddress", "Server IP", new HudPos(2, -6, 6, 1.0), mc -> {
			float s = ms("serveraddress");
			int iconW = Mods.bool("serveraddress", "serverIcon") ? 15 : 0;
			return new int[]{(int) ((iconW + mc.fontRendererObj.getStringWidth(serverText(mc)) + 2) * s), (int) (13 * s)};
		}, (g, mc, w, h) -> {
			float s = ms("serveraddress");
			Pose p = g.pose();
			p.pushPose();
			p.scale(s, s, 1f);
			bg(g, "serveraddress", (int) (w / s), (int) (h / s));
			boolean shadow = Mods.bool("serveraddress", "textShadow");
			int color = OriginColorPicker.liveColor("serveraddress", "color");
			int x = 0;
			if (Mods.bool("serveraddress", "serverIcon")) {
				OriginUi.icon(g, "serveraddress", 0, 0, 12, color);
				x = 15;
			}
			g.drawString(mc.fontRendererObj, serverText(mc), x, 2, color, shadow);
			p.popPose();
		});

		// ---- Sprint/Sneak state ----
		add("sprintstate", "togglesprint", "Sprint state", new HudPos(6, 6, -6, 1.0), mc -> text("Sprint (Toggled)"),
				(g, mc, w, h) -> {
					if (!Mods.bool("togglesprint", "hud")) {
						return;
					}
					com.origin.client.client.OriginFeatures f = OriginClientMod.FEATURES;
					String s = f.sprintToggledOn ? "Sprint (Toggled)" : f.sneakToggledOn ? "Sneak (Toggled)" : null;
					if (s == null && editorPreview) {
						s = "Sprint (Toggled)";
					}
					if (s != null) {
						g.drawString(mc.fontRendererObj, s, 0, 0, TEXT);
					}
				});

		// ---- Scoreboard (movable + editable) ----
		// A real draggable element: default at the right edge, centred (id "scoreboard",
		// HudPos loaded by scoreboardPos()). In the editor it shows the sample; in-game
		// it draws nothing here (the sidebar draws from GuiIngame.renderScoreboard,
		// repositioned by GuiIngameScoreboardMixin / SidebarHud).
		add("scoreboard", "scoreboard", "Scoreboard", new HudPos(5, -3, 0, 1.0),
				mc -> sampleScoreboardSize(),
				(g, mc, w, h) -> {
					if (editorPreview && !suppressScoreboard) {
						drawSampleScoreboard(g);
					}
				});

		// ---- Locator Bar (Separate/movable mode) ----
		// The draggable placement element for the separate locator bar. In normal
		// gameplay the bar itself is drawn by WaypointHud.renderBars from the vanilla
		// hotbar / XP layer (HudHooks: Post(EXPERIENCE) / Post(HOTBAR)); this element
		// only draws the PREVIEW in the HUD editor / mod menu, and provides the drag
		// target. Measuring 0×0 while off keeps it out of the editor until separate
		// mode is enabled.
		add("locatorbar", "waypoints", "Locator Bar", new HudPos(7, 0, -22, 1.0),
				mc -> locatorBarActive() ? new int[]{182, 5} : new int[]{0, 0},
				(g, mc, w, h) -> {
					if (locatorBarActive() && (editorPreview || mc.currentScreen instanceof HudEditorScreen)) {
						com.origin.client.client.waypoints.WaypointHud.drawBar(g, mc, 0, 0, w, true);
					}
				});

		// ---- Lunar-parity readouts (Clock, Ping, Direction HUD, ...) ----
		Readouts.register();
	}

	private static boolean locatorBarActive() {
		return Mods.bool("waypoints", "locatorBar") && Mods.bool("waypoints", "separateBar");
	}

	// ---- Lunar parity, batch 2 (2026-10-11): Boss Bar, Titles, Cooldowns ----
	// A second static block, after the first, so ALL already holds the original
	// elements. Boss Bar and Titles follow the Scoreboard split: the element is the
	// drag target + editor sample, and their hooks draw the real thing from
	// vanilla's own layer at this element's position. Cooldowns is a plain element
	// (1.8.9 has no item cooldowns: it always measures empty -- see CooldownsHud).
	static {
		add("bossbar", "bossbar", "Boss Bar", BossBarHud.defaultPos(),
				mc -> BossBarHud.size(),
				(g, mc, w, h) -> {
					if (editorPreview) {
						BossBarHud.renderSample(g);
					}
				});
		add("titles", "titles", "Titles", TitlesHud.defaultPos(),
				mc -> TitlesHud.editorSize(),
				(g, mc, w, h) -> {
					if (editorPreview && Mods.bool("titles", "showInHudEditor")) {
						TitlesHud.renderSample(g);
					}
				});
		add("cooldowns", "cooldowns", "Cooldowns", new HudPos(7, 0, -62, 1.0),
				CooldownsHud::size, CooldownsHud::render);
	}

	// ---- Lunar parity, batch 4a (2026-10-11): Minimap ----
	// Its own static block so it merges cleanly; the map, its terrain scan and its
	// full-screen view live in the minimap package.
	static {
		add("minimap", "minimap", "Minimap", com.origin.client.client.minimap.MinimapHud.defaultPos(),
				com.origin.client.client.minimap.MinimapHud::size, com.origin.client.client.minimap.MinimapHud::render);
	}

	// ---- Lunar parity: Hypixel Bed Wars (2026-10-11) ----
	// Its own static block so it merges cleanly: Resource Counter + Teammates.
	static {
		BedwarsHud.register();
	}

	// ---- Origin originals: Portal Calculator (2026-10-12) ----
	static {
		PortalCalcHud.register();
	}

	// ---- Origin originals: Friends & Party (2026-10-12) ----
	// The Party HUD (docs/SOCIAL.md C): name, health, distance, direction per
	// member in a LAN world, names + online status elsewhere. Left edge under
	// Combo / Reach; shown in the editor only while you're in a party.
	static {
		add("party", com.origin.client.client.social.Social.MOD, "Party", new HudPos(3, 6, 56, 1.0),
				com.origin.client.client.social.PartyWorld::hudSize, com.origin.client.client.social.PartyWorld::hudRender);
	}

	// Package-private so Readouts (the Lunar-parity readouts) registers into the same list.
	static void add(String id, String modId, String label, HudPos def,
							java.util.function.Function<Minecraft, int[]> measure, Renderer r) {
		ALL.add(new Element(id, modId, label, def, measure, r));
	}

	private static int[] text(String sample) {
		return new int[]{Minecraft.getMinecraft().fontRendererObj.getStringWidth(sample), 10};
	}

	private static String trim(Minecraft mc, String s, int px) {
		FontRenderer f = mc.fontRendererObj;
		return f.getStringWidth(s) <= px ? s : f.trimStringToWidth(s, px - 6) + "…";
	}

	// ---- potions ----
	// 1.8.9: effects are PotionEffect + an int potion id (Potion.potionTypes[id]);
	// "infinite" is the /effect-max flag (getIsPotionDurationMax, shown "**:**" by
	// vanilla), the closest 1.8.9 has to 1.19.4+'s infinite effects.

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class PotionRow {
		private final Potion effect;
		private final int duration;
		private final boolean infinite;

		private PotionRow(Potion effect, int duration, boolean infinite) {
			this.effect = effect;
			this.duration = duration;
			this.infinite = infinite;
		}

		public Potion effect() {
			return effect;
		}

		public int duration() {
			return duration;
		}

		public boolean infinite() {
			return infinite;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof PotionRow)) {
				return false;
			}
			PotionRow other = (PotionRow) obj;
			return java.util.Objects.equals(this.effect, other.effect)
					&& this.duration == other.duration
					&& this.infinite == other.infinite;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(effect, duration, infinite);
		}
	}

	private static String effectName(Potion p) {
		return I18n.format(p.getName());
	}

	private static int rowTextWidth(Minecraft mc, PotionRow row) {
		String name = effectName(row.effect);
		int secs = row.duration / 20;
		String time = row.infinite ? "∞" : secs / 60 + ":" + String.format("%02d", secs % 60);
		return mc.fontRendererObj.getStringWidth(name + " " + time);
	}

	private static List<PotionRow> potionRows(Minecraft mc) {
		List<PotionRow> rows = new ArrayList<>();
		if (mc.thePlayer != null) {
			boolean excludePerm = Mods.bool("potionhud", "excludePermanent");
			for (PotionEffect e : mc.thePlayer.getActivePotionEffects()) {
				int id = e.getPotionID();
				Potion p = id >= 0 && id < Potion.potionTypes.length ? Potion.potionTypes[id] : null;
				if (p == null) {
					continue;
				}
				boolean infinite = e.getIsPotionDurationMax();
				if (infinite && excludePerm) {
					continue;
				}
				rows.add(new PotionRow(p, e.getDuration(), infinite));
			}
		}
		if (rows.isEmpty() && editorPreview) {
			rows.add(new PotionRow(Potion.moveSpeed, 83 * 20, false));
			rows.add(new PotionRow(Potion.damageBoost, 45 * 20, false));
		}
		return rows;
	}

	// 1.8.9 has no mob-effect atlas: the status icons are 18x18 cells on the
	// inventory sheet (GuiContainer.inventoryBackground is protected -- same file),
	// 8 per row from v=198, as InventoryEffectRenderer draws them.
	private static final ResourceLocation INVENTORY = new ResourceLocation("textures/gui/container/inventory.png");

	private static void drawEffectRow(Gfx g, Minecraft mc, int y, Potion effect, int duration, boolean infinite) {
		int secs = duration / 20;
		// Blink: within the threshold, the row pulses like vanilla's expiring icons
		if (!infinite && Mods.bool("potionhud", "blink")
				&& secs < Mods.num("potionhud", "blinkDuration")
				&& (System.currentTimeMillis() / 400) % 2 == 0) {
			return;
		}
		try {
			if (!effect.hasStatusIcon()) {
				throw new IllegalStateException("no icon");
			}
			int icon = effect.getStatusIconIndex();
			Gfx.defaultBlend();
			g.blit(INVENTORY, 0, y, icon % 8 * 18, 198 + icon / 8 * 18, 18, 18);
			net.minecraft.client.renderer.GlStateManager.disableBlend();
		} catch (Throwable t) {
			g.fill(2, y + 4, 12, y + 14, 0xFF000000 | effect.getLiquidColor());
		}
		FontRenderer font = mc.fontRendererObj;
		boolean shadow = Mods.bool("potionhud", "textShadow");
		boolean minimal = Mods.bool("potionhud", "minimal");
		boolean showName = Mods.bool("potionhud", "effectName") && !minimal;
		String name = effectName(effect);
		if (Mods.bool("potionhud", "uppercase")) {
			name = name.toUpperCase(java.util.Locale.ROOT);
		}
		String time = infinite ? "∞"
				: Mods.bool("potionhud", "formattedDurations")
				? secs / 60 + ":" + String.format("%02d", secs % 60)
				: secs + "s";
		int nameColor = Mods.bool("potionhud", "colorByEffect")
				? 0xFF000000 | effect.getLiquidColor()
				: OriginColorPicker.liveColor("potionhud", "textColor");
		int timeColor = OriginColorPicker.liveColor("potionhud", "durationColor");
		int x = 22;
		if (!showName) {
			g.drawString(font, time, x, y + 5, timeColor, shadow);
			return;
		}
		String nm = name;   // full name, never trimmed (the box grows to fit)
		if (Mods.bool("potionhud", "reversedText")) {
			g.drawString(font, time, x, y + 5, timeColor, shadow);
			g.drawString(font, nm, x + font.getStringWidth(time + " "), y + 5, nameColor, shadow);
		} else {
			g.drawString(font, nm, x, y + 5, nameColor, shadow);
			g.drawString(font, time, x + font.getStringWidth(nm + " "), y + 5, timeColor, shadow);
		}
	}

	// ---- armor ----

	private static void renderArmor(Gfx g, Minecraft mc, int w, int h) {
		if (mc.thePlayer == null) {
			return;
		}
		List<ItemStack> items = armorItems(mc);
		if (items.isEmpty()) {
			return; // no armor / no held item in-game: draw nothing at all
		}
		FontRenderer font = mc.fontRendererObj;
		boolean vertical = Mods.mode("armorhud", "listMode").equals("Vertical");
		String durPos = Mods.mode("armorhud", "durabilityPos");
		boolean shadow = Mods.bool("armorhud", "textShadow");
		int textColor = OriginColorPicker.liveColor("armorhud", "textColor");
		if (Mods.bool("armorhud", "showBackground")) {
			// background hugs however many pieces are actually worn, growing
			// piece by piece — never the full-loadout gray slab
			boolean dur = !durPos.equals("Hidden");
			int wAct = vertical ? 19 + (dur ? 30 : 0) : items.size() * 19;
			int hAct = vertical ? items.size() * 19 : 17 + (dur ? 10 : 0);
			OriginUi.panel(g, -3, -3, wAct + 6, hAct + 6, 5, OriginColorPicker.liveColor("armorhud", "bgColor"), 0);
		}

		int x = 0, y = 0;
		for (ItemStack stack : items) {
			g.renderItem(stack, x, y);
			if (Mods.bool("armorhud", "itemCount")) {
				g.renderItemDecorations(font, stack, x, y);
			}
			if (!durPos.equals("Hidden") && stack.isItemStackDamageable()) {
				int remaining = stack.getMaxDamage() - stack.getItemDamage();
				String txt = Mods.mode("armorhud", "damageDisplay").equals("Percent")
						? (int) Math.round(remaining * 100.0 / stack.getMaxDamage()) + "%"
						: String.valueOf(remaining);
				// Left/Right placements only make sense stacked vertically; in
				// the horizontal row they'd overflow the box, so fall to Below.
				String pos2 = vertical ? durPos : "Below";
				int tx;
				switch (pos2) {
					case "Left": tx = x - font.getStringWidth(txt) - 2; break;
					case "Below": tx = x + (16 - font.getStringWidth(txt)) / 2; break;
					default: tx = x + 19; break; // Right
				}
				int ty = pos2.equals("Below") ? y + 17 : y + 5;
				// Damage Color: below the threshold (Percent <25% remaining, or
				// Value <50 durability left) the durability text switches colour.
				boolean armorLow = Mods.mode("armorhud", "damageThreshold").equals("Value")
						? remaining <= 50 : remaining * 100.0 / stack.getMaxDamage() <= 25.0;
				g.drawString(font, txt, tx, ty,
						armorLow ? OriginColorPicker.liveColor("armorhud", "damageColor") : textColor, shadow);
			}
			if (vertical) {
				y += 19;
			} else {
				x += 19;
			}
		}
	}

	// ---- keystrokes ----

	/** Combined size factor: the mod's Scale slider x its Box Size slider. */
	private static float ks() {
		double s = Mods.num("keystrokes", "scale");
		double b = Mods.num("keystrokes", "boxSize");
		return (float) ((s <= 0.05 ? 1 : s) * (b <= 0.05 ? 1 : b));
	}

	private static void renderKeystrokes(Gfx g, Minecraft mc, int w, int h) {
		float s = ks();
		Pose p = g.pose();
		p.pushPose();
		p.scale(s, s, 1f);
		p.translate(0, -ksBounds()[0], 0); // hug the topmost shown row
		GameSettings o = mc.gameSettings;
		boolean movement = Mods.bool("keystrokes", "showMovement");
		boolean arrows = Mods.bool("keystrokes", "arrows");
		if (movement) {
			key(g, mc, 24, 0, 22, 22, arrows ? "↑" : "W", o.keyBindForward.isKeyDown());
			key(g, mc, 0, 24, 22, 22, arrows ? "←" : "A", o.keyBindLeft.isKeyDown());
			key(g, mc, 24, 24, 22, 22, arrows ? "↓" : "S", o.keyBindBack.isKeyDown());
			key(g, mc, 48, 24, 22, 22, arrows ? "→" : "D", o.keyBindRight.isKeyDown());
		}
		if (Mods.bool("keystrokes", "showClicks")) {
			key(g, mc, 0, 48, 34, 14, "LMB", ClickStats.leftDown);
			key(g, mc, 36, 48, 34, 14, "RMB", ClickStats.rightDown);
		}
		if (Mods.bool("keystrokes", "showSpace")) {
			int thick = (int) Math.max(1, Math.min(10, Mods.num("keystrokes", "spacebarThickness"))) + 4;
			key(g, mc, 0, 64, 70, thick, "", o.keyBindJump.isKeyDown());
		}
		p.popPose();
	}

	// The animation id of each key ("ks:" + label + x) was concatenated per key per frame; the nine keys sit at fixed
	// spots, so the id string is built once per (spot, label). Same id text as before, so OriginUi.anim state is shared.
	private static final java.util.HashMap<Integer, String[]> KS_KEYS = new java.util.HashMap<>();

	private static String ksAnimKey(String label, int x, int y) {
		int spot = (x << 12) ^ y;
		String[] e = KS_KEYS.get(spot);
		if (e == null || !e[0].equals(label)) {
			e = new String[]{label, "ks:" + label + x};
			KS_KEYS.put(spot, e);
		}
		return e[1];
	}

	private static void key(Gfx g, Minecraft mc, int x, int y, int w, int h, String label, boolean down) {
		// Key Fade Delay drives the press/release fade between the two color sets
		double fade = Math.max(50, Mods.num("keystrokes", "keyFadeDelay"));
		float k = OriginUi.anim(ksAnimKey(label, x, y), down, fade);
		int bgUp = OriginColorPicker.liveColor("keystrokes", "bgColor");
		int bgDown = OriginColorPicker.liveColor("keystrokes", "bgColorPressed");
		int fill = com.origin.client.client.theme.OriginTheme.lerpColor(bgUp, bgDown, k);
		g.fill(x, y, x + w, y + h, fill);
		if (Mods.bool("keystrokes", "border")) {
			int t = (int) Math.max(1, Math.min(4, Math.round(Mods.num("keystrokes", "borderThickness"))));
			int bc = OriginColorPicker.liveColor("keystrokes", "borderColor");
			// top/bottom span full width; left/right fill only the gap between them
			// so corner pixels are never drawn twice (which made corners darker
			// than the straight edges at higher opacity).
			g.fill(x, y, x + w, y + t, bc);
			g.fill(x, y + h - t, x + w, y + h, bc);
			g.fill(x, y + t, x + t, y + h - t, bc);
			g.fill(x + w - t, y + t, x + w, y + h - t, bc);
		}
		if (!label.isEmpty()) {
			int up = OriginColorPicker.liveColor("keystrokes", "color");
			int dn = OriginColorPicker.liveColor("keystrokes", "textColorPressed");
			int tc = com.origin.client.client.theme.OriginTheme.lerpColor(up, dn, k);
			int tw = mc.fontRendererObj.getStringWidth(label);
			g.drawString(mc.fontRendererObj, label, x + (w - tw) / 2, y + (h - 8) / 2, tc, Mods.bool("keystrokes", "textShadow"));
		}
	}

	/** Per-module rounded backing at the element's own opacity (0 = none). */
	public static void drawBacking(com.origin.client.client.gui.Gfx g, int x, int y, int w, int h, double bg) {
		if (bg <= 0.01) {
			return;
		}
		int a = (int) (bg * 255);
		OriginUi.panel(g, x - 4, y - 4, w + 8, h + 8, 6, (a << 24) | 0x0E0E0E, 0);
	}

	/** Draws just the Potion element over an open inventory/container screen
	 *  (the container's dimmed backdrop covers the normal HUD pass, so "Show In
	 *  Inventory" needs its own draw on top -- HudHooks, DrawScreenEvent.Post). */
	public static void renderInInventory(Gfx g) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || !Mods.on("potionhud")) {
			return;
		}
		for (Element e : ALL) {
			if (!e.modId().equals("potionhud")) {
				continue;
			}
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = pos.x(g.guiWidth(), w), y = pos.y(g.guiHeight(), h);
			Pose p = g.pose();
			int depth = p.depth();
			p.pushPose();
			p.translate(x, y, 0);
			p.scale((float) pos.scale, (float) pos.scale, 1f);
			try {
				e.renderer().render(g, mc, size[0], size[1]);
			} catch (Throwable ignored) {
			}
			popTo(p, depth);
		}
	}

	// The Scoreboard is a real draggable HUD element ("scoreboard" in ALL). In the
	// editor/menu it draws this SAMPLE at its HudPos; in-game it draws NOTHING here —
	// the real sidebar renders, repositioned to the same HudPos + scale by
	// SidebarHud. So dragging the sample moves the real scoreboard.
	private static final String SB_TITLE = "Origin Network";
	private static final String[][] SB_ROWS = {
			{"Rank: ", "VIP"}, {"Coins: ", "1,234"}, {"Wins: ", "42"},
			{"Map: ", "Skylands"}, {"Players: ", "12/16"}};

	public static int[] sampleScoreboardSize() {
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		boolean hideNums = Mods.bool("scoreboard", "hideNumbers");
		int w = font.getStringWidth(SB_TITLE);
		for (String[] r : SB_ROWS) {
			w = Math.max(w, font.getStringWidth(r[0]) + (hideNums ? 0 : font.getStringWidth(r[1]) + 10));
		}
		int lineH = font.FONT_HEIGHT + 1;
		return new int[]{w + 6, (SB_ROWS.length + 1) * lineH + 3};
	}

	/** The scoreboard element's placement — shared by the editor element AND the
	 *  sidebar draw that repositions the real board. Default: right edge (inset 3), centred. */
	public static HudPos scoreboardPos() {
		return HudPos.load("scoreboard", new HudPos(5, -3, 0, 1.0));
	}

	// Draws the sample scoreboard at LOCAL (0,0) — the caller has already translated
	// and scaled to the element's position.
	private static void drawSampleScoreboard(Gfx g) {
		if (Mods.bool("scoreboard", "hideScoreboard")) {
			return;
		}
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		boolean hideNums = Mods.bool("scoreboard", "hideNumbers");
		boolean shadow = Mods.bool("scoreboard", "textShadow");
		int headerColor = OriginColorPicker.liveColor("scoreboard", "headerColor");
		int bg = OriginColorPicker.liveColor("scoreboard", "bgColor");
		int[] sz = sampleScoreboardSize();
		int boxW = sz[0], contentH = sz[1];
		int lineH = font.FONT_HEIGHT + 1;
		OriginUi.panel(g, 0, 0, boxW, contentH, 0, bg, 0);
		if (Mods.bool("scoreboard", "border")) {
			int bt = (int) Math.max(1, Math.min(4, Math.round(Mods.num("scoreboard", "borderThickness"))));
			int bc = OriginColorPicker.liveColor("scoreboard", "borderColor");
			g.fill(0, 0, boxW, bt, bc);
			g.fill(0, contentH - bt, boxW, contentH, bc);
			g.fill(0, 0, bt, contentH, bc);
			g.fill(boxW - bt, 0, boxW, contentH, bc);
		}
		int ty = 2;
		g.drawString(font, SB_TITLE, (boxW - font.getStringWidth(SB_TITLE)) / 2, ty, headerColor, shadow);
		ty += lineH + 1;
		for (String[] r : SB_ROWS) {
			g.drawString(font, r[0], 3, ty, TEXT, shadow);
			if (!hideNums) {
				g.drawString(font, r[1], boxW - 3 - font.getStringWidth(r[1]), ty, 0xFFE05555, shadow);
			}
			ty += lineH;
		}
	}

	/** Main in-game dispatcher: draws every enabled element at its anchored,
	 *  scaled position. Skipped entirely while the HUD editor is open (the
	 *  editor draws its own draggable versions), with the HUD hidden (F1), and
	 *  -- 1.8.9 -- while the F3 debug screen is up: 1.8.9's debug text fills both
	 *  top corners where the default readouts sit, so they hide together. */
	public static void renderAll(Gfx g) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.gameSettings.hideGUI || mc.gameSettings.showDebugInfo
				|| mc.currentScreen instanceof HudEditorScreen) {
			return;
		}
		if (FrameWatch.idle()) {
			return;   // window unfocused (Limit Unfocused FPS): skip Origin's HUD, nobody is looking
		}
		int sw = g.guiWidth(), sh = g.guiHeight();
		for (int ei = 0; ei < ALL.size(); ei++) {
			Element e = ALL.get(ei);
			if (!Mods.on(e.modId())) {
				continue;
			}
			int prof = -1;
			if (com.origin.client.client.BenchProf.ON) {
				prof = com.origin.client.client.BenchProf.hudElement(ei, e.modId());
				com.origin.client.client.BenchProf.begin(prof);
			}
			HudPos pos = e.pos();
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_HUD_MEASURE);
			}
			int[] size = e.measure().apply(mc);
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_HUD_MEASURE);
			}
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = pos.x(sw, w), y = pos.y(sh, h);
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_HUD_BACKING);
			}
			// Fills inside one element go out as one draw (Gfx batching; draw order is kept by flush-on-
			// anything-else). The minimap is left out: it flips raw GL state (depth) and draws its own
			// geometry between its Gfx calls, which the batch cannot see.
			boolean batch = !"minimap".equals(e.modId());
			if (batch) {
				Gfx.batchBegin();
			}
			Pose p = g.pose();
			int depth = p.depth();
			try {
				drawBacking(g, (int) x, (int) y, (int) w, (int) h, pos.bg);
				if (com.origin.client.client.BenchProf.ON) {
					com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_HUD_BACKING);
					com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_HUD_RENDER);
				}
				p.pushPose();
				p.translate(x, y, 0);
				p.scale((float) pos.scale, (float) pos.scale, 1f);
				try {
					e.renderer().render(g, mc, size[0], size[1]);
				} catch (Throwable t) {
					// One bad element must never take the HUD down.
				}
				popTo(p, depth);
			} finally {
				if (batch) {
					Gfx.batchEnd();
				}
			}
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_HUD_RENDER);
				com.origin.client.client.BenchProf.end(prof);
			}
		}
		// Party markers + pings (Friends & Party): the same screen-projected overlay.
		try {
			com.origin.client.client.social.PartyWorld.renderMarkers(g);
		} catch (Throwable t) {
			// never takes the HUD down
		}
		// Waypoint icon/name/distance overlay — screen-projected so it's always
		// visible (through blocks) and untouched by world lighting/shaders.
		try {
			com.origin.client.client.waypoints.WaypointHud.render(g);
		} catch (Throwable t) {
			// must never take the HUD down — but log the FIRST failure so a broken
			// overlay is visible in the log instead of silently absent on screen
			if (!waypointHudWarned) {
				waypointHudWarned = true;
				com.origin.client.OriginClient.LOGGER.warn("Origin: waypoint HUD overlay failed", t);
			}
		}
	}

	private static boolean waypointHudWarned = false;

	/** 1.8.9: the pose IS the GL matrix stack, shared by every element; an element
	 *  that threw between its own push and pop must not leave a level behind for the
	 *  next element (or vanilla) -- unwind to the depth from before the element. */
	public static void popTo(Pose p, int depth) {
		while (p.depth() > depth) {
			p.popPose();
		}
	}
}
