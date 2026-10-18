package com.origin.client.client.minimap;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.hud.HudElements;
import com.origin.client.client.hud.HudPos;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

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
		return mc.hasSingleplayerServer();
	}

	// ---- tick: keys + automatic cave mode ----

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			MinimapData.leftWorld();
		}
		if (!Mods.on(MOD) || !allowed(mc)) {
			zoomInWasDown = zoomOutWasDown = mapKeyWasDown = false;
			return;
		}
		boolean free = mc.screen == null;
		boolean in = free && OriginClientMod.isRawKeyDown(Mods.keyCode(MOD, "zoomInKey"));
		boolean out = free && OriginClientMod.isRawKeyDown(Mods.keyCode(MOD, "zoomOutKey"));
		// Tracked even while a screen is open: the full map closes on this same key, and
		// the still-held key must not reopen it on the next tick.
		boolean map = OriginClientMod.isRawKeyDown(Mods.keyCode(MOD, "openMapKey"));
		if (in && !zoomInWasDown) {
			stepZoom(mc, +1);
		}
		if (out && !zoomOutWasDown) {
			stepZoom(mc, -1);
		}
		if (free && map && !mapKeyWasDown && mc.player != null) {
			mc.setScreen(new MinimapScreen());
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
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal("Minimap zoom: " + fmtZoom(next) + "x"));
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
		if (mc.level == null || mc.player == null || mode.equals("Off")) {
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
		if (mc.level.dimensionType().hasCeiling()) {
			want = true;
		} else {
			BlockPos head = BlockPos.containing(mc.player.getEyePosition());
			int surface = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, head.getX(), head.getZ());
			want = surface > head.getY() + 1 && mc.level.getBrightness(LightLayer.SKY, head) < 8;
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
		int top = mc.player != null ? mc.player.getBlockY() + 2 : 0;
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
	private record Info(String text, int color) {
	}

	private static List<Info> info(Minecraft mc) {
		List<Info> out = new ArrayList<>();
		var p = mc.player;
		if (Mods.bool(MOD, "showCoordinates")) {
			out.add(new Info(p == null ? "0, 64, 0" : p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ(), 0xFFFFFFFF));
		}
		if (Mods.bool(MOD, "showBiome")) {
			if (p != null && mc.level != null) {
				var biome = mc.level.getBiome(p.blockPosition());
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
	 *  data, not Level.getDayTime(), so the Time Changer mod doesn't move it. */
	private static String clock(Minecraft mc) {
		long t = mc.level != null ? Math.floorMod(com.origin.client.client.OriginClientMod.realDayTime(mc.level), 24000L) : 6000L;
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
					w = Math.max(w, mc.font.width(l.text()) + 2);
				}
			}
			h += 3 + lines.size() * 10;
		}
		return new int[]{w, h};
	}

	public static int[] size(Minecraft mc) {
		if (mc.level != null && !allowed(mc)) {
			return new int[]{0, 0};   // measures empty on servers, like any hidden HUD piece
		}
		float s = scale();
		int[] b = box(mc, info(mc));
		return new int[]{(int) Math.ceil(b[0] * s), (int) Math.ceil(b[1] * s)};
	}

	// ---- render ----

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		if (mc.level != null && !allowed(mc)) {
			return;
		}
		float s = scale();
		List<Info> lines = info(mc);
		int[] b = box(mc, lines);
		int mw = mapW(), mh = mapH();
		boolean circle = circle();
		// 1.21.11: g.pose() is a Matrix3x2fStack (pushMatrix / 2-arg scale and
		// translate), and nothing needs flushing -- the deferred GUI keeps the
		// order things were submitted in.
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(s, s);

		float cx = b[0] / 2f, cy = mh / 2f;
		var player = mc.player;
		boolean world = player != null && mc.level != null;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double px = 0, pz = 0;
		float yaw = 180f;
		if (world) {
			px = player.xo + (player.getX() - player.xo) * partial;
			pz = player.zo + (player.getZ() - player.zo) * partial;
			yaw = player.getViewYRot(partial);
		}
		boolean rotate = Mods.bool(MOD, "rotate");
		double yawRad = Math.toRadians(yaw);
		float angle = rotate ? (float) (Math.PI - yawRad) : 0f;
		float halfDiag = circle ? mw / 2f : (float) Math.hypot(mw / 2f, mh / 2f);
		// Never show further than the scanned window (MinimapData.MAX_RADIUS chunks).
		float ppb = (float) Math.max(Mods.num(MOD, "zoom"), halfDiag / (MinimapData.MAX_RADIUS * 16.0 - 8.0));
		MinimapRenderer.View v = new MinimapRenderer.View(cx, cy, mw / 2f, mh / 2f, circle, px, pz, ppb, angle);

		if (world) {
			try {
				MinimapData.update(mc, v.radiusBlocks(), look(mc));
			} catch (Throwable t) {
				// terrain just stops updating this frame; the map itself still draws
			}
		}

		// 1.21.1 drew the map immediately with the depth test off; here it is
		// recorded into the deferred GUI like every other HUD element (no depth).
		drawMap(g, mc, v, world, partial, yawRad);

		// Info lines, centred under the map; Fit Text To Width shrinks a long line
		// to the map's width instead of letting it hang over the sides.
		boolean fit = Mods.bool(MOD, "fitTextToWidth");
		float y = mh + 3;
		for (Info l : lines) {
			int tw = mc.font.width(l.text());
			float ts = fit && tw > mw ? mw / (float) tw : 1f;
			pose.pushMatrix();
			pose.translate(cx, y + (10 - 9 * ts) / 2f);
			pose.scale(ts, ts);
			g.text(mc.font, l.text(), -tw / 2, 0, l.color(), true);
			pose.popMatrix();
			y += 10;
		}
		pose.popMatrix();
	}

	private static void drawMap(GuiGraphicsExtractor g, Minecraft mc, MinimapRenderer.View v, boolean world, float partial, double yawRad) {
		if (Mods.bool(MOD, "showBackground")) {
			MinimapRenderer.background(g, v, OriginColorPicker.liveColor(MOD, "bgColor"));
		}
		if (world) {
			MinimapRenderer.terrain(g, v);
			MinimapRenderer.entities(g, mc, v, radar(), partial);
			if (Mods.bool(MOD, "showWaypoints") && Mods.on("waypoints")) {
				MinimapRenderer.waypoints(g, v, Mods.bool(MOD, "showDistantWaypoints"), 2.5f);
			}
			MinimapRenderer.party(g, v, 2f);
		}
		// The arrow points where you face: straight up when the map turns with you.
		double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
		float dx = (float) (fx * v.cos - fz * v.sin), dy = (float) (fx * v.sin + fz * v.cos);
		MinimapRenderer.playerArrow(g, v.cx, v.cy, (float) Math.atan2(dx, -dy),
				(float) Mods.num(MOD, "playerMarkerSize"), OriginColorPicker.liveColor(MOD, "playerMarkerColor"));
		if (Mods.bool(MOD, "border")) {
			MinimapRenderer.border(g, v, (float) Mods.num(MOD, "borderThickness"), OriginColorPicker.liveColor(MOD, "borderColor"));
		}
		if (Mods.bool(MOD, "compass")) {
			MinimapRenderer.compass(g, mc, v, OriginColorPicker.liveColor(MOD, "compassColor"), Mods.bool(MOD, "compassShadow"));
		}
	}
}
