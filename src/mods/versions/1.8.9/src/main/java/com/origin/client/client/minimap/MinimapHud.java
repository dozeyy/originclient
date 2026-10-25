package com.origin.client.client.minimap;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.Pose;
import com.origin.client.client.hud.HudElements;
import com.origin.client.client.hud.HudPos;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.util.KeyCodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimap (Lunar parity, batch 4a): the HUD element, its per-tick state (zoom keys,
 * the full-screen map key, automatic cave mode) and the option plumbing.
 *
 * Lunar's Minimap is Xaero's Minimap underneath, so it works the same way: a square
 * or round map of the loaded world around you, north-up or turning with you, with
 * entity dots, your waypoints, a compass and a coordinates / biome / clock readout
 * underneath. The terrain itself is built incrementally by {@link MinimapData}; this
 * class only decides what to show and where.
 *
 * Sizes: one Map Width / Map Height step is 12 GUI px (Lunar's default 8 = 96 px),
 * and Map Zoom is GUI px per block (1x = one block per GUI pixel, crisp at every GUI
 * scale). The mod's own Scale slider multiplies the whole element, on top of the HUD
 * editor's drag-to-resize, like Cooldowns.
 *
 * 1.8.9: keys are polled with KeyCodes.isDown (LWJGL2 codes from Mods.keyCode);
 * "singleplayer" is Minecraft.isSingleplayer (the integrated server is running);
 * the roof test reads the world heightmap (World.getHeight) and sky light; the
 * partial tick comes from {@link #partial}, set every frame by HudHooks
 * (RenderTickEvent -- 1.8.9 has no public frame-time getter); the clock reads
 * WorldInfo (the save's time, which Time Changer never touches).
 */
public final class MinimapHud {
	static final String MOD = "minimap";
	private static final int UNIT_PX = 12;
	/** Zoom In / Zoom Out step through these (Xaero-style fixed levels). */
	private static final double[] ZOOM_LEVELS = {0.5, 0.75, 1, 1.5, 2, 3, 4};

	// per-tick state (client thread)
	private static boolean zoomInWasDown = false, zoomOutWasDown = false, mapKeyWasDown = false;
	private static boolean caveActive = false;
	private static int caveVotes = 0;

	private MinimapHud() {
	}

	/** Default placement: top-right corner, where Lunar and Xaero put it. */
	public static HudPos defaultPos() {
		return new HudPos(2, -6, 6, 1.0);
	}

	/**
	 * Singleplayer only (Will, 2026-10-12): on any server or Realm the map is
	 * neither drawn nor openable. A LAN world you host yourself still counts as
	 * singleplayer -- it runs on this PC's own integrated server.
	 */
	public static boolean allowed(Minecraft mc) {
		return mc.isSingleplayer();
	}

	/** The current frame's partial tick (HudHooks, RenderTickEvent START). */
	public static volatile float partial = 1f;

	// ---- tick: keys + automatic cave mode ----

	public static void tick(Minecraft mc) {
		if (mc.theWorld == null) {
			MinimapData.leftWorld();
		}
		if (!Mods.on(MOD) || !allowed(mc)) {
			zoomInWasDown = zoomOutWasDown = mapKeyWasDown = false;
			return;
		}
		boolean free = mc.currentScreen == null;
		boolean in = free && KeyCodes.isDown(Mods.keyCode(MOD, "zoomInKey"));
		boolean out = free && KeyCodes.isDown(Mods.keyCode(MOD, "zoomOutKey"));
		// Tracked even while a screen is open: the full map closes on this same key, and
		// the still-held key must not reopen it on the next tick.
		boolean map = KeyCodes.isDown(Mods.keyCode(MOD, "openMapKey"));
		if (in && !zoomInWasDown) {
			stepZoom(mc, +1);
		}
		if (out && !zoomOutWasDown) {
			stepZoom(mc, -1);
		}
		if (free && map && !mapKeyWasDown && mc.thePlayer != null) {
			mc.displayGuiScreen(new MinimapScreen());
		}
		zoomInWasDown = in;
		zoomOutWasDown = out;
		mapKeyWasDown = map;
		updateCaveMode(mc);
	}

	private static void stepZoom(Minecraft mc, int dir) {
		double z = Mods.num(MOD, "zoom");
		double next = z;
		if (dir > 0) {
			for (double l : ZOOM_LEVELS) {
				if (l > z + 1e-6) {
					next = l;
					break;
				}
			}
		} else {
			for (int i = ZOOM_LEVELS.length - 1; i >= 0; i--) {
				if (ZOOM_LEVELS[i] < z - 1e-6) {
					next = ZOOM_LEVELS[i];
					break;
				}
			}
		}
		if (next != z) {
			Mods.set(MOD, "zoom", next);
		}
		if (mc.thePlayer != null && mc.ingameGUI != null) {
			// 1.8.9's action bar (displayClientMessage(text, true) on later versions)
			mc.ingameGUI.setRecordPlaying(new ChatComponentText("Minimap zoom: " + fmtZoom(next) + "x"), false);
		}
	}

	private static String fmtZoom(double z) {
		return z == Math.floor(z) ? String.valueOf((int) z) : String.valueOf(z);
	}

	/**
	 * Auto cave mode (Xaero's rule): on in dimensions with a roof (the Nether), and
	 * whenever there's a roof over your head that also blocks the sky -- so standing
	 * under a tree (leaves only dim the sky light a little) never flips it. A change
	 * must hold for 15 ticks before it applies, so walking under a ledge doesn't flash
	 * the whole map between modes.
	 */
	private static void updateCaveMode(Minecraft mc) {
		String mode = Mods.mode(MOD, "caveMode");
		if (mc.theWorld == null || mc.thePlayer == null || mode.equals("Off")) {
			caveActive = false;
			caveVotes = 0;
			return;
		}
		if (mode.equals("Always")) {
			caveActive = true;
			caveVotes = 0;
			return;
		}
		boolean want;
		if (mc.theWorld.provider.getHasNoSky()) {   // 1.8.9 NAME UNVERIFIED (the Nether's roof)
			want = true;
		} else {
			BlockPos head = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
			int surface = mc.theWorld.getHeight(head).getY();
			want = surface > head.getY() + 1 && mc.theWorld.getLightFor(EnumSkyBlock.SKY, head) < 8;
		}
		if (want == caveActive) {
			caveVotes = 0;
		} else if (++caveVotes >= 15) {
			caveActive = want;
			caveVotes = 0;
		}
	}

	/** The look the terrain scan bakes in, from the current options. */
	static MinimapData.Look look(Minecraft mc) {
		int top = mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posY) + 2 : 0;
		return new MinimapData.Look(caveActive, top,
				Mods.bool(MOD, "lighting"), Mods.bool(MOD, "terrainShading"), Mods.bool(MOD, "biomeColors"),
				Mods.bool(MOD, "chunkGrid"), OriginColorPicker.liveColor(MOD, "gridColor"));
	}

	static MinimapRenderer.Radar radar() {
		return new MinimapRenderer.Radar(
				Mods.bool(MOD, "showPlayers"), Mods.bool(MOD, "playerHeads"), Mods.bool(MOD, "showHostile"),
				Mods.bool(MOD, "showPassive"), Mods.bool(MOD, "showItems"), Mods.bool(MOD, "showOther"),
				OriginColorPicker.liveColor(MOD, "playerColor"), OriginColorPicker.liveColor(MOD, "hostileColor"),
				OriginColorPicker.liveColor(MOD, "passiveColor"), OriginColorPicker.liveColor(MOD, "itemColor"),
				OriginColorPicker.liveColor(MOD, "otherColor"),
				(float) Mods.num(MOD, "entityMarkerSize"), (float) Mods.num(MOD, "entityMarkerOpacity"),
				Mods.bool(MOD, "entityMarkerShadow"),
				// In cave mode, mobs far above (on the surface) or below would clutter the cave map.
				caveActive ? 24 : 0);
	}

	// ---- size ----

	private static float scale() {
		double s = Mods.num(MOD, "scale");
		return (float) (s <= 0.05 ? 1.0 : s);
	}

	private static boolean circle() {
		return Mods.mode(MOD, "shape").equals("Circle");
	}

	private static int mapW() {
		return (int) Math.round(Math.max(1, Mods.num(MOD, "mapWidth"))) * UNIT_PX;
	}

	/** A round map is Map Width across in both directions. */
	private static int mapH() {
		return circle() ? mapW() : (int) Math.round(Math.max(1, Mods.num(MOD, "mapHeight"))) * UNIT_PX;
	}

	/** One info line under the map: text in a colour. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Info {
		private final String text;
		private final int color;

		private Info(String text, int color) {
			this.text = text;
			this.color = color;
		}

		public String text() {
			return text;
		}

		public int color() {
			return color;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Info)) {
				return false;
			}
			Info other = (Info) obj;
			return java.util.Objects.equals(this.text, other.text)
					&& this.color == other.color;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(text, color);
		}
	}

	private static List<Info> info(Minecraft mc) {
		List<Info> out = new ArrayList<>();
		EntityPlayerSP p = mc.thePlayer;
		if (Mods.bool(MOD, "showCoordinates")) {
			out.add(new Info(p == null ? "0, 64, 0" : MathHelper.floor_double(p.posX) + ", " + MathHelper.floor_double(p.posY)
					+ ", " + MathHelper.floor_double(p.posZ), 0xFFFFFFFF));
		}
		if (Mods.bool(MOD, "showBiome")) {
			if (p != null && mc.theWorld != null) {
				BiomeGenBase biome = HudElements.biomeAt(mc, new BlockPos(p));
				int color = Mods.bool(MOD, "presetBiomeColor") ? HudElements.biomeColor(biome) : 0xFFFFFFFF;
				out.add(new Info(HudElements.biomeName(biome), color));
			} else {
				out.add(new Info("Plains", 0xFFFFFFFF));
			}
		}
		if (Mods.bool(MOD, "showClock")) {
			out.add(new Info(clock(mc), 0xFFFFFFFF));
		}
		return out;
	}

	/** The world's time of day as a clock (6:00 AM = day tick 0). Read from the level
	 *  data (WorldInfo), not World.getWorldTime(), so the Time Changer mod doesn't move it. */
	private static String clock(Minecraft mc) {
		long t = mc.theWorld != null ? Math.floorMod(mc.theWorld.getWorldInfo().getWorldTime(), 24000L) : 6000L;
		int hour = (int) ((t / 1000 + 6) % 24);
		int minute = (int) (t % 1000 * 60 / 1000);
		if (Mods.bool(MOD, "militaryTime")) {
			return String.format("%02d:%02d", hour, minute);
		}
		int h12 = hour % 12 == 0 ? 12 : hour % 12;
		String s = String.format("%d:%02d", h12, minute);
		return Mods.bool(MOD, "showAmPm") ? s + (hour < 12 ? " AM" : " PM") : s;
	}

	/** Unscaled {box width, box height}: the map plus the info lines under it. With Fit
	 *  Text To Width off, a long line widens the box (the map stays centred). */
	private static int[] box(Minecraft mc, List<Info> lines) {
		int w = mapW(), h = mapH();
		if (!lines.isEmpty()) {
			if (!Mods.bool(MOD, "fitTextToWidth")) {
				for (Info l : lines) {
					w = Math.max(w, mc.fontRendererObj.getStringWidth(l.text()) + 2);
				}
			}
			h += 3 + lines.size() * 10;
		}
		return new int[]{w, h};
	}

	public static int[] size(Minecraft mc) {
		if (mc.theWorld != null && !allowed(mc)) {
			return new int[]{0, 0};   // measures empty on servers, like any hidden HUD piece
		}
		float s = scale();
		int[] b = box(mc, info(mc));
		return new int[]{(int) Math.ceil(b[0] * s), (int) Math.ceil(b[1] * s)};
	}

	// ---- render ----

	public static void render(Gfx g, Minecraft mc, int w, int h) {
		if (mc.theWorld != null && !allowed(mc)) {
			return;
		}
		float s = scale();
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_MM_INFO);
		}
		List<Info> lines = info(mc);
		int[] b = box(mc, lines);
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_MM_INFO);
		}
		int mw = mapW(), mh = mapH();
		boolean circle = circle();
		Pose pose = g.pose();
		int depth = pose.depth();
		pose.pushPose();
		pose.scale(s, s, 1f);
		// (1.21.1 flushes GuiGraphics' batch here; on 1.8.9 every earlier HUD draw has
		// already been drawn on the spot, so there is nothing to flush.)

		float cx = b[0] / 2f, cy = mh / 2f;
		EntityPlayerSP player = mc.thePlayer;
		boolean world = player != null && mc.theWorld != null;
		float partial = MinimapHud.partial;   // set per frame by HudHooks (RenderTickEvent)
		double px = 0, pz = 0;
		float yaw = 180f;
		if (world) {
			px = player.prevPosX + (player.posX - player.prevPosX) * partial;
			pz = player.prevPosZ + (player.posZ - player.prevPosZ) * partial;
			yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partial;
		}
		boolean rotate = Mods.bool(MOD, "rotate");
		double yawRad = Math.toRadians(yaw);
		float angle = rotate ? (float) (Math.PI - yawRad) : 0f;
		float halfDiag = circle ? mw / 2f : (float) Math.hypot(mw / 2f, mh / 2f);
		// Never show further than the scanned window (MinimapData.MAX_RADIUS chunks).
		float ppb = (float) Math.max(Mods.num(MOD, "zoom"), halfDiag / (MinimapData.MAX_RADIUS * 16.0 - 8.0));
		MinimapRenderer.View v = new MinimapRenderer.View(cx, cy, mw / 2f, mh / 2f, circle, px, pz, ppb, angle);

		if (world) {
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_MM_UPDATE);
			}
			try {
				MinimapData.update(mc, v.radiusBlocks(), look(mc));
			} catch (Throwable t) {
				// terrain just stops updating this frame; the map itself still draws
			} finally {
				if (com.origin.client.client.BenchProf.ON) {
					com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_MM_UPDATE);
				}
			}
		}

		// The map is immediate-mode geometry. Like GuiGraphics' batched flush on 1.21.1, it
		// draws with the depth test off: the vanilla HUD layers above it in z have
		// already written depth (hotbar items, effect icons) and would hide it.
		boolean depthWas = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
		GlStateManager.disableDepth();
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_MM_DRAWMAP);
		}
		try {
			drawMap(g, mc, v, world, partial, yawRad);
		} finally {
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_MM_DRAWMAP);
			}
			if (depthWas) {
				GlStateManager.enableDepth();
			}
		}

		// Info lines, centred under the map; Fit Text To Width shrinks a long line
		// to the map's width instead of letting it hang over the sides.
		boolean fit = Mods.bool(MOD, "fitTextToWidth");
		float y = mh + 3;
		for (Info l : lines) {
			int tw = mc.fontRendererObj.getStringWidth(l.text());
			float ts = fit && tw > mw ? mw / (float) tw : 1f;
			pose.pushPose();
			pose.translate(cx, y + (10 - 9 * ts) / 2f, 0);
			pose.scale(ts, ts, 1f);
			g.drawString(mc.fontRendererObj, l.text(), -tw / 2, 0, l.color(), true);
			pose.popPose();
			y += 10;
		}
		HudElements.popTo(pose, depth);
	}

	private static void drawMap(Gfx g, Minecraft mc, MinimapRenderer.View v, boolean world, float partial, double yawRad) {
		if (Mods.bool(MOD, "showBackground")) {
			MinimapRenderer.background(v, OriginColorPicker.liveColor(MOD, "bgColor"));
		}
		if (world) {
			MinimapRenderer.terrain(v);
			MinimapRenderer.entities(g, mc, v, radar(), partial);
			if (Mods.bool(MOD, "showWaypoints") && Mods.on("waypoints")) {
				MinimapRenderer.waypoints(g, v, Mods.bool(MOD, "showDistantWaypoints"), 2.5f);
			}
			MinimapRenderer.party(g, v, 2f);
		}
		// The arrow points where you face: straight up when the map turns with you.
		double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
		float dx = (float) (fx * v.cos - fz * v.sin), dy = (float) (fx * v.sin + fz * v.cos);
		MinimapRenderer.playerArrow(v.cx, v.cy, (float) Math.atan2(dx, -dy),
				(float) Mods.num(MOD, "playerMarkerSize"), OriginColorPicker.liveColor(MOD, "playerMarkerColor"));
		if (Mods.bool(MOD, "border")) {
			MinimapRenderer.border(v, (float) Mods.num(MOD, "borderThickness"), OriginColorPicker.liveColor(MOD, "borderColor"));
		}
		if (Mods.bool(MOD, "compass")) {
			MinimapRenderer.compass(g, mc, v, OriginColorPicker.liveColor(MOD, "compassColor"), Mods.bool(MOD, "compassShadow"));
		}
	}
}
