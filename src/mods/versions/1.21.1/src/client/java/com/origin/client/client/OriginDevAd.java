package com.origin.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.mixin.DevAdMouseAccess;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Dev-only ad capture harness (branch claude/ad-capture, never released). With {@code ORIGIN_SHOTS_SET=ad} and
 * {@code ORIGIN_AD_CLIP=R2..R8} the dev client opens one fixed world (seed {@link #SEED}), stages one clip of the
 * Origin Client launch ad and records it with {@link OriginDevAdCapture} into {@code $ORIGIN_SHOTS/<clip>*.mp4}.
 *
 * <p>Every input is scripted inside the game: clicks, scrolls, drags and keys are synthetic GLFW events sent through
 * Minecraft's own MouseHandler / KeyboardHandler (so menus, Key Strokes and CPS see them exactly like a real mouse),
 * while the real OS mouse and keyboard are cut off from the window (DevAdInputMixin) and the window never takes focus
 * or grabs the pointer (DevAdWindowMixin). The OS pointer is never moved. Camera moves set the player's pose every
 * frame. Run it through tools/ad-capture/capture.py.
 */
public final class OriginDevAd {
	public static final boolean ON = "ad".equals(System.getenv("ORIGIN_SHOTS_SET"));
	private static final String DIR = System.getenv("ORIGIN_SHOTS");
	private static final String CLIP = env("ORIGIN_AD_CLIP", "R2");
	private static final boolean PROBE = "probe".equals(env("ORIGIN_AD_MODE", "take"));
	private static final int FPS = Integer.parseInt(env("ORIGIN_AD_FPS", "60"));
	static final String LEVEL = "origin-ad";
	/** The 1.18 title panorama's seed (tools/panorama/spots.json): jagged peaks right by spawn. */
	static final long SEED = 6671238423019257953L;
	static final int TIME = Integer.parseInt(env("ORIGIN_AD_TIME", "11800"));   // golden hour, sun low in the west

	/** True while the harness itself is feeding an input event (the real devices are blocked). */
	public static boolean synthetic;
	/** ARGB painted over the world before the HUD draws (0 = off): the keyed HUD clip R9 (DevAdGuiMixin). */
	public static int keyBackground = 0;
	/** Dev-only deck layout (R12): Origin windows drawn at this scale with their top at deckTop window px (0 = normal). */
	public static float deckScale = 0, deckTop = 0;
	/** GLFW keys the script holds down (DevAdKeysMixin answers InputConstants.isKeyDown from this; Snaplook reads raw state). */
	public static final Set<Integer> heldKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** ORIGIN_AD_SUFFIX: appended to a V3 take's name (capture.py --suffix _9x16 for the portrait takes). */
	static final String SUFFIX = env("ORIGIN_AD_SUFFIX", "").trim();

	private static int phase = 0;
	private static long nextAt = 0, phaseFrom = 0;
	private static boolean fresh;
	private static Clip clip;

	private OriginDevAd() {
	}

	static String env(String k, String def) {
		String v = System.getenv(k);
		return v == null || v.isBlank() ? def : v;
	}

	/** The take's window (capture.py --size, default 1920x1080) and GUI scale (--gui, default 2). */
	static final int WIN_W = Integer.parseInt(env("ORIGIN_AD_SIZE", "1920x1080").split("x")[0].trim());
	static final int WIN_H = Integer.parseInt(env("ORIGIN_AD_SIZE", "1920x1080").split("x")[1].trim());
	static final int GUI = Integer.parseInt(env("ORIGIN_AD_GUI", "2"));

	/**
	 * Window px per 1080p px for the deck screens (Download Shaders, FPS Coach): OriginDeck scales them with the window
	 * height and centres them, so a 1080p spot (x, y) sits at (x, y) * this on a 16:9 window of any size.
	 */
	static double k1080(Minecraft mc) {
		return mc.getWindow().getHeight() / 1080.0;
	}

	static void log(String s) {
		com.origin.client.OriginClient.LOGGER.info("[ad] " + s);
	}

	// ================================================================ hooks

	/** Client tick (20 Hz): the run's state machine. Called from OriginDevShots.tick when ON. */
	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (now < nextAt) {
			return;
		}
		try {
			switch (phase) {
				case 0 -> {   // wait for the title, set the look, open the world
					if (mc.getOverlay() != null || mc.screen == null) {
						return;
					}
					if (!(mc.screen instanceof TitleScreen)) {
						mc.options.onboardAccessibility = false;
						mc.setScreen(new TitleScreen());
						return;
					}
					if (nextAt == 0) {
						nextAt = now + 1500;
						return;
					}
					clip = Clip.of(CLIP);
					baseOptions(mc);
					clip.options(mc);
					if (clip.titleOnly()) {   // V3_TITLE: the script runs on the title screen itself, no world
						log("title clip " + CLIP);
						go(5, now);
						return;
					}
					clip.openingWorld();   // V3_LOAD starts recording here, before openWorld: it spins the loading screens itself
					var flows = mc.createWorldOpenFlows();
					fresh = !mc.getLevelSource().levelExists(LEVEL);
					if (!fresh) {
						flows.openWorld(LEVEL, () -> mc.setScreen(new TitleScreen()));
					} else {
						LevelSettings settings = new LevelSettings("Origin Ad", GameType.CREATIVE, false, Difficulty.NORMAL, true,
								new GameRules(), WorldDataConfiguration.DEFAULT);
						flows.createFreshLevel(LEVEL, settings, new WorldOptions(SEED, true, false),
								WorldPresets::createNormalWorldDimensions, new TitleScreen());
					}
					log((fresh ? "creating" : "opening") + " world " + LEVEL + " (seed " + SEED + ") for " + CLIP + (PROBE ? " [probe]" : ""));
					go(1, now);
				}
				case 1 -> {   // in the world: common rules, then the clip's own server setup
					if (mc.screen instanceof PauseScreen) {
						mc.setScreen(null);
					}
					MinecraftServer server = mc.getSingleplayerServer();
					if (mc.level == null || mc.player == null || server == null || mc.screen != null) {
						if (now - phaseFrom > 300_000) {
							throw new IllegalStateException("world never loaded");
						}
						return;
					}
					server.execute(() -> {
						try {
							commonSetup(server);
							clip.server(server, level(server), server.getPlayerList().getPlayers().get(0));
						} catch (Throwable t) {
							log("server setup failed: " + t);
							clip.failed = t.toString();
						}
						clip.serverReady = true;
					});
					go(2, now);
				}
				case 2 -> {
					if (clip.failed != null) {
						throw new IllegalStateException(clip.failed);
					}
					if (mc.screen instanceof PauseScreen && !clip.allowPause) {
						mc.setScreen(null);
					}
					if (clip.serverReady && clip.tick(mc, now - phaseFrom)) {
						go(3, now);
					}
				}
				case 5 -> {   // title-only clip
					if (clip.failed != null) {
						throw new IllegalStateException(clip.failed);
					}
					if (clip.tick(mc, now - phaseFrom)) {
						go(3, now);
					}
				}
				case 3 -> {
					log("done");
					try {
						clip.finish(mc);
					} catch (Throwable t) {
						log("finish failed: " + t);
					}
					OriginDevAdCapture.stop();
					phase = 4;
					mc.stop();
				}
				default -> {
				}
			}
		} catch (Throwable t) {
			log("FAILED in phase " + phase + ": " + t);
			com.origin.client.OriginClient.LOGGER.warn("[ad] failure", t);
			OriginDevAdCapture.stop();
			phase = 4;
			mc.stop();
		}
	}

	/** Render thread, every frame, after the ticks and before the world is drawn: camera and per-frame input. */
	public static void preRender(Minecraft mc) {
		if (clip != null && clip.takeFrom >= 0) {
			clip.noteScreen(mc);
		}
		if (clip != null && (phase == 2 && mc.player != null || phase == 5)) {
			// The window is never really grabbed (that would take Will's pointer): mirror only the flag, so in-world
			// clicks reach the key mappings while menus see a free pointer.
			((DevAdMouseAccess) mc.mouseHandler).originad$setGrabbed(mc.screen == null);
			try {
				clip.frame(mc);
			} catch (Throwable t) {
				log("frame failed: " + t);
				com.origin.client.OriginClient.LOGGER.warn("[ad] frame failure", t);
				clip.failed = t.toString();
			}
		}
	}

	private static void go(int p, long now) {
		phase = p;
		phaseFrom = now;
		nextAt = 0;
	}

	// ================================================================ setup

	private static void baseOptions(Minecraft mc) {
		var o = mc.options;
		o.guiScale().set(GUI);
		o.fov().set(70);
		o.fovEffectScale().set(0.0);
		o.renderDistance().set(20);
		o.simulationDistance().set(8);
		o.framerateLimit().set(260);
		o.enableVsync().set(false);
		o.bobView().set(false);
		o.hideGui = false;
		o.onboardAccessibility = false;
		o.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
		// The menus and the HUD editor draw a sample boss bar ("Ender Dragon") while open; no clip is about it.
		Mods.setOn("bossbar", false);
		o.pauseOnLostFocus = false;
		o.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
		o.save();
		if (mc.getWindow().getWidth() != WIN_W || mc.getWindow().getHeight() != WIN_H) {
			mc.getWindow().setWindowed(WIN_W, WIN_H);
		}
		// Origin's own: never throttle (the window is deliberately left unfocused), no HUD extras we didn't pick.
		Mods.set(Mods.PERFORMANCE_ID, "limitUnfocusedFps", false);
		for (String id : new String[]{"fps", "keystrokes", "cps", "combo", "armorhud"}) {
			Mods.setOn(id, false);
		}
	}

	private static void commonSetup(MinecraftServer server) {
		for (String c : new String[]{
				"gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
				"gamerule doFireTick false", "gamerule doInsomnia false", "gamerule doPatrolSpawning false",
				"gamerule doTraderSpawning false", "gamerule sendCommandFeedback false", "gamerule announceAdvancements false",
				"gamerule showDeathMessages false", "gamerule doImmediateRespawn true", "gamerule keepInventory true",
				"weather clear 1000000", "time set " + TIME, "difficulty normal", "recipe give @a *"}) {
			cmd(server, c);
		}
	}

	static void cmd(MinecraftServer server, String c) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), c);
	}

	static ServerLevel level(MinecraftServer server) {
		return server.overworld();
	}

	// ================================================================ synthetic input (real code paths, no OS device)

	private static long handle(Minecraft mc) {
		return mc.getWindow().getWindow();
	}

	/** Pointer to window pixel (x, y); a held button turns it into a drag. */
	static void mouseTo(Minecraft mc, double x, double y) {
		synthetic = true;
		try {
			((DevAdMouseAccess) mc.mouseHandler).originad$onMove(handle(mc), x, y);
		} finally {
			synthetic = false;
		}
		if (clip != null) {
			clip.trackMove(x, y);
		}
	}

	static void mouseGui(Minecraft mc, double gx, double gy) {
		double s = mc.getWindow().getGuiScale();
		mouseTo(mc, gx * s, gy * s);
	}

	static void button(Minecraft mc, int b, boolean down) {
		synthetic = true;
		try {
			((DevAdMouseAccess) mc.mouseHandler).originad$onPress(handle(mc), b, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
		} finally {
			synthetic = false;
		}
		if (clip != null) {
			clip.trackEvent(down ? "press" : "release", "mouse" + b, mx(mc), my(mc));
		}
	}

	static void scroll(Minecraft mc, double dy) {
		synthetic = true;
		try {
			((DevAdMouseAccess) mc.mouseHandler).originad$onScroll(handle(mc), 0, dy);
		} finally {
			synthetic = false;
		}
	}

	static void key(Minecraft mc, int key, boolean down) {
		synthetic = true;
		try {
			mc.keyboardHandler.keyPress(handle(mc), key, GLFW.glfwGetKeyScancode(key), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
		} finally {
			synthetic = false;
		}
		if (clip != null) {
			clip.trackEvent(down ? "keydown" : "keyup", keyName(key), mx(mc), my(mc));
		}
	}

	/** Every clickable target an Origin window recorded last frame, in window px (for mapping a screen once). */
	static void logHits(Screen s) {
		try {
			Class<?> c = s.getClass();
			java.lang.reflect.Field hf = null;
			for (Class<?> k = c; k != null && hf == null; k = k.getSuperclass()) {
				for (java.lang.reflect.Field f : k.getDeclaredFields()) {
					if (f.getName().equals("hits")) {
						hf = f;
					}
				}
			}
			if (hf == null) {
				log("hits: none on " + c.getSimpleName());
				return;
			}
			hf.setAccessible(true);
			float ox = fieldF(s, "lastOx"), oy = fieldF(s, "lastOy"), sc = fieldF(s, "lastS");
			List<?> hits = (List<?>) hf.get(s);
			StringBuilder b = new StringBuilder("hits on " + c.getSimpleName() + " (window px):");
			for (Object h : new ArrayList<>(hits)) {
				var r = (com.origin.client.client.gui.OriginDeck.Hit) h;
				b.append(String.format(Locale.ROOT, " [%.0f,%.0f-%.0f,%.0f]", ox + r.x0() * sc, oy + r.y0() * sc, ox + r.x1() * sc, oy + r.y1() * sc));
			}
			log(b.toString());
		} catch (Throwable t) {
			log("hits: " + t);
		}
	}

	static float fieldF(Object o, String name) throws Exception {
		for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
			try {
				java.lang.reflect.Field f = k.getDeclaredField(name);
				f.setAccessible(true);
				return f.getFloat(o);
			} catch (NoSuchFieldException ignored) {
			}
		}
		return Float.NaN;
	}

	static AbstractWidget widget(Screen s, String label) {
		for (var ch : s.children()) {
			if (ch instanceof AbstractWidget w && w.visible && w.getMessage().getString().toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT))) {
				return w;
			}
		}
		return null;
	}

	static void logWidgets(Screen s) {
		StringBuilder b = new StringBuilder("widgets on " + s.getClass().getSimpleName() + " (gui px):");
		for (var ch : s.children()) {
			if (ch instanceof AbstractWidget w) {
				b.append(String.format(Locale.ROOT, " '%s'[%d,%d %dx%d]", w.getMessage().getString(), w.getX(), w.getY(), w.getWidth(), w.getHeight()));
			} else {
				b.append(" <").append(ch.getClass().getSimpleName()).append('>');
			}
		}
		log(b.toString());
	}

	static void still(Minecraft mc, String name) {
		Screenshot.grab(new File(DIR), name + ".png", mc.getMainRenderTarget(), c -> {
		});
	}

	static String out(String name) {
		return new File(DIR, name + ".mp4").getAbsolutePath();
	}

	/** Holds the camera exactly at a pose this frame (no interpolation drift, no physics). */
	static void pose(LocalPlayer p, double x, double y, double z, float yaw, float pitch) {
		p.setPos(x, y, z);
		p.xo = x;
		p.yo = y;
		p.zo = z;
		p.xOld = x;
		p.yOld = y;
		p.zOld = z;
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.yRotO = yaw;
		p.xRotO = pitch;
		p.setYHeadRot(yaw);
		p.yHeadRotO = yaw;
		p.setDeltaMovement(Vec3.ZERO);
	}

	// ================================================================ clips

	/** One clip's script. tick() runs at 20 Hz until it returns true (done); frame() runs every rendered frame. */
	abstract static class Clip {
		volatile boolean serverReady;
		/** A take that opens the pause menu itself; otherwise a stray pause screen is closed while the take runs. */
		boolean allowPause;
		volatile String failed;
		long takeFrom = -1;

		static Clip of(String id) {
			return switch (id) {
				case "R2" -> new ClipLandscape();
				case "R3" -> new ClipShaders();
				case "R4" -> new ClipPacks();
				case "R5" -> new ClipFps();
				case "R6" -> new ClipHud();
				case "R7" -> new ClipSocial();
				case "R8" -> new ClipPvp();
				case "R9" -> new ClipHudKeyed();
				case "R10" -> new ClipPicker();
				case "R11" -> new ClipCoach();
				case "R12" -> new ClipMenuStills();
				default -> {
					Clip v3 = OriginDevAdV3.of(id);
					if (v3 == null) {
						throw new IllegalArgumentException("unknown clip " + id);
					}
					yield v3;
				}
			};
		}

		void options(Minecraft mc) {
		}

		/** True: the clip runs on the title screen (phase 5), no world is opened. */
		boolean titleOnly() {
			return false;
		}

		/** Right after the world open was requested (still on the title screen). */
		void openingWorld() {
		}

		/** The run is over (phase 3), right before the recorder stops. */
		void finish(Minecraft mc) {
		}

		// ---- V3 sidecars: every scripted pointer position and input event, in take seconds and output (window) px
		String takeName;
		final StringBuilder track = new StringBuilder(), events = new StringBuilder(), screens = new StringBuilder();
		private double lastX = Double.NaN, lastY = Double.NaN;
		private String lastScreen = "?";

		void trackMove(double x, double y) {
			if (takeFrom < 0 || x == lastX && y == lastY) {
				return;
			}
			lastX = x;
			lastY = y;
			track.append(track.length() == 0 ? "" : ", ").append(String.format(Locale.ROOT, "[%.4f, %.1f, %.1f]", t(), x, y));
		}

		void trackEvent(String type, String what, double x, double y) {
			if (takeFrom < 0) {
				return;
			}
			events.append(events.length() == 0 ? "" : ",\n    ").append(String.format(Locale.ROOT,
					"{\"t\": %.4f, \"type\": \"%s\", \"input\": \"%s\", \"x\": %.1f, \"y\": %.1f}", t(), type, what, x, y));
		}

		void noteScreen(Minecraft mc) {
			String s = mc.screen == null ? null : mc.screen.getClass().getSimpleName();
			if (!java.util.Objects.equals(s, lastScreen)) {
				lastScreen = s;
				screens.append(screens.length() == 0 ? "" : ", ").append(String.format(Locale.ROOT, "[%.4f, %s]", Math.max(0, t()),
						s == null ? "null" : "\"" + s + "\""));
			}
		}

		/** The take's pointer/events/screens as JSON (extra: more top-level fields, "" or starting with ","). */
		String trackJson(Minecraft mc, String extra) {
			return String.format(Locale.ROOT, "{\n  \"take\": \"%s\",\n  \"size\": [%d, %d],\n  \"gui\": %.0f,\n  \"fps\": %d,\n"
							+ "  \"note\": \"t = seconds into the take; x,y = output pixels; pointer holds its last position between entries; "
							+ "screens: [t, open screen class or null] (null = in the world: no pointer drawn)\",\n"
							+ "  \"length\": %.3f,\n  \"pointer\": [%s],\n  \"events\": [\n    %s\n  ],\n  \"screens\": [%s]%s\n}\n",
					takeName, mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getWindow().getGuiScale(), FPS, Math.max(0, t()),
					track, events, screens, extra);
		}

		abstract void server(MinecraftServer server, ServerLevel level, ServerPlayer p);

		abstract boolean tick(Minecraft mc, long sincePhase);

		void frame(Minecraft mc) {
		}

		/** Seconds into the take (0 before it starts). */
		double t() {
			return takeFrom < 0 ? -1 : (System.nanoTime() - takeFrom) / 1e9;
		}

		void startTake(String name) throws java.io.IOException {
			if (name.startsWith("V3_")) {
				name += SUFFIX;
			}
			takeName = name;
			if (!PROBE) {
				OriginDevAdCapture.start(out(name), FPS);
			}
			takeFrom = System.nanoTime();
			if (!"R7".equals(CLIP) && !"V3_FRIENDS".equals(CLIP)) {
				Minecraft.getInstance().getToasts().clear();   // recipe/advancement toasts from the setup commands
			}
			log("take " + name + " started");
		}

		void stopTake() {
			OriginDevAdCapture.stop();
			takeFrom = -1;
		}
	}

	// ---------------------------------------------------------------- scenery: real generated spots of the seed

	/** A camera move: u in [0,1] -> {x, y, z, yaw, pitch}. */
	interface Path {
		double[] at(double u);
	}

	record Shot(String name, Path path, double secs) {
	}

	static final Set<String> PEAKS = Set.of("minecraft:jagged_peaks", "minecraft:frozen_peaks", "minecraft:stony_peaks");
	static final Set<String> WATER = Set.of("minecraft:river", "minecraft:beach", "minecraft:lukewarm_ocean", "minecraft:ocean",
			"minecraft:warm_ocean", "minecraft:stony_shore");
	static final Set<String> FOREST = Set.of("minecraft:old_growth_birch_forest", "minecraft:birch_forest", "minecraft:flower_forest",
			"minecraft:dark_forest", "minecraft:old_growth_spruce_taiga", "minecraft:forest");

	static BlockPos locate(ServerLevel level, BlockPos from, Set<String> biomes) {
		var found = level.findClosestBiome3d((Holder<Biome> h) -> h.unwrapKey().map(k -> biomes.contains(k.location().toString())).orElse(false),
				from, 6400, 32, 64);
		if (found == null) {
			throw new IllegalStateException("no " + biomes + " near " + from);
		}
		log("locate " + biomes.iterator().next() + "... -> " + found.getFirst().toShortString() + " ("
				+ found.getSecond().unwrapKey().map(k -> k.location().toString()).orElse("?") + ")");
		return found.getFirst();
	}

	static String biomeAt(ServerLevel level, int x, int y, int z) {
		return level.getBiome(new BlockPos(x, y, z)).unwrapKey().map(k -> k.location().toString()).orElse("?");
	}

	/** Generates every chunk within r blocks of (x, z) (server thread), so heights are real. */
	static void load(ServerLevel level, int x, int z, int r) {
		for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) {
			for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
				level.getChunk(cx, cz);
			}
		}
	}

	/** Generates the chunk under (x, z) if it isn't yet (getHeight lies for chunks that aren't loaded). */
	static void need(ServerLevel level, double x, double z) {
		level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
	}

	static int ground(ServerLevel level, double x, double z) {
		need(level, x, z);
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
	}

	static int top(ServerLevel level, double x, double z) {
		need(level, x, z);
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
	}

	static boolean water(ServerLevel level, double x, double z) {
		need(level, x, z);
		int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z));
		return level.getFluidState(new BlockPos((int) Math.floor(x), h - 1, (int) Math.floor(z))).is(net.minecraft.tags.FluidTags.WATER);
	}

	static float yawTo(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	static float pitchTo(double dy, double dist) {
		return (float) -Math.toDegrees(Math.atan2(dy, dist));
	}

	static double lerp(double a, double b, double u) {
		return a + (b - a) * u;
	}

	/** Orbit around a summit: the camera arcs at a fixed radius and height, always looking at the mountain. */
	static Shot mountain(ServerLevel level, BlockPos near) {
		BlockPos at = locate(level, near, PEAKS);
		load(level, at.getX(), at.getZ(), 128);
		int px = at.getX(), pz = at.getZ(), ph = -999;
		for (int dx = -96; dx <= 96; dx += 4) {
			for (int dz = -96; dz <= 96; dz += 4) {
				int h = ground(level, at.getX() + dx, at.getZ() + dz);
				if (h > ph) {
					ph = h;
					px = at.getX() + dx;
					pz = at.getZ() + dz;
				}
			}
		}
		final int cx = px, cz = pz, peak = ph;
		// Camera south of the summit (sun low in the west lights the slopes from the side), arcing west -> east.
		double best = -1, bestR = 80, bestAlt = peak;
		for (double r = 60; r <= 130; r += 10) {
			int max = -999;
			for (double a = 55; a <= 105; a += 5) {
				double x = cx + r * Math.cos(Math.toRadians(a)), z = cz + r * Math.sin(Math.toRadians(a));
				max = Math.max(max, top(level, x, z));
			}
			double alt = Math.max(peak - 14, max + 10);
			double score = -Math.abs(alt - (peak - 10)) - r * 0.05;
			if (best < 0 || score > best) {
				best = score;
				bestR = r;
				bestAlt = alt;
			}
		}
		final double R = bestR, alt = bestAlt;
		log(String.format(Locale.ROOT, "mountain: summit %d %d %d (%s), orbit r=%.0f at y=%.0f", cx, peak, cz, biomeAt(level, cx, peak, cz), R, alt));
		return new Shot("mountain", u -> {
			double a = Math.toRadians(lerp(105, 60, u));
			double x = cx + R * Math.cos(a), z = cz + R * Math.sin(a);
			double ty = peak - 16;
			return new double[]{x, alt, z, yawTo(cx - x, cz - z), pitchTo(ty - alt, R)};
		}, 11);
	}

	/** A low run along open water with land on both sides, facing into the low sun. */
	static Shot waterRun(ServerLevel level, BlockPos near) {
		int sea = level.getSeaLevel();
		double bestScore = -1e9;
		double[] best = null;
		BlockPos at = null;
		for (String biome : new String[]{"minecraft:river"}) {   // a river: land on both banks, the classic reflection shot
		try {
			at = locate(level, near, Set.of(biome));
		} catch (IllegalStateException e) {
			continue;
		}
		load(level, at.getX(), at.getZ(), 112);
		for (int ox = -48; ox <= 48; ox += 8) {
			for (int oz = -48; oz <= 48; oz += 8) {
				double sx = at.getX() + ox, sz = at.getZ() + oz;
				if (!water(level, sx, sz)) {
					continue;
				}
				for (int d = 0; d < 16; d++) {
					double a = Math.toRadians(d * 22.5);
					double ex = sx + 56 * Math.cos(a), ez = sz + 56 * Math.sin(a);
					int wet = 0, land = 0, wall = 0;
					for (int i = 0; i <= 19; i++) {   // the 56-block run plus 20 blocks of open water ahead
						double x = lerp(sx, ex, i / 14.0), z = lerp(sz, ez, i / 14.0);
						if (water(level, x, z)) {
							wet++;
						}
						// land rising beside the run (banks, hills): what makes it more than open sea
						double nx = -Math.sin(a), nz = Math.cos(a);
						for (int side = -1; side <= 1; side += 2) {
							for (int k = 24; k <= 48; k += 12) {
								land += Math.max(0, Math.min(30, top(level, x + side * nx * k, z + side * nz * k) - sea));
							}
							for (int k = 4; k <= 24; k += 4) {
								if (top(level, x + side * nx * k, z + side * nz * k) - sea > k + 3) {
									wall++;   // a bank rising right beside the lens blocks half the frame
								}
							}
						}
					}
					if (wet < 20) {
						continue;
					}
					// into the low western sun (yaw ~90) reads gold on the water
					float yaw = yawTo(ex - sx, ez - sz);
					double sun = Math.cos(Math.toRadians(yaw - 90));
					double score = land + 80 * sun - 40 * wall;
					if (score > bestScore) {
						bestScore = score;
						best = new double[]{sx, sz, ex, ez};
					}
				}
			}
		}
		}
		double[] pick = coords("ORIGIN_AD_WATER");   // a run picked by eye from the probe stills (still the seed's own terrain)
		if (pick != null) {
			load(level, (int) pick[0], (int) pick[1], 96);
			best = pick;
		}
		if (best == null) {
			throw new IllegalStateException("no open water run near " + near.toShortString());
		}
		final double[] b = best;
		final double y = sea + 3.2;
		final float yaw = yawTo(b[2] - b[0], b[3] - b[1]);
		log(String.format(Locale.ROOT, "water: %.0f,%.0f -> %.0f,%.0f at y=%.1f yaw %.0f (%s) score %.0f", b[0], b[1], b[2], b[3], y, yaw,
				biomeAt(level, (int) b[0], sea, (int) b[1]), bestScore));
		return new Shot("water", u -> new double[]{lerp(b[0], b[2], u), y + 1.5 * u, lerp(b[1], b[3], u), yaw, (float) lerp(4, 7, u)}, 11);
	}

	/** Skimming a flat forest canopy at golden hour: the flattest, densest straight run found near a forest biome. */
	static Shot canopy(ServerLevel level, BlockPos near) {
		double bestScore = -1e9;
		double[] best = null;
		for (String biome : new String[]{"minecraft:birch_forest", "minecraft:dark_forest", "minecraft:forest", "minecraft:old_growth_birch_forest"}) {
		BlockPos at;
		try {
			at = locate(level, near, Set.of(biome));
		} catch (IllegalStateException e) {
			continue;
		}
		load(level, at.getX(), at.getZ(), 112);
		for (int ox = -48; ox <= 48; ox += 16) {
			for (int oz = -48; oz <= 48; oz += 16) {
				double cx = at.getX() + ox, cz = at.getZ() + oz;
				for (int d = 0; d < 8; d++) {
					double a = Math.toRadians(d * 45);
					double ux = Math.cos(a), uz = Math.sin(a);
					double sx = cx - 28 * ux, sz = cz - 28 * uz, ex = cx + 28 * ux, ez = cz + 28 * uz;
					int inForest = 0, max = -999, lo = 999, hi = -999, leaves = 0;
					for (int i = 0; i <= 28; i++) {   // the run plus 14 blocks of look-ahead, a 10-block band each side
						double x = lerp(sx, ex + 14 * ux, i / 28.0), z = lerp(sz, ez + 14 * uz, i / 28.0);
						for (int side = -10; side <= 10; side += 5) {
							double bx = x - uz * side, bz = z + ux * side;
							int t = top(level, bx, bz), gr = ground(level, bx, bz);
							max = Math.max(max, t);
							lo = Math.min(lo, gr);
							hi = Math.max(hi, gr);
							if (t - gr >= 4) {
								leaves++;
							}
							if (FOREST.contains(biomeAt(level, (int) bx, gr, (int) bz))) {
								inForest++;
							}
						}
					}
					if (water(level, cx, cz)) {
						continue;
					}
					float yaw = yawTo(ux, uz);
					double side = Math.abs(Math.sin(Math.toRadians(yaw - 90)));   // sun to the side: long shadows across the canopy
					double score = inForest * 3 + leaves * 4 - (hi - lo) * 12 + side * 40;
					if (score > bestScore) {
						bestScore = score;
						best = new double[]{sx, sz, ex, ez, max, hi - lo};
					}
				}
			}
		}
		}
		final double[] b = best;
		final double y = b[4] + 5;
		final float yaw = yawTo(b[2] - b[0], b[3] - b[1]);
		log(String.format(Locale.ROOT, "forest: %.0f,%.0f -> %.0f,%.0f at y=%.0f yaw %.0f (%s, ground range %.0f) score %.0f", b[0], b[1], b[2], b[3], y, yaw,
				biomeAt(level, (int) b[0], (int) y - 8, (int) b[1]), b[5], bestScore));
		return new Shot("forest", u -> new double[]{lerp(b[0], b[2], u), y, lerp(b[1], b[3], u), yaw, 9f}, 11);
	}

	/** ORIGIN_AD_FLIGHTS: the R2 flights in order (default the three of the first cut). */
	static final String[] FLIGHTS = env("ORIGIN_AD_FLIGHTS", "mountain,water,forest").split(",");
	static final String VANILLA = env("ORIGIN_AD_VANILLA", "");
	/** ORIGIN_AD_SCOUT=cherry,peaks,...: R2 takes one still per candidate run of each kind instead of recording. */
	static final String SCOUT = env("ORIGIN_AD_SCOUT", "");

	static Shot flight(ServerLevel level, BlockPos spawn, String name) {
		Shot pinned = pinned(level, name);
		if (pinned != null) {
			return pinned;
		}
		return switch (name) {
			case "mountain" -> mountain(level, spawn);
			case "water" -> waterRun(level, spawn);
			case "forest" -> canopy(level, spawn);
			case "cherry" -> dolly(level, spawn, name, Set.of("minecraft:cherry_grove"), 7, 9, false);
			case "meadow" -> dolly(level, spawn, name, Set.of("minecraft:meadow"), 6, 7, false);
			case "jungle" -> dolly(level, spawn, name, Set.of("minecraft:jungle", "minecraft:bamboo_jungle", "minecraft:sparse_jungle"), 6, 9, false);
			case "badlands" -> dolly(level, spawn, name, Set.of("minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands"), 12, 11, false);
			case "coast" -> dolly(level, spawn, name, Set.of("minecraft:beach", "minecraft:stony_shore"), 9, 6, true);
			case "snowy" -> dolly(level, spawn, name, Set.of("minecraft:snowy_slopes", "minecraft:grove"), 10, 10, false);
			default -> throw new IllegalStateException("unknown flight " + name);
		};
	}

	/**
	 * A slow straight dolly over a biome found from the spawn: of 48-block runs through it (every 16 blocks, 8
	 * headings), the one most inside the biome with the most relief ahead, light from the side and no hill in the lens.
	 * coast: the view ahead is half water, half land. Camera {@code lift} blocks over the tallest thing under the run.
	 */
	static Shot dolly(ServerLevel level, BlockPos near, String name, Set<String> biomes, double lift, double pitch, boolean coast) {
		BlockPos at = locate(level, near, biomes);
		load(level, at.getX(), at.getZ(), 160);
		double bestScore = -1e9;
		double[] best = null;
		for (int ox = -48; ox <= 48; ox += 16) {
			for (int oz = -48; oz <= 48; oz += 16) {
				double cx = at.getX() + ox, cz = at.getZ() + oz;
				for (int d = 0; d < 8; d++) {
					double a = Math.toRadians(d * 45);
					double ux = Math.cos(a), uz = Math.sin(a);
					double sx = cx - 24 * ux, sz = cz - 24 * uz, ex = cx + 24 * ux, ez = cz + 24 * uz;
					int in = 0, wet = 0, maxTop = -999;
					for (int i = 0; i <= 12; i++) {
						double x = lerp(sx, ex, i / 12.0), z = lerp(sz, ez, i / 12.0);
						for (int side = -8; side <= 8; side += 8) {
							double bx = x - uz * side, bz = z + ux * side;
							int gr = ground(level, bx, bz);
							maxTop = Math.max(maxTop, top(level, bx, bz));
							if (biomes.contains(biomeAt(level, (int) bx, gr, (int) bz))) {
								in++;
							}
							if (water(level, bx, bz)) {
								wet++;
							}
						}
					}
					double y = maxTop + lift;
					int lo = 999, hi = -999, aheadWet = 0, ahead = 0, wall = 0;
					for (int k = 8; k <= 80; k += 8) {
						for (int side = -32; side <= 32; side += 16) {
							double bx = ex + ux * k - uz * side, bz = ez + uz * k + ux * side;
							int gr = ground(level, bx, bz);
							lo = Math.min(lo, gr);
							hi = Math.max(hi, gr);
							ahead++;
							if (water(level, bx, bz)) {
								aheadWet++;
							}
							if (k <= 48 && Math.abs(side) <= 16 && top(level, bx, bz) > y - 2) {
								wall++;   // a hill right in the lens
							}
						}
					}
					for (int i = 0; i <= 12; i += 2) {   // a slope rising right beside the run fills half the frame
						double x = lerp(sx, ex, i / 12.0), z = lerp(sz, ez, i / 12.0);
						for (int side = -32; side <= 32; side += 8) {
							if (Math.abs(side) >= 16 && top(level, x - uz * side, z + ux * side) > y - 3) {
								wall++;
							}
						}
					}
					if (in < 26) {
						continue;   // two thirds of the run inside the biome, or it is another biome's shot
					}
					float yaw = yawTo(ux, uz);
					// the sun (west, yaw 90) beside or behind the camera: lit faces under a blue sky, not a lens full of haze
					double sideLight = Math.abs(Math.sin(Math.toRadians(yaw - 90))) + Math.max(0, -Math.cos(Math.toRadians(yaw - 90)));
					double wetShare = aheadWet / (double) ahead;
					double score = in * 4 + Math.min(60, hi - lo) * 2 + sideLight * 30 - wall * 25
							- (coast ? Math.abs(wetShare - 0.5) * 200 : wet * 15 + wetShare * 60);
					if (score > bestScore) {
						bestScore = score;
						best = new double[]{sx, sz, ex, ez, y};
					}
				}
			}
		}
		if (best == null) {
			throw new IllegalStateException("no clear " + name + " run near " + at.toShortString());
		}
		final double[] b = best;
		final float yaw = yawTo(b[2] - b[0], b[3] - b[1]);
		log(String.format(Locale.ROOT, "%s: %.0f,%.0f -> %.0f,%.0f at y=%.0f yaw %.0f (%s) score %.0f", name, b[0], b[1], b[2], b[3], b[4], yaw,
				biomeAt(level, (int) b[0], ground(level, b[0], b[1]), (int) b[1]), bestScore));
		return new Shot(name, u -> new double[]{lerp(b[0], b[2], u), b[4], lerp(b[1], b[3], u), yaw, (float) pitch}, 9);
	}

	/**
	 * ORIGIN_AD_RUN_<NAME>=sx,sz,ex,ez,y,pitch[,secs[,yawOffset[,yEnd]]]: a straight run picked by eye from the scout
	 * stills (still the seed's own terrain, nothing placed). The camera looks along the run (plus yawOffset degrees).
	 */
	/**
	 * The client really has the land around the camera: most chunks inside the render distance received (not just "every
	 * section that exists is meshed", which is also true right after a teleport, before any chunk arrives) and all meshed.
	 */
	static boolean landed(Minecraft mc) {
		if (mc.level == null || !mc.levelRenderer.hasRenderedAllSections()) {
			return false;
		}
		int rd = mc.options.getEffectiveRenderDistance();
		return mc.level.getChunkSource().getLoadedChunksCount() >= (int) (0.5 * Math.PI * rd * rd);
	}

	static Shot pinned(ServerLevel level, String name) {
		double[] r = coords("ORIGIN_AD_RUN_" + name.toUpperCase(Locale.ROOT));
		if (r == null) {
			return null;
		}
		return straight(level, name, r[0], r[1], r[2], r[3], r[4], r[5], r.length > 6 ? r[6] : 11, r.length > 7 ? r[7] : 0,
				r.length > 8 ? r[8] : r[4]);
	}

	static Shot straight(ServerLevel level, String name, double sx, double sz, double ex, double ez, double y, double pitch, double secs,
			double yawOff, double yEnd) {
		final float yaw = (float) (yawTo(ex - sx, ez - sz) + yawOff);
		log(String.format(Locale.ROOT, "%s: run %.0f,%.0f -> %.0f,%.0f at y=%.1f..%.1f yaw %.0f pitch %.0f (%s)", name, sx, sz, ex, ez, y, yEnd, yaw, pitch,
				biomeAt(level, (int) sx, ground(level, sx, sz), (int) sz)));
		return new Shot(name, u -> new double[]{lerp(sx, ex, u), lerp(y, yEnd, u), lerp(sz, ez, u), yaw, (float) pitch}, secs);
	}

	/** ORIGIN_AD_TIME_<NAME>: a flight's own time of day (the coast at sunset), else ORIGIN_AD_TIME. */
	static int timeOf(String name) {
		String base = name.contains("#") ? name.substring(0, name.indexOf('#')) : name;
		return Integer.parseInt(env("ORIGIN_AD_TIME_" + base.toUpperCase(Locale.ROOT), String.valueOf(TIME)));
	}

	static final Set<String> CHERRY = Set.of("minecraft:cherry_grove");
	static final Set<String> SNOWPEAKS = Set.of("minecraft:jagged_peaks", "minecraft:frozen_peaks", "minecraft:snowy_slopes");
	static final Set<String> BEACH = Set.of("minecraft:beach", "minecraft:snowy_beach");
	static final Set<String> OCEAN = Set.of("minecraft:ocean", "minecraft:deep_ocean", "minecraft:warm_ocean", "minecraft:lukewarm_ocean",
			"minecraft:deep_lukewarm_ocean");
	static final Set<String> RIVER = Set.of("minecraft:river");

	/**
	 * Scout: candidate straight runs for one kind of shot (cherry, peaks, coast, ocean, river), from several finds of
	 * the biome around the spawn, best first and at least 96 blocks apart. Each is {sx, sz, ex, ez, y, pitch, score}.
	 * Only a short list for the eye: the stills decide, then the pick is pinned with ORIGIN_AD_RUN_<NAME>.
	 */
	static List<double[]> scout(ServerLevel level, BlockPos spawn, String kind, int want) {
		Set<String> biomes = switch (kind) {
			case "cherry" -> CHERRY;
			case "peaks" -> SNOWPEAKS;
			case "coast" -> BEACH;
			case "ocean" -> OCEAN;
			case "river" -> RIVER;
			default -> throw new IllegalStateException("no scout for " + kind);
		};
		int sea = level.getSeaLevel();
		List<BlockPos> finds = new ArrayList<>();
		int[][] origins = {{0, 0}, {1800, 0}, {-1800, 0}, {0, 1800}, {0, -1800}, {1300, 1300}, {-1300, -1300}};
		for (int[] o : origins) {
			BlockPos at;
			try {
				at = locate(level, spawn.offset(o[0], 0, o[1]), biomes);
			} catch (IllegalStateException e) {
				continue;
			}
			boolean near = false;
			for (BlockPos f : finds) {
				near |= f.distSqr(at) < 256 * 256;
			}
			if (!near) {
				finds.add(at);
			}
		}
		List<double[]> all = new ArrayList<>();
		for (BlockPos at : finds) {
			for (int ox = -64; ox <= 64; ox += 16) {
				for (int oz = -64; oz <= 64; oz += 16) {
					for (int d = 0; d < 8; d++) {
						double[] c = rate(level, kind, biomes, sea, at.getX() + ox, at.getZ() + oz, Math.toRadians(d * 45));
						if (c != null) {
							all.add(c);
						}
					}
				}
			}
		}
		all.sort((a, b) -> Double.compare(b[6], a[6]));
		List<double[]> out = new ArrayList<>();
		for (double[] c : all) {
			boolean near = false;
			for (double[] o : out) {
				near |= Math.hypot((c[0] + c[2]) / 2 - (o[0] + o[2]) / 2, (c[1] + c[3]) / 2 - (o[1] + o[3]) / 2) < 96;
			}
			if (!near) {
				out.add(c);
				if (out.size() >= want) {
					break;
				}
			}
		}
		log("scout " + kind + ": " + finds.size() + " finds, " + all.size() + " runs rated, " + out.size() + " kept");
		return out;
	}

	/** One 48-block run through (cx, cz) at heading a, rated for its kind; null when it isn't that kind of shot at all. */
	static double[] rate(ServerLevel level, String kind, Set<String> biomes, int sea, double cx, double cz, double a) {
		double ux = Math.cos(a), uz = Math.sin(a);
		double sx = cx - 24 * ux, sz = cz - 24 * uz, ex = cx + 24 * ux, ez = cz + 24 * uz;
		int in = 0, n = 0, wetLine = 0, maxTop = -999;
		for (int i = 0; i <= 12; i++) {
			double x = lerp(sx, ex, i / 12.0), z = lerp(sz, ez, i / 12.0);
			if (water(level, x, z)) {
				wetLine++;
			}
			for (int side = -8; side <= 8; side += 8) {
				double bx = x - uz * side, bz = z + ux * side;
				int gr = ground(level, bx, bz);
				maxTop = Math.max(maxTop, top(level, bx, bz));
				n++;
				if (biomes.contains(biomeAt(level, (int) bx, gr, (int) bz))) {
					in++;
				}
			}
		}
		double inShare = in / (double) n;
		boolean wetKind = kind.equals("ocean") || kind.equals("river");
		if (inShare < (wetKind ? 0.5 : 0.6)) {
			return null;
		}
		if (wetKind && wetLine < 12) {
			return null;   // the camera runs over water the whole way
		}
		double y = switch (kind) {
			case "cherry" -> maxTop + 5;
			case "peaks" -> maxTop + 16;
			case "coast" -> Math.max(sea + 9, maxTop + 4);
			case "ocean" -> sea + 5;
			default -> sea + 3.2;   // river: low over the water
		};
		double pitch = switch (kind) {
			case "cherry" -> 13;
			case "peaks" -> 7;
			case "coast" -> 5;
			case "ocean" -> 2;
			default -> 4;
		};
		int lo = 999, hi = -999, ahead = 0, wetAhead = 0, leafy = 0, pink = 0, snow = 0, wall = 0;
		for (int k = 8; k <= 96; k += 8) {
			for (int side = -40; side <= 40; side += 10) {
				double bx = ex + ux * k - uz * side, bz = ez + uz * k + ux * side;
				int gr = ground(level, bx, bz), t = top(level, bx, bz);
				lo = Math.min(lo, gr);
				hi = Math.max(hi, gr);
				ahead++;
				if (water(level, bx, bz)) {
					wetAhead++;
				}
				if (t - gr >= 4) {
					leafy++;
				}
				var st = level.getBlockState(new BlockPos((int) Math.floor(bx), t - 1, (int) Math.floor(bz)));
				if (st.is(net.minecraft.world.level.block.Blocks.CHERRY_LEAVES) || st.is(net.minecraft.world.level.block.Blocks.PINK_PETALS)) {
					pink++;
				}
				if (st.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) || st.is(net.minecraft.world.level.block.Blocks.SNOW)
						|| st.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW) || st.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)) {
					snow++;
				}
				if (k <= 48 && Math.abs(side) <= 20 && t > y - 2) {
					wall++;   // something right in the lens
				}
			}
		}
		// banks: land rising right beside the camera fills half the frame
		int bank = 0, landL = 0, landR = 0, treesBeside = 0;
		for (int i = 0; i <= 12; i += 2) {
			double x = lerp(sx, ex, i / 12.0), z = lerp(sz, ez, i / 12.0);
			for (int side = -40; side <= 40; side += 8) {
				if (side == 0) {
					continue;
				}
				double bx = x - uz * side, bz = z + ux * side;
				int t = top(level, bx, bz), gr = ground(level, bx, bz);
				if (Math.abs(side) <= 16 && t > y + 2) {
					bank++;
				}
				if (!water(level, bx, bz)) {
					if (side < 0) {
						landL++;
					} else {
						landR++;
					}
				}
				if (t - gr >= 4) {
					treesBeside++;
				}
			}
		}
		int far = 0, farWet = 0;
		for (int k = 100; k <= 260; k += 20) {
			for (int side = -80; side <= 80; side += 20) {
				far++;
				if (water(level, ex + ux * k - uz * side, ez + uz * k + ux * side)) {
					farWet++;
				}
			}
		}
		double wa = wetAhead / (double) ahead, fw = farWet / (double) far;
		float yaw = yawTo(ux, uz);
		double sunAhead = Math.cos(Math.toRadians(yaw - 90));   // the low sun is in the west (yaw 90)
		double sideLight = Math.abs(Math.sin(Math.toRadians(yaw - 90))) + Math.max(0, -sunAhead);
		double relief = Math.min(70, hi - lo);
		double score = switch (kind) {
			case "cherry" -> pink * 5 + inShare * 60 + relief - wall * 20 - bank * 15 + sideLight * 20 - wetAhead * 2;
			case "peaks" -> snow * 2 + relief * 3 - wall * 25 - bank * 20 + sideLight * 40;
			case "coast" -> (fw < 0.75 ? -400 : 0) + fw * 100 - Math.abs(wa - 0.55) * 160 + Math.abs(landL - landR) * 3 - wall * 25 - bank * 20
					+ sunAhead * 25;
			case "ocean" -> fw * 60 + Math.min(wa, 0.97) * 40 + (fw > 0.6 && fw < 0.95 ? 60 : 0) - wall * 30 + sunAhead * 30;
			default -> leafy * 2 + treesBeside * 3 + wetLine * 4 + relief - bank * 25 - wall * 30 + sideLight * 20;   // river
		};
		return new double[]{sx, sz, ex, ez, y, pitch, score};
	}

	/** Moves the server player to the pose (chunks start loading there right away). */
	static void place(ServerPlayer p, double[] c) {
		p.teleportTo(p.serverLevel(), c[0], c[1], c[2], (float) c[3], (float) c[4]);
	}

	// ---------------------------------------------------------------- R2: three slow golden-hour flights

	static final class ClipLandscape extends Clip {
		private volatile List<Shot> shots;
		private int i = 0, step = 0;
		private long stepAt;
		private double[] cam;
		private double rehearseFrom = -1;
		// ORIGIN_AD_VANILLA=<flight>: after every flight, that one flight again with shaders off (a before/after pair)
		private int vanillaOf = -1;
		private boolean vanillaPass;

		@Override
		void options(Minecraft mc) {
			mc.options.renderDistance().set(32);
			mc.options.hideGui = true;
			mc.options.fov().set(64);
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			BlockPos spawn = level.getSharedSpawnPos();
			List<Shot> list = new ArrayList<>();
			if (!SCOUT.isEmpty()) {   // scout: one still per candidate run, nothing recorded
				int want = Integer.parseInt(env("ORIGIN_AD_SCOUT_N", "5"));
				// ORIGIN_AD_SCOUT=list + ORIGIN_AD_SCOUT_LIST=name:sx,sz,ex,ez,y,pitch;...: stills of runs already rated (no rating pass,
				// which loads thousands of chunks and ran an 8 GB heap out of memory with five kinds in one session)
				String[] given = SCOUT.equals("list") ? env("ORIGIN_AD_SCOUT_LIST", "").split(";") : new String[0];
				for (String g : given) {
					String[] nv = g.trim().split(":");
					String[] q = nv[1].split(",");
					double[] c = new double[q.length];
					for (int j = 0; j < q.length; j++) {
						c[j] = Double.parseDouble(q[j].trim());
					}
					list.add(straight(level, nv[0].trim(), c[0], c[1], c[2], c[3], c[4], c[5], 11, 0, c[4]));
				}
				for (String kind : given.length > 0 ? new String[0] : SCOUT.split(",")) {
					int k = 0;
					for (double[] c : scout(level, spawn, kind.trim(), want)) {
						String name = kind.trim() + "#" + (++k);
						log(String.format(Locale.ROOT, "scout %s score %.0f: RUN=%.0f,%.0f,%.0f,%.0f,%.1f,%.0f", name, c[6], c[0], c[1], c[2], c[3], c[4], c[5]));
						list.add(straight(level, name, c[0], c[1], c[2], c[3], c[4], c[5], 11, 0, c[4]));
					}
				}
				place(p, list.get(0).path().at(0.5));
				shots = list;
				return;
			}
			for (String f : FLIGHTS) {
				try {
					list.add(flight(level, spawn, f.trim()));
				} catch (IllegalStateException e) {
					log("flight " + f + " skipped: " + e.getMessage());
				}
			}
			for (int k = 0; k < list.size(); k++) {
				if (list.get(k).name().equals(VANILLA)) {
					vanillaOf = k;
				}
			}
			place(p, list.get(0).path().at(0));
			shots = list;
		}

		@Override
		boolean tick(Minecraft mc, long since) {
			if (shots == null) {
				return false;
			}
			long now = System.currentTimeMillis();
			Shot s = shots.get(i);
			switch (step) {
				case 0 -> {   // fly there (at the flight's own time of day), let the far land generate and mesh
					cam = s.path().at(SCOUT.isEmpty() ? 0 : 0.5);
					// ORIGIN_AD_PACK_<NAME>=<part of a file name>: this flight under a pack the in-game picker downloaded (R10),
					// switched on with the same IrisBridge.apply call Download makes. The reload stalls; landed() waits it out.
					String pk = env("ORIGIN_AD_PACK_" + s.name().toUpperCase(Locale.ROOT), "");
					if (!pk.isEmpty() && !vanillaPass) {
						String file = packFile(pk);
						if (!file.equals(com.origin.client.client.shaders.IrisBridge.currentPack())) {
							log(s.name() + ": shader pack " + file);
							com.origin.client.client.shaders.IrisBridge.apply(file);
						}
					}
					MinecraftServer server = mc.getSingleplayerServer();
					int time = timeOf(s.name());
					server.execute(() -> {
						cmd(server, "time set " + time);
						place(server.getPlayerList().getPlayers().get(0), cam);
					});
					stepAt = now;
					step = 1;
				}
				case 1 -> {
					if (!SCOUT.isEmpty()) {
						if (now - stepAt > 9_000 && landed(mc) || now - stepAt > 60_000) {
							still(mc, "scout_" + s.name().replace('#', '_'));
							log("scout still " + s.name() + " after " + (now - stepAt) / 1000 + " s");
							step = 0;
							if (++i >= shots.size()) {
								return true;
							}
						}
						return false;
					}
					if (!farReady(now)) {
						return false;
					}
					if (now - stepAt > 12_000 && landed(mc) || now - stepAt > 90_000) {
						log(s.name() + ": loaded after " + (now - stepAt) / 1000 + " s (pack " + com.origin.client.client.shaders.IrisBridge.currentPack()
								+ "); rehearsing the move");
						rehearseFrom = System.nanoTime();
						step = 2;
					}
				}
				case 2 -> {   // rehearsal at 2.5x: every chunk along the path gets built before the take
					if (rehearseFrom < 0) {
						stepAt = now;
						step = 3;
					}
				}
				case 3 -> {
					cam = s.path().at(0);
					if (now - stepAt > 6000 && mc.levelRenderer.hasRenderedAllSections() || now - stepAt > 40_000) {
						if (PROBE) {
							step = 10;
							stepAt = now;
							return false;
						}
						try {
							startTake(String.format(Locale.ROOT, "R2_%02d_%s%s", i + 1, s.name(), vanillaPass ? "_vanilla" : ""));
						} catch (java.io.IOException e) {
							throw new RuntimeException(e);
						}
						step = 4;
					}
				}
				case 4 -> {
					if (t() >= s.secs()) {
						stopTake();
						step = 0;
						if (vanillaPass) {
							return true;
						}
						if (++i >= shots.size()) {
							if (vanillaOf < 0) {
								return true;
							}
							// same path, same time of day, shaders off: Iris reloads, every section rebuilds before the take
							log("vanilla pass: " + shots.get(vanillaOf).name() + " with shaders off");
							com.origin.client.client.shaders.IrisBridge.apply(null);
							vanillaPass = true;
							i = vanillaOf;
						}
					}
				}
				case 10, 11, 12 -> {   // probe: three stills along the move
					double u = (step - 10) / 2.0;
					cam = s.path().at(u);
					if (now - stepAt > 2500) {
						still(mc, "probe_R2_" + s.name() + "_" + (step - 10));
						stepAt = now;
						if (++step > 12) {
							step = 0;
							if (++i >= shots.size()) {
								return true;
							}
						}
					}
				}
				default -> {
				}
			}
			return false;
		}

		@Override
		void frame(Minecraft mc) {
			if (shots == null || mc.player == null) {
				return;
			}
			Shot s = shots.get(i);
			if (rehearseFrom >= 0) {
				double u = (System.nanoTime() - rehearseFrom) / 1e9 / (s.secs() / 2.5);
				if (u >= 1) {
					rehearseFrom = -1;
					u = 1;
				}
				cam = s.path().at(u);
			} else if (takeFrom >= 0) {
				cam = s.path().at(Math.min(1, t() / s.secs()));
			}
			if (cam != null) {
				pose(mc.player, cam[0], cam[1], cam[2], (float) cam[3], (float) cam[4]);
			}
		}
	}

	// ---------------------------------------------------------------- a held view for the UI clips

	/** A scripted UI take: a world view held still (or slowly drifting) behind menus driven by timed steps. */
	abstract static class UiClip extends Clip {
		private volatile double[] view;
		private boolean shotsLoaded;
		private long loadedAt;
		private int done = 0;
		protected double length;
		protected double drift = 0;   // degrees per second of slow yaw drift behind the UI
		/** A camera move instead of the held view (V3 world clips): pose = path.at((t - pathFrom) / pathSecs), clamped. */
		protected volatile Path path;
		protected double pathSecs = 11, pathFrom = 0;
		private final List<Step> steps = new ArrayList<>();
		private double slip = 0;

		record Step(double at, java.util.function.Consumer<Minecraft> run) {
		}

		void at(double t, java.util.function.Consumer<Minecraft> run) {
			steps.add(new Step(t, run));
		}

		/** The fixed view (server thread): a pose on one of the scenery moves. */
		void view(double[] v) {
			view = v;
		}

		@Override
		boolean tick(Minecraft mc, long since) {
			long now = System.currentTimeMillis();
			if (view == null) {
				return false;
			}
			if (!shotsLoaded) {
				if (loadedAt == 0) {
					loadedAt = now;
				}
				if (now - loadedAt > warmup() && farReady(now) && (mc.levelRenderer.hasRenderedAllSections() || now - loadedAt > 60_000)) {
					shotsLoaded = true;
					try {
						startTake(CLIP);
					} catch (java.io.IOException e) {
						throw new RuntimeException(e);
					}
				}
				return false;
			}
			return t() >= length;
		}

		long warmup() {
			return 10_000;
		}

		@Override
		void frame(Minecraft mc) {
			if (path != null && holdCamera() && mc.player != null) {
				double[] c = path.at(Math.min(1, Math.max(0, (Math.max(0, t()) - pathFrom) / pathSecs)));
				pose(mc.player, c[0], c[1], c[2], (float) c[3], (float) c[4]);
			} else if (view != null && holdCamera() && mc.player != null) {
				double t = Math.max(0, t());
				pose(mc.player, view[0], view[1], view[2], (float) (view[3] + drift * t), (float) view[4]);
			}
			if (takeFrom < 0) {
				return;
			}
			double t = t();
			// One step per frame; a step that runs late (a hitch) pushes every later step back by as much, so a stall
			// never fires a glide and its click in the same frame.
			if (done < steps.size() && steps.get(done).at() + slip <= t) {
				slip += Math.max(0, t - (steps.get(done).at() + slip) - 0.034);
				steps.get(done++).run().accept(mc);
			}
			perFrame(mc, t);
		}

		boolean holdCamera() {
			return true;
		}

		void perFrame(Minecraft mc, double t) {
		}
	}

	/** Smooth pointer glide (window px) between two points over [t0, t1], applied every frame. */
	record Glide(double t0, double t1, double x0, double y0, double x1, double y1) {
		boolean apply(Minecraft mc, double t) {
			if (t < t0 || t > t1 + 0.05) {
				return false;
			}
			double u = Math.min(1, Math.max(0, (t - t0) / (t1 - t0)));
			u = u * u * (3 - 2 * u);
			mouseTo(mc, lerp(x0, x1, u), lerp(y0, y1, u));
			return true;
		}
	}

	// ---------------------------------------------------------------- R3: Download Shaders -> pick -> apply -> the world changes

	static final class ClipShaders extends UiClip {
		private final List<Glide> glides = new ArrayList<>();
		private double scrollFrom = -1, scrollTo, scrollAmt;
		private long doneAt = 0;
		private double appliedAt = -1;

		@Override
		void options(Minecraft mc) {
			mc.options.hideGui = true;
			length = 60;   // set to Done + 4.5 s once Done is clicked
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			Shot m = mountain(level, level.getSharedSpawnPos());
			double[] v = m.path().at(0.45);
			place(p, v);
			view(v);
			drift = 0.6;
			// Iris's own shader screen, as the O key opens it
			at(0.7, mc -> {
				com.origin.client.client.shaders.IrisBridge.openIrisScreen();
				log("opened " + mc.screen.getClass().getSimpleName());
			});
			at(1.2, mc -> {
				logWidgets(mc.screen);
				AbstractWidget b = widget(mc.screen, "Download Shaders");
				double s = mc.getWindow().getGuiScale();
				double[] at = b != null ? new double[]{(b.getX() + b.getWidth() / 2.0) * s, (b.getY() + b.getHeight() / 2.0) * s}
						: irisDownloadRow(mc);   // Iris 1.8+: its own row, which Origin points at the same browser
				glides.add(new Glide(1.2, 1.8, 1500, 950, at[0], at[1]));
			});
			at(2.0, mc -> click(mc));
			at(2.6, mc -> logHits(mc.screen));
			// browse the list: down, then back to the top
			at(2.8, mc -> {
				glides.add(new Glide(2.8, 3.3, mx(mc), my(mc), 960, 640));
				scroll(3.4, 5.0, -9);
			});
			at(5.3, mc -> scroll(5.3, 6.4, 9));
			// Complementary Reimagined: hover its Download button, click
			at(6.6, mc -> {
				logHits(mc.screen);
				glides.add(new Glide(6.6, 7.2, mx(mc), my(mc), PACK_X, PACK_Y));
			});
			at(7.5, mc -> click(mc));
		}

		static double PACK_X = Double.parseDouble(System.getenv().getOrDefault("ORIGIN_AD_PACK_X", "1306"));
		static double PACK_Y = Double.parseDouble(System.getenv().getOrDefault("ORIGIN_AD_PACK_Y", "426"));

		private void scroll(double t0, double t1, double total) {
			scrollFrom = t0;
			scrollTo = t1;
			scrollAmt = total;
		}

		private double lastScrollT = -1;

		@Override
		void perFrame(Minecraft mc, double t) {
			now = t;
			for (Glide g : glides) {
				g.apply(mc, t);
			}
			if (scrollFrom >= 0 && t >= scrollFrom) {
				double prev = Math.max(scrollFrom, lastScrollT);
				double n0 = Math.floor((prev - scrollFrom) / (scrollTo - scrollFrom) * Math.abs(scrollAmt));
				double n1 = Math.floor((Math.min(t, scrollTo) - scrollFrom) / (scrollTo - scrollFrom) * Math.abs(scrollAmt));
				for (double k = n0; k < n1; k++) {
					OriginDevAd.scroll(mc, Math.signum(scrollAmt));
				}
				lastScrollT = t;
				if (t >= scrollTo) {
					scrollFrom = -1;
					lastScrollT = -1;
				}
			}
			// After the click: the download runs for real; when it is in, back to Iris, select it, Apply, Done.
			var st = com.origin.client.client.shaders.ShaderDownloader.state("complementary-reimagined");
			if (t > 7.6 && doneAt == 0 && st.status() == com.origin.client.client.shaders.ShaderDownloader.Status.DONE) {
				doneAt = System.nanoTime();
				log("download done at " + String.format(Locale.ROOT, "%.2f", t) + " s: " + st.message());
				chain(1.0, m -> key(m, GLFW.GLFW_KEY_ESCAPE, true));
				chain(0.03, m -> key(m, GLFW.GLFW_KEY_ESCAPE, false));
				chain(0.6, m -> {
					logWidgets(m.screen);
					double[] e = irisEntry(m, "Complementary");
					glideNow(m, 0.5, e[0], e[1]);
				});
				chain(0.7, OriginDevAd.ClipShaders::click);
				chain(0.4, m -> glideTo(m, 0.45, widget(m.screen, "Apply")));
				chain(0.65, OriginDevAd.ClipShaders::click);
				chain(1.0, m -> glideTo(m, 0.45, widget(m.screen, "Done")));
				chain(0.65, m -> {
					click(m);
					appliedAt = now;
					length = now + 4.5;
				});
				length = 1e9;
			}
			if (t > 45 && doneAt == 0) {
				throw new IllegalStateException("Complementary download never finished: " + st.status() + " " + st.message());
			}
			if (late < later.size() && t >= lastRun + later.get(late).at()) {
				lastRun = t;
				later.get(late++).run().accept(mc);
			}
		}

		/** Links run in order, each {@code delay} s after the previous one actually ran (stall-proof). */
		private final List<Step> later = new ArrayList<>();
		private int late = 0;
		private double lastRun, now;

		private void chain(double delay, java.util.function.Consumer<Minecraft> run) {
			if (later.isEmpty()) {
				lastRun = now;
			}
			later.add(new Step(delay, run));
		}

		private void glideNow(Minecraft mc, double dur, double x, double y) {
			glides.add(new Glide(now, now + dur, mx(mc), my(mc), x, y));
		}

		private void glideTo(Minecraft mc, double dur, AbstractWidget b) {
			double s = mc.getWindow().getGuiScale();
			glideNow(mc, dur, (b.getX() + b.getWidth() / 2.0) * s, (b.getY() + b.getHeight() / 2.0) * s);
		}

		static void click(Minecraft mc) {
			button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, true);
			button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
			log(String.format(Locale.ROOT, "click at %.0f,%.0f on %s", mx(mc), my(mc), mc.screen == null ? "world" : mc.screen.getClass().getSimpleName()));
		}
	}

	static double mx(Minecraft mc) {
		return mc.mouseHandler.xpos();
	}

	static double my(Minecraft mc) {
		return mc.mouseHandler.ypos();
	}

	/** Window-px centre of the Iris pack list row whose pack name contains {@code name}. */
	static double[] irisEntry(Minecraft mc, String name) {
		return listRow(mc, name, e -> describe(e).contains(name));
	}

	/** Window-px centre of Iris's own big "Download Shaders" row (ShaderPackSelectionList.downloadButton). */
	static double[] irisDownloadRow(Minecraft mc) {
		return listRow(mc, "downloadButton", e -> {
			for (Class<?> k = e.getClass(); k != null; k = k.getSuperclass()) {
				if (k.getSimpleName().contains("Pinned")) {
					return true;
				}
			}
			return false;
		});
	}

	static double[] listRow(Minecraft mc, String name, java.util.function.Predicate<Object> match) {
		try {
			for (var ch : mc.screen.children()) {
				if (ch instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> list) {
					var m = net.minecraft.client.gui.components.AbstractSelectionList.class.getDeclaredMethod("getRowTop", int.class);
					m.setAccessible(true);
					var hf = net.minecraft.client.gui.components.AbstractSelectionList.class.getDeclaredField("itemHeight");
					hf.setAccessible(true);
					int ih = hf.getInt(list);
					for (int i = 0; i < list.children().size(); i++) {
						Object e = list.children().get(i);
						if (match.test(e)) {
							int top = (int) m.invoke(list, i);
							double s = mc.getWindow().getGuiScale();
							log("iris row " + i + " '" + describe(e) + "' top " + top);
							return new double[]{(list.getRowLeft() + list.getRowWidth() / 2.0) * s, (top + ih / 2.0 - 2) * s};
						}
					}
				}
			}
		} catch (Throwable t) {
			log("iris entry: " + t);
		}
		throw new IllegalStateException("no Iris row for " + name);
	}

	private static String describe(Object o) {
		StringBuilder b = new StringBuilder();
		for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
			for (var f : k.getDeclaredFields()) {
				if (f.getType() == String.class) {
					try {
						f.setAccessible(true);
						b.append(f.get(o)).append(' ');
					} catch (Throwable ignored) {
					}
				}
			}
		}
		return b.toString();
	}

	// ---------------------------------------------------------------- R4: Browse Packs

	static final class ClipPacks extends UiClip {
		private final List<Glide> glides = new ArrayList<>();
		private double scrollFrom = -1, scrollTo, scrollAmt, lastT = -1;
		static final double TAB_X = Double.parseDouble(System.getenv().getOrDefault("ORIGIN_AD_TAB_X", "0"));
		static final double TAB_Y = Double.parseDouble(System.getenv().getOrDefault("ORIGIN_AD_TAB_Y", "0"));

		@Override
		void options(Minecraft mc) {
			mc.options.hideGui = true;
			length = 10;
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			double[] v = canopy(level, level.getSharedSpawnPos()).path().at(0.3);
			place(p, v);
			view(v);
			drift = 0.5;
			at(0.4, mc -> {
				mc.setScreen(new net.minecraft.client.gui.screens.packs.PackSelectionScreen(mc.getResourcePackRepository(),
						r -> mc.options.updateResourcePacks(r), mc.getResourcePackDirectory(),
						net.minecraft.network.chat.Component.translatable("resourcePack.title")));
			});
			at(0.8, mc -> {
				logWidgets(mc.screen);
				AbstractWidget b = widget(mc.screen, "Browse Packs");
				double s = mc.getWindow().getGuiScale();
				glides.add(new Glide(0.8, 1.3, 1500, 900, (b.getX() + b.getWidth() / 2.0) * s, (b.getY() + b.getHeight() / 2.0) * s));
			});
			at(1.5, ClipShaders::click);
			at(2.2, mc -> logHits(mc.screen));
			at(2.3, mc -> {
				if (TAB_X > 0) {
					glides.add(new Glide(2.3, 2.8, mx(mc), my(mc), TAB_X, TAB_Y));
				}
			});
			at(3.0, mc -> {
				if (TAB_X > 0) {
					ClipShaders.click(mc);
				}
			});
			at(4.0, mc -> {
				logHits(mc.screen);
				glides.add(new Glide(4.0, 4.4, mx(mc), my(mc), 960, 640));
				scrollFrom = 4.6;
				scrollTo = 8.6;
				scrollAmt = -10;
			});
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			for (Glide g : glides) {
				g.apply(mc, t);
			}
			if (scrollFrom >= 0 && t >= scrollFrom) {
				double prev = Math.max(scrollFrom, lastT);
				double n0 = Math.floor((prev - scrollFrom) / (scrollTo - scrollFrom) * Math.abs(scrollAmt));
				double n1 = Math.floor((Math.min(t, scrollTo) - scrollFrom) / (scrollTo - scrollFrom) * Math.abs(scrollAmt));
				for (double k = n0; k < n1; k++) {
					OriginDevAd.scroll(mc, Math.signum(scrollAmt));
				}
				lastT = t;
				if (t >= scrollTo) {
					scrollFrom = -1;
				}
			}
		}
	}

	// ---------------------------------------------------------------- shared in-world HUD set-up

	/** Origin HUD modules for the gameplay clips: what a PvP player turns on. */
	static void hud(boolean fps, boolean pvp) {
		Mods.setOn("fps", fps);
		Mods.setOn("keystrokes", pvp);
		Mods.setOn("cps", pvp);
		Mods.setOn("combo", pvp);
		Mods.setOn("armorhud", pvp);
	}

	/** A kit that reads as a PvP player's (armor shows in Armor Status). */
	static void kit(MinecraftServer server) {
		cmd(server, "clear @a");
		String ench = "[minecraft:enchantments={levels:{\"minecraft:protection\":4,\"minecraft:unbreaking\":3}}]";
		cmd(server, "item replace entity @a armor.head with minecraft:diamond_helmet" + ench);
		cmd(server, "item replace entity @a armor.chest with minecraft:diamond_chestplate" + ench);
		cmd(server, "item replace entity @a armor.legs with minecraft:diamond_leggings" + ench);
		cmd(server, "item replace entity @a armor.feet with minecraft:diamond_boots" + ench);
		cmd(server, "item replace entity @a hotbar.0 with minecraft:diamond_sword[minecraft:enchantments={levels:{\"minecraft:sharpness\":3}}]");
		cmd(server, "item replace entity @a hotbar.1 with minecraft:bow");
		cmd(server, "item replace entity @a hotbar.2 with minecraft:golden_apple 6");
		cmd(server, "item replace entity @a hotbar.3 with minecraft:cobblestone 64");
		cmd(server, "item replace entity @a hotbar.8 with minecraft:cooked_beef 32");
		cmd(server, "item replace entity @a inventory.0 with minecraft:arrow 32");
	}

	// ---------------------------------------------------------------- the arena (R6, R7, R8): built in the sky, mountains behind

	static double[] arenaCenter;   // floor centre (x, floorY + 1, z)

	static void arena(MinecraftServer server, ServerLevel level, ServerPlayer p) {
		BlockPos at = locate(level, level.getSharedSpawnPos(), PEAKS);
		load(level, at.getX(), at.getZ() - 160, 48);
		int cx = at.getX(), cz = at.getZ() - 160;
		int ground = 0;
		for (int dx = -14; dx <= 14; dx += 2) {
			for (int dz = -14; dz <= 14; dz += 2) {
				ground = Math.max(ground, top(level, cx + dx, cz + dz));
			}
		}
		int fy = Math.max(ground + 8, level.getSeaLevel() + 40);
		int r = 11;
		cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", cx - r - 2, fy + 1, cz - r - 2, cx + r + 2, fy + 8, cz + r + 2));
		cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:polished_deepslate", cx - r, fy, cz - r, cx + r, fy, cz + r));
		cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:deepslate_tiles", cx - r, fy - 1, cz - r, cx + r, fy - 1, cz + r));
		cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:polished_blackstone_bricks", cx - r - 1, fy, cz - r - 1, cx + r + 1, fy + 1, cz + r + 1));
		cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:polished_deepslate", cx - r, fy, cz - r, cx + r, fy, cz + r));
		cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", cx - r, fy + 1, cz - r, cx + r, fy + 1, cz + r));
		cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:polished_blackstone_wall", cx - r - 1, fy + 2, cz - r - 1, cx + r + 1, fy + 2, cz + r + 1, "hollow"));
		cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", cx - r, fy + 2, cz - r, cx + r, fy + 2, cz + r));
		for (int[] c : new int[][]{{-r - 1, -r - 1}, {r + 1, -r - 1}, {-r - 1, r + 1}, {r + 1, r + 1}}) {
			cmd(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:chiseled_polished_blackstone", cx + c[0], fy + 1, cz + c[1], cx + c[0], fy + 3, cz + c[1]));
			cmd(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:lantern", cx + c[0], fy + 4, cz + c[1]));
		}
		arenaCenter = new double[]{cx + 0.5, fy + 1, cz + 0.5};
		log(String.format(Locale.ROOT, "arena: floor y=%d centre %d %d (summit side to the south)", fy, cx, cz));
	}

	// ---------------------------------------------------------------- R5: FPS counter, then FPS Coach

	static final class ClipFps extends UiClip {
		private final List<Glide> glides = new ArrayList<>();
		static final double COACH_X = Double.parseDouble(System.getenv().getOrDefault("ORIGIN_AD_COACH_X", "1331"));   // TRY IT on its 3rd tip (clouds)
		static final double COACH_Y = Double.parseDouble(System.getenv().getOrDefault("ORIGIN_AD_COACH_Y", "634"));

		@Override
		void options(Minecraft mc) {
			length = 15;
			hud(false, false);
			mc.options.hideGui = true;   // rev 3: F1-clean behind the Coach (the FPS readout comes from R9)
		}

		@Override
		long warmup() {
			return 45_000;   // FPS Coach ignores the first 30 s in a world, then averages the last 10 s of play
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");   // nothing in the hands
			cmd(server, "clear @a");
			double[] v = canopy(level, level.getSharedSpawnPos()).path().at(0.2);
			place(p, v);
			view(v);
			drift = 1.6;
			at(6.6, mc -> mc.setScreen(new com.origin.client.client.gui.FpsCoachScreen(null)));   // what its K key opens
			at(7.4, mc -> {
				logHits(mc.screen);
				if (COACH_X > 0) {
					glides.add(new Glide(7.4, 7.9, 1600, 950, COACH_X, COACH_Y));
				}
			});
			at(8.1, mc -> {
				if (COACH_X > 0) {
					ClipShaders.click(mc);
				}
			});
			at(9.5, mc -> logHits(mc.screen));
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			mc.player.getAbilities().flying = true;
			for (Glide g : glides) {
				g.apply(mc, t);
			}
		}
	}

	// ---------------------------------------------------------------- R6: mod menu, then the HUD editor (drag Key Strokes, CPS)

	static final class ClipHud extends UiClip {
		private final List<Glide> glides = new ArrayList<>();
		static final double[] EDIT = coords("ORIGIN_AD_EDIT");      // the menu's HUD editor button (window px)
		static final double[] KEYS = coords("ORIGIN_AD_KEYS");      // Key Strokes: grab point, drop point
		static final double[] CPS = coords("ORIGIN_AD_CPS");        // CPS: grab point, drop point

		/** ORIGIN_AD_MENU_ONLY=1 (rev 3): just the mod menu over an F1-clean real landscape, then a slow scroll of the list. */
		static final boolean MENU_ONLY = "1".equals(System.getenv("ORIGIN_AD_MENU_ONLY"));

		@Override
		void options(Minecraft mc) {
			length = MENU_ONLY ? 9 : 12.5;
			if (MENU_ONLY) {
				mc.options.hideGui = true;   // F1: no HUD draws, so the cards can show a real setup (capture.py R6_ON)
				for (String id : new String[]{"fps", "cps", "armorhud"}) {   // the ones baseOptions switched off
					Mods.setOn(id, true);
				}
			} else {
				hud(true, true);
			}
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			if (MENU_ONLY) {
				cmd(server, "gamemode spectator @a");
				cmd(server, "clear @a");
				double[] v = canopy(level, level.getSharedSpawnPos()).path().at(0.6);
				place(p, v);
				view(v);
				drift = 0.5;
				at(0.5, mc -> mc.setScreen(new com.origin.client.client.gui.OriginModMenuScreen()));
				at(1.2, mc -> logHits(mc.screen));
				at(1.4, mc -> glides.add(new Glide(1.4, 2.0, 1500, 900, 960, 600)));
				for (int k = 0; k < 8; k++) {   // one notch every 0.35 s from 4.5 s: the list rolls past slowly
					at(4.5 + k * 0.35, mc -> scroll(mc, -1));
				}
				return;
			}
			cmd(server, "gamemode survival @a");
			kit(server);
			arena(server, level, p);
			double[] c = arenaCenter;
			double[] v = {c[0], c[1], c[2] + 6, 180, 6};
			place(p, v);
			view(v);
			at(0.5, mc -> mc.setScreen(new com.origin.client.client.gui.OriginModMenuScreen()));
			at(1.2, mc -> logHits(mc.screen));
			at(1.4, mc -> glides.add(new Glide(1.4, 2.4, 1500, 900, 1160, 640)));
			at(3.0, mc -> {
				logHits(mc.screen);
				if (EDIT != null) {
					glides.add(new Glide(3.0, 3.6, mx(mc), my(mc), EDIT[0], EDIT[1]));
				}
			});
			at(3.8, mc -> {
				if (EDIT != null) {
					ClipShaders.click(mc);
				} else {
					mc.setScreen(new com.origin.client.client.hud.HudEditorScreen(false, mc.screen));
				}
			});
			at(4.6, mc -> {
				logHits(mc.screen);
				logWidgets(mc.screen);
			});
			if (KEYS != null) {
				drag(6.6, 7.8, KEYS);
			}
			if (CPS != null) {
				drag(8.4, 9.5, CPS);
			}
		}

		private void drag(double t0, double t1, double[] g) {
			at(t0 - 0.5, mc -> glides.add(new Glide(t0 - 0.5, t0 - 0.05, mx(mc), my(mc), g[0], g[1])));
			at(t0, mc -> button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, true));
			glides.add(new Glide(t0 + 0.05, t1, g[0], g[1], g[2], g[3]));
			at(t1 + 0.1, mc -> button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, false));
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			for (Glide g : glides) {
				g.apply(mc, t);
			}
		}
	}

	static double[] coords(String envKey) {
		String v = System.getenv(envKey);
		if (v == null || v.isBlank()) {
			return null;
		}
		String[] p = v.split(",");
		double[] d = new double[p.length];
		for (int i = 0; i < p.length; i++) {
			d[i] = Double.parseDouble(p[i].trim());
		}
		return d;
	}

	// ---------------------------------------------------------------- R7: a friend request and a party invite pop up in game

	static final class ClipSocial extends UiClip {
		/** Made-up players (dev data, like --render-shots): nobody real. */
		static final String FRIEND = "Mossbrook", FRIEND_ID = "5b7e2c1d9a4f4e8b8c3d6f1a2b9e7c40";
		static final String HOST = "TidewaterJay", HOST_ID = "a3c9e14f7b2d4d6e9f0a8b5c1e7d3f62";

		@Override
		void options(Minecraft mc) {
			length = 10;
			hud(true, true);
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode survival @a");
			kit(server);
			arena(server, level, p);
			double[] c = arenaCenter;
			double[] v = {c[0] - 4, c[1], c[2] + 5, 200, 4};
			place(p, v);
			view(v);
			drift = -1.2;
			at(1.5, mc -> com.origin.client.client.social.SocialToast.request(mc, FRIEND_ID, FRIEND));
			at(2.1, mc -> com.origin.client.client.social.SocialToast.invite(mc, HOST_ID, HOST));
		}
	}

	// ---------------------------------------------------------------- R9: the HUD modules alone, keyed, plus the two toasts

	/**
	 * Rev 3's floating HUD layers: the real modules (positions and scale from capture.py's prepare(), no backgrounds)
	 * drawn over one flat key colour (DevAdGuiMixin) and recorded lossless, so the edit keys each one out exactly.
	 * Spectator (no hand, hotbar, health or crosshair), armor on for Armor Status, two effects for Potion Effects,
	 * scripted left clicks for CPS, then the friend-request and party-invite toasts (made-up players).
	 */
	static final class ClipHudKeyed extends UiClip {
		static final String[] ON = {"fps", "cps", "coords", "armorhud", "potionhud", "clock", "memory"};
		static final String[] OFF = {"keystrokes", "combo", "reach", "ping", "saturation", "packdisplay", "itemcounter",
				"directionhud", "daycounter", "stopwatch", "serveraddress", "scoreboard", "minimap", "crosshair", "togglesprint", "cooldowns",
				"bossbar", "waypoints", "titles", "teamview"};
		private final Random rnd = new Random(11);
		private double nextClick = 0.3;

		@Override
		void options(Minecraft mc) {
			length = 12;
			mc.options.hideGui = false;   // the modules only draw with the HUD on; spectator hides everything vanilla
			mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FAST);   // no vignette over the key colour
			for (String id : OFF) {
				Mods.setOn(id, false);
			}
			for (String id : ON) {
				Mods.setOn(id, true);
			}
			keyBackground = 0xFFFF00FF;
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			String ench = "[minecraft:enchantments={levels:{\"minecraft:protection\":4,\"minecraft:unbreaking\":3}}]";
			cmd(server, "item replace entity @a armor.head with minecraft:diamond_helmet" + ench);
			cmd(server, "item replace entity @a armor.chest with minecraft:diamond_chestplate" + ench);
			cmd(server, "item replace entity @a armor.legs with minecraft:diamond_leggings" + ench);
			cmd(server, "item replace entity @a armor.feet with minecraft:diamond_boots" + ench);
			cmd(server, "effect give @a minecraft:speed 900 1 true");
			cmd(server, "effect give @a minecraft:night_vision 900 0 true");
			double[] v = canopy(level, level.getSharedSpawnPos()).path().at(0.5);
			place(p, v);
			view(v);
			drift = 2.0;   // the world keeps moving under the key colour, so the FPS readout is a real one
			at(4.0, mc -> com.origin.client.client.social.SocialToast.request(mc, ClipSocial.FRIEND_ID, ClipSocial.FRIEND));
			at(5.2, mc -> com.origin.client.client.social.SocialToast.invite(mc, ClipSocial.HOST_ID, ClipSocial.HOST));
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			// CPS: real clicks through the real input path, 9-12 a second (spectator: a click in the air does nothing)
			if (t >= nextClick && t < length - 0.5) {
				button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, true);
				button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
				nextClick = t + 1.0 / (9 + rnd.nextDouble() * 3);
			}
		}
	}

	// ---------------------------------------------------------------- R8: an armored duel in the arena

	static final class ClipPvp extends UiClip {
		private final Random rnd = new Random(7);
		private double nextClick, releaseClick = -1, nextStrafe, nextJump, jumpUp = -1, backOff = -1;
		private int strafeKey = 0;
		private boolean wDown, sDown, sprintDown;
		private float yaw, pitch;
		private double lastT = -1;
		private int round = 0;
		private double healAt = 0;
		private double spawnAt = 0.2;
		private static final String[][] FOES = {
				{"minecraft:zombie", "diamond", "minecraft:iron_sword"},
				{"minecraft:husk", "iron", "minecraft:iron_axe"},
				{"minecraft:zombie", "netherite", "minecraft:diamond_sword"},
				{"minecraft:piglin_brute", "golden", "minecraft:golden_axe"},
				{"minecraft:zombie", "chainmail", "minecraft:stone_sword"},
				{"minecraft:husk", "diamond", "minecraft:diamond_axe"},
		};

		@Override
		void options(Minecraft mc) {
			length = 72;
			hud(true, true);
			mc.options.bobView().set(true);
		}

		@Override
		boolean holdCamera() {
			return takeFrom < 0;   // during the fight the player moves for real
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode survival @a");
			cmd(server, "difficulty normal");
			kit(server);
			cmd(server, "effect clear @a");
			arena(server, level, p);
			cmd(server, "kill @e[type=!minecraft:player,distance=..60]");
			double[] c = arenaCenter;
			double[] v = {c[0], c[1], c[2] + 7, 180, 4};
			place(p, v);
			view(v);
			yaw = 180;
			pitch = 4;
		}

		private void spawn(Minecraft mc) {
			String[] f = FOES[round++ % FOES.length];
			MinecraftServer server = mc.getSingleplayerServer();
			double[] c = arenaCenter;
			LocalPlayer me = mc.player;
			double ang = Math.toRadians(yaw) + (rnd.nextBoolean() ? 0.4 : -0.4);
			double x = me.getX() - Math.sin(ang) * 7, z = me.getZ() + Math.cos(ang) * 7;
			x = Math.max(c[0] - 9, Math.min(c[0] + 9, x));
			z = Math.max(c[2] - 9, Math.min(c[2] + 9, z));
			String m = f[1];
			String nbt = String.format(Locale.ROOT, "{PersistenceRequired:1b,CanPickUpLoot:0b,IsImmuneToZombification:1b,Health:10f,"
					+ "attributes:[{id:\"minecraft:generic.max_health\",base:10d},{id:\"minecraft:generic.attack_damage\",base:1.5d},"
					+ "{id:\"minecraft:generic.movement_speed\",base:0.3d}],"
					+ "ArmorItems:[{id:\"minecraft:%s_boots\",count:1},{id:\"minecraft:%s_leggings\",count:1},{id:\"minecraft:%s_chestplate\",count:1},{id:\"minecraft:%s_helmet\",count:1}],"
					+ "HandItems:[{id:\"%s\",count:1},{}],ArmorDropChances:[0f,0f,0f,0f],HandDropChances:[0f,0f]}", m, m, m, m, f[2]);
			String cmd = String.format(Locale.ROOT, "summon %s %.1f %.1f %.1f %s", f[0], x, c[1], z, nbt);
			server.execute(() -> cmd(server, cmd));
			log("round " + round + ": " + f[0] + " in " + m);
		}

		private LivingEntity foe(Minecraft mc) {
			LivingEntity best = null;
			double bd = 1e9;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof LivingEntity le && e instanceof Enemy && le.isAlive() && le.getHealth() > 0) {
					double d = e.distanceToSqr(mc.player);
					if (d < bd && d < 30 * 30) {
						bd = d;
						best = le;
					}
				}
			}
			return best;
		}

		private void hold(Minecraft mc, int key, boolean want, boolean is) {
			if (want != is) {
				key(mc, key, want);
			}
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			double dt = lastT < 0 ? 0 : Math.min(0.1, t - lastT);
			lastT = t;
			LocalPlayer me = mc.player;
			if (me == null || mc.screen != null) {
				return;
			}
			if (me.getHealth() < 8 && t > healAt) {
				healAt = t + 1;
				MinecraftServer server = mc.getSingleplayerServer();
				server.execute(() -> cmd(server, "effect give @a minecraft:instant_health 1 1 true"));
			}
			LivingEntity foe = foe(mc);
			if (foe == null) {
				hold(mc, GLFW.GLFW_KEY_W, false, wDown);
				wDown = false;
				hold(mc, GLFW.GLFW_KEY_LEFT_CONTROL, false, sprintDown);
				sprintDown = false;
				if (strafeKey != 0) {
					key(mc, strafeKey, false);
					strafeKey = 0;
				}
				if (t >= spawnAt && t < length - 4) {
					spawn(mc);
					spawnAt = t + 1.6;
				}
				// look around the arena while the next one comes in
				yaw += (float) (dt * 25);
				pitch += (float) ((4 - pitch) * Math.min(1, dt * 4));
				me.setYRot(yaw);
				me.setXRot(pitch);
				return;
			}
			spawnAt = t + 1.2;
			// aim: a critically damped follow of the foe's chest, with a small human wobble
			Vec3 eye = me.getEyePosition();
			Vec3 tgt = foe.position().add(0, foe.getBbHeight() * 0.62, 0);
			double dx = tgt.x - eye.x, dy = tgt.y - eye.y, dz = tgt.z - eye.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			float wantYaw = yawTo(dx, dz) + (float) (Math.sin(t * 2.3) * 2.5);
			float wantPitch = pitchTo(dy, flat) + (float) (Math.sin(t * 1.7) * 1.5);
			float k = (float) (1 - Math.exp(-dt * 14));
			yaw += net.minecraft.util.Mth.wrapDegrees(wantYaw - yaw) * k;
			pitch += (wantPitch - pitch) * k;
			me.setYRot(yaw);
			me.setXRot(pitch);
			double dist = Math.sqrt(flat * flat + dy * dy);
			// move: run in sprinting, strafe while trading, back off when it gets too close
			boolean back = t < backOff;
			if (dist < 1.4 && !back) {
				backOff = t + 0.25 + rnd.nextDouble() * 0.2;
				back = true;
			}
			hold(mc, GLFW.GLFW_KEY_W, !back, wDown);
			wDown = !back;
			hold(mc, GLFW.GLFW_KEY_S, back, sDown);
			sDown = back;
			hold(mc, GLFW.GLFW_KEY_LEFT_CONTROL, !back, sprintDown);
			sprintDown = !back;
			if (dist < 4.5 && t >= nextStrafe) {
				if (strafeKey != 0) {
					key(mc, strafeKey, false);
				}
				strafeKey = strafeKey == GLFW.GLFW_KEY_A ? GLFW.GLFW_KEY_D : GLFW.GLFW_KEY_A;
				key(mc, strafeKey, true);
				nextStrafe = t + 0.6 + rnd.nextDouble() * 0.7;
			} else if (dist >= 4.5 && strafeKey != 0) {
				key(mc, strafeKey, false);
				strafeKey = 0;
			}
			if (dist < 3.4 && t >= nextJump && me.onGround()) {
				key(mc, GLFW.GLFW_KEY_SPACE, true);
				jumpUp = t + 0.12;
				nextJump = t + 1.4 + rnd.nextDouble() * 2.2;
			}
			if (jumpUp > 0 && t >= jumpUp) {
				key(mc, GLFW.GLFW_KEY_SPACE, false);
				jumpUp = -1;
			}
			// click: 9-13 CPS while in reach, like a player trading hits
			if (releaseClick > 0 && t >= releaseClick) {
				button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
				releaseClick = -1;
			}
			if (dist < 3.6 && t >= nextClick && releaseClick < 0) {
				button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, true);
				releaseClick = t + 0.03 + rnd.nextDouble() * 0.02;
				nextClick = t + 1.0 / (9 + rnd.nextDouble() * 4);
			}
		}

		@Override
		boolean tick(Minecraft mc, long since) {
			boolean end = super.tick(mc, since);
			if (end) {
				for (int key : new int[]{GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_SPACE}) {
					key(mc, key, false);
				}
			}
			return end;
		}
	}

	// ---------------------------------------------------------------- far terrain gate, pack files, sidecars

	private static long farLogAt = 0;

	/**
	 * ORIGIN_AD_FAR_GATE=<file>: capture.py creates the file once Distant Horizons has generated the land around the
	 * camera and settled (it watches DH's progress lines); nothing records before that. No env = no far terrain = ready.
	 */
	static boolean farReady(long now) {
		String g = env("ORIGIN_AD_FAR_GATE", "");
		if (g.isEmpty() || new File(g).exists()) {
			return true;
		}
		if (now - farLogAt > 30_000) {
			farLogAt = now;
			log("far wait: far terrain not settled yet (gate " + g + ")");
		}
		return false;
	}

	/** The shaderpacks/ file whose name contains {@code part} (case-insensitive). */
	static String packFile(String part) {
		for (String f : com.origin.client.client.shaders.IrisBridge.listPacks()) {
			if (f.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT))) {
				return f;
			}
		}
		throw new IllegalStateException("no shader pack matching '" + part + "' in shaderpacks/ (run R10 first): "
				+ com.origin.client.client.shaders.IrisBridge.listPacks());
	}

	/** A small JSON sidecar next to the take (the edit reads click times and measured numbers from it). */
	static void sidecar(String name, String json) {
		try {
			java.nio.file.Files.writeString(new File(DIR, name + ".json").toPath(), json);
			log("wrote " + name + ".json");
		} catch (java.io.IOException e) {
			log("sidecar " + name + " failed: " + e);
		}
	}

	// ---------------------------------------------------------------- R10 (rev 4): the shader picker, three real downloads

	/**
	 * Shaders already on (Complementary), Origin's Download Shaders browser over the hero view; the pointer clicks
	 * Download on three packs, ORIGIN_AD_PICK_GAP s apart. Each Download is real: Modrinth, SHA-512, then the pack is
	 * switched on (ShaderDownloader.use). The packs stay in shaderpacks/ for the R2 flights (ORIGIN_AD_PACK_<NAME>).
	 * Writes R10.json: each click's take second, when its download finished and when Iris switched to it.
	 */
	static final class ClipPicker extends UiClip {
		private final List<Glide> glides = new ArrayList<>();
		static final String[] PICKS = env("ORIGIN_AD_PICKS", "bsl-shaders@594,photon-shader@678,solas-shader@762").split(",");
		static final double PX = Double.parseDouble(env("ORIGIN_AD_PICK_X", "1306"));
		static final double FIRST = 2.6, GAP = Double.parseDouble(env("ORIGIN_AD_PICK_GAP", "7"));
		private final double[] clickT = new double[PICKS.length], doneT = new double[PICKS.length], onT = new double[PICKS.length];
		private final String[] files = new String[PICKS.length];
		private boolean finished;

		@Override
		void options(Minecraft mc) {
			mc.options.hideGui = true;
			length = 1e9;   // set once the last pack is in and switched on
			java.util.Arrays.fill(clickT, -1);
			java.util.Arrays.fill(doneT, -1);
			java.util.Arrays.fill(onT, -1);
		}

		static String slug(int k) {
			return PICKS[k].split("@")[0].trim();
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			Shot h = pinned(level, "hero");
			double[] v = h != null ? h.path().at(0.35) : mountain(level, level.getSharedSpawnPos()).path().at(0.45);
			place(p, v);
			view(v);
			drift = 0.4;
			at(0.6, mc -> mc.setScreen(new com.origin.client.client.shaders.ShaderBrowserScreen(null)));
			at(1.4, mc -> logHits(mc.screen));
			for (int k = 0; k < PICKS.length; k++) {
				final int kk = k;
				final double y1080 = Double.parseDouble(PICKS[k].split("@")[1].trim());
				double c = FIRST + k * GAP;
				at(c - 0.75, mc -> {
					double t = t();
					glides.add(new Glide(t, t + 0.55, mx(mc), my(mc), PX * k1080(mc), y1080 * k1080(mc)));
				});
				at(c, mc -> {
					logHits(mc.screen);
					mouseTo(mc, PX * k1080(mc), y1080 * k1080(mc));   // a pack switch stalls the game ~2 s; a glide that fell inside the stall never ran
					ClipShaders.click(mc);
					clickT[kk] = t();
					log(String.format(Locale.ROOT, "pick %d %s clicked at %.3f s: %s", kk, slug(kk), clickT[kk],
							com.origin.client.client.shaders.ShaderDownloader.state(slug(kk))));
				});
			}
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			for (Glide g : glides) {
				g.apply(mc, t);
			}
			String cur = com.origin.client.client.shaders.IrisBridge.currentPack();
			boolean all = true;
			for (int k = 0; k < PICKS.length; k++) {
				if (clickT[k] < 0) {
					all = false;
					continue;
				}
				var st = com.origin.client.client.shaders.ShaderDownloader.state(slug(k));
				if (doneT[k] < 0 && st.status() == com.origin.client.client.shaders.ShaderDownloader.Status.DONE) {
					doneT[k] = t;
					files[k] = st.message();
					log(String.format(Locale.ROOT, "pick %d %s downloaded at %.3f s: %s", k, slug(k), t, files[k]));
				}
				if (st.status() == com.origin.client.client.shaders.ShaderDownloader.Status.ERROR) {
					throw new IllegalStateException("download " + slug(k) + " failed: " + st.message());
				}
				if (files[k] != null && onT[k] < 0 && files[k].equals(cur)) {
					onT[k] = t;
					log(String.format(Locale.ROOT, "pick %d %s switched on at %.3f s", k, slug(k), t));
				}
				all &= onT[k] >= 0 || (doneT[k] >= 0 && k < PICKS.length - 1);
			}
			if (all && !finished) {
				finished = true;
				length = t + 2.5;
				StringBuilder b = new StringBuilder("{\n  \"picks\": [\n");
				for (int k = 0; k < PICKS.length; k++) {
					b.append(String.format(Locale.ROOT,
							"    {\"slug\": \"%s\", \"file\": \"%s\", \"y\": %.1f, \"x\": %.1f, \"click\": %.3f, \"downloaded\": %.3f, \"on\": %.3f}%s\n",
							slug(k), files[k], Double.parseDouble(PICKS[k].split("@")[1].trim()) * k1080(mc), PX * k1080(mc), clickT[k], doneT[k],
							onT[k], k < PICKS.length - 1 ? "," : ""));
				}
				b.append("  ]\n}\n");
				sidecar("R10", b.toString());
				log("all picks in: " + b.toString().replace('\n', ' '));
			}
			if (t > FIRST + GAP * PICKS.length + 90 && !finished) {
				throw new IllegalStateException("picks never finished");
			}
		}
	}

	// ---------------------------------------------------------------- R11 (rev 4): FPS Coach TRY IT over far terrain, keyed FPS

	/**
	 * At the far flight's first pose (Distant Horizons on, Complementary on, render distance 32), the world under a flat
	 * key colour (DevAdGuiMixin) so the real FPS module (big, no background) can be keyed. FPS Coach opens, the pointer
	 * clicks TRY IT on ORIGIN_AD_COACH_TIP (default renderdistance); the Coach's own A/B test runs (before, settle,
	 * after, the camera held still); then the Coach reopens on its result card. R11.json: the click second, the
	 * Coach's measured before/after, and the FPS readout every half second.
	 */
	static final class ClipCoach extends UiClip {
		private final List<Glide> glides = new ArrayList<>();
		static final String TIP = env("ORIGIN_AD_COACH_TIP", "renderdistance");
		static final double TX = Double.parseDouble(env("ORIGIN_AD_COACH_X", "1331")), TY0 = 488, TDY = 72;
		static final String[] OFF = {"keystrokes", "cps", "combo", "armorhud", "potionhud", "clock", "memory", "coords", "reach", "ping",
				"saturation", "packdisplay", "itemcounter", "directionhud", "daycounter", "stopwatch", "serveraddress", "scoreboard", "minimap",
				"crosshair", "togglesprint", "cooldowns", "bossbar", "waypoints", "titles", "teamview"};
		private double clickT = -1, resultT = -1, nextSample = 0, tryY = -1;
		private final StringBuilder samples = new StringBuilder();
		private String result = "null";
		private boolean written;

		@Override
		void options(Minecraft mc) {
			length = 1e9;
			mc.options.hideGui = false;   // the FPS module only draws with the HUD on
			mc.options.renderDistance().set(32);
			mc.options.fov().set(64);
			for (String id : OFF) {
				Mods.setOn(id, false);
			}
			Mods.setOn("fps", true);
			keyBackground = 0xFFFF00FF;
		}

		@Override
		long warmup() {
			return 45_000;   // FPS Coach ignores the first 30 s in a world
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			Shot f = pinned(level, "far");
			if (f == null) {
				throw new IllegalStateException("R11 needs ORIGIN_AD_RUN_FAR");
			}
			double[] v = f.path().at(0);
			place(p, v);
			view(v);
			drift = 0;   // the Coach's test counts a move; a still camera keeps before and after the same view
			at(1.5, mc -> mc.setScreen(new com.origin.client.client.gui.FpsCoachScreen(null)));
			at(2.4, mc -> {
				logHits(mc.screen);
				var tips = com.origin.client.client.mods.FpsCoach.tips(mc);
				int i = -1;
				StringBuilder b = new StringBuilder("coach tips:");
				for (int k = 0; k < tips.size(); k++) {
					b.append(" [").append(k).append(' ').append(tips.get(k).id()).append(": ").append(tips.get(k).title()).append(']');
					if (tips.get(k).id().equals(TIP)) {
						i = k;
					}
				}
				log(b.toString());
				if (i < 0) {
					throw new IllegalStateException("no FPS Coach tip " + TIP);
				}
				double t = t();
				tryY = (TY0 + TDY * i) * k1080(mc);
				glides.add(new Glide(t, t + 0.6, mx(mc), my(mc), TX * k1080(mc), tryY));
			});
			at(3.5, mc -> {
				mouseTo(mc, TX * k1080(mc), tryY);
				ClipShaders.click(mc);
				clickT = t();
				log(String.format(Locale.ROOT, "TRY IT at %.3f s: testing=%s (%s)", clickT, com.origin.client.client.mods.FpsCoach.testing(),
						com.origin.client.client.mods.FpsCoach.testingChange()));
			});
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			for (Glide g : glides) {
				g.apply(mc, t);
			}
			if (t >= nextSample) {
				nextSample = t + 0.5;
				samples.append(samples.length() == 0 ? "" : ", ").append(String.format(Locale.ROOT, "[%.2f, %d]", t, mc.getFps()));
			}
			var r = com.origin.client.client.mods.FpsCoach.result();
			if (clickT >= 0 && resultT < 0 && !com.origin.client.client.mods.FpsCoach.testing() && r != null) {
				resultT = t;
				if (r.before() != null && r.after() != null) {
					result = String.format(Locale.ROOT, "{\"change\": \"%s\", \"before\": {\"avg\": %.2f, \"low\": %.2f, \"frames\": %d}, "
									+ "\"after\": {\"avg\": %.2f, \"low\": %.2f, \"frames\": %d}, \"gain\": %.2f, \"moved\": %s, \"stopped\": %s}",
							r.change(), r.before().avg(), r.before().low(), r.before().frames(), r.after().avg(), r.after().low(), r.after().frames(),
							r.after().avg() - r.before().avg(), r.moved(), r.stopped());
				}
				log(String.format(Locale.ROOT, "coach result at %.3f s: %s", t, result));
				length = t + 6.0;
				mc.setScreen(new com.origin.client.client.gui.FpsCoachScreen(null));   // the result card
			}
			if (!written && t >= length - 0.3) {
				written = true;
				sidecar("R11", String.format(Locale.ROOT, "{\n  \"tip\": \"%s\",\n  \"x\": %.0f,\n  \"y\": %.0f,\n  \"click\": %.3f,\n  \"result_at\": %.3f,\n  \"result\": %s,\n  \"fps\": [%s]\n}\n",
						TIP, TX * k1080(mc), tryY, clickT, resultT, result, samples));
			}
			if (t > 150 && resultT < 0) {
				throw new IllegalStateException("FPS Coach test never finished");
			}
		}
	}

	// ---------------------------------------------------------------- R12 (rev 4): the mod menu as one tall real image

	/**
	 * The mod menu drawn 1920 px wide (deckScale = 1920/920, top at y 0; it runs past the window's foot), then the list
	 * stepped through exact scroll offsets with one still each (menu_NN.png). v2/capture/stitch_menu.py joins them into
	 * one tall image; R12.json lists every still's real scroll offset (design px) and the scale.
	 */
	static final class ClipMenuStills extends UiClip {
		static final double STEP = 300;
		static final int N = 13;
		private final double[] got = new double[N];

		@Override
		void options(Minecraft mc) {
			mc.options.hideGui = true;
			for (String id : new String[]{"fps", "cps", "armorhud"}) {   // the ones baseOptions switched off (capture.py R6_ON)
				Mods.setOn(id, true);
			}
			deckScale = mc.getWindow().getWidth() / 920f;   // frame-wide at any window width
			deckTop = 0;
			length = 3.0 + N * 0.7 + 1.0;
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			double[] v = canopy(level, level.getSharedSpawnPos()).path().at(0.6);
			place(p, v);
			view(v);
			drift = 0;
			at(0.5, mc -> mc.setScreen(new com.origin.client.client.gui.OriginModMenuScreen()));
			at(2.6, mc -> logHits(mc.screen));
			for (int k = 0; k < N; k++) {
				final int kk = k;
				at(3.0 + k * 0.7, mc -> {
					double want = Math.min(kk * STEP, menuF(mc.screen, "maxScroll"));
					menuSet(mc.screen, "scroll", want);
					menuSet(mc.screen, "scrollTarget", want);
				});
				at(3.0 + k * 0.7 + 0.45, mc -> {
					got[kk] = menuF(mc.screen, "scroll");
					still(mc, String.format(Locale.ROOT, "menu_%02d", kk));
					log(String.format(Locale.ROOT, "menu still %d at scroll %.1f (max %.1f)", kk, got[kk], menuF(mc.screen, "maxScroll")));
					if (kk == N - 1) {
						StringBuilder b = new StringBuilder();
						for (int j = 0; j < N; j++) {
							b.append(j == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.2f", got[j]));
						}
						sidecar("R12", String.format(Locale.ROOT, "{\n  \"scale\": %.6f,\n  \"top\": %.1f,\n  \"bodyY\": 128,\n  \"bottom\": 572,\n  \"scroll\": [%s]\n}\n",
								deckScale, deckTop, b));
					}
				});
			}
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			mouseTo(mc, 4, mc.getWindow().getHeight() - 4);   // over the panel's own margin: no card is hovered in any still
		}

		static double menuF(Screen s, String name) {
			try {
				var f = s.getClass().getDeclaredField(name);
				f.setAccessible(true);
				return ((Number) f.get(s)).doubleValue();
			} catch (Exception e) {
				throw new IllegalStateException("mod menu field " + name + ": " + e);
			}
		}

		static void menuSet(Screen s, String name, double v) {
			try {
				var f = s.getClass().getDeclaredField(name);
				f.setAccessible(true);
				f.setDouble(s, v);
			} catch (Exception e) {
				throw new IllegalStateException("mod menu field " + name + ": " + e);
			}
		}
	}

	/** Unused here; kept so InputConstants stays referenced for the key names in logs. */
	static String keyName(int key) {
		return InputConstants.Type.KEYSYM.getOrCreate(key).getName();
	}
}
