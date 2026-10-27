package com.origin.client.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.origin.client.client.hud.HudElements;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Benchmark mode (Phase 6.1, 2026-10-25). OFF unless the JVM property
 * {@code -Dorigin.bench=<scenarios>} (or env {@code ORIGIN_BENCH}) is set, so
 * players never run any of this. "all" = every scenario, or a comma list of
 * idle, hud, particles, menu, flythrough. tools/bench/bench-189.ps1 drives it.
 *
 * What one launch does: waits for the title screen, sizes the window, creates a
 * fresh seeded world (seed 1234567, creative, peaceful, cheats on, time frozen at
 * noon, no rain, no mob spawning), lets spawn chunks build, then runs each
 * scenario as warm-up + measured window with a scripted camera (no OS input),
 * writes bench.json + bench.md (+ frames-<scenario>.csv), restores every setting
 * it touched, and quits the game.
 *
 * Variants (tools/bench/prod189.py, CI "bench-189" workflow): {@code ORIGIN_BENCH_PROBE=1} makes
 * this the ONLY thing Origin does in the game (no hooks, no HUD, no title screen; the CI jar for
 * that variant also has no Mixin tweaker), which is the "OptiFine-only baseline": the same bench
 * scenes measured on OptiFine + TexFix + BetterFps with Origin inert. The hud scene needs Origin's
 * HUD, so a probe run skips it.
 *
 * Measurement cost is kept near zero: the per-frame path is one nanoTime() and
 * one store into a preallocated long[]; heap is sampled 20x/s from Runtime (no
 * allocation); GC counts and render-thread allocated bytes are read only at the
 * start and end of each measured window. Stats are computed after the window.
 *
 * Frame time = the gap between consecutive RenderTickEvent STARTs: a whole
 * frame including buffer swap, the same source FrameStats/the FPS HUD use.
 */
public final class OriginBench {
	private static final String SPEC = prop("origin.bench", "ORIGIN_BENCH");

	public static boolean enabled() {
		return SPEC != null && !SPEC.trim().isEmpty();
	}

	/** True for the OptiFine-only baseline: OriginClientMod then installs ONLY the bench and returns. */
	public static boolean probeOnly() {
		return enabled() && PROBE;
	}

	/**
	 * ORIGIN_BENCH_PROF=1 on a non-probe run: BenchProf times every Origin hook inside the measured window
	 * (U6.3 measurement). Off for players and for the OptiFine-only baseline.
	 */
	public static boolean profiling() {
		return enabled() && !PROBE && "1".equals(str("origin.bench.prof", "0"));
	}

	/** Registers the bench listener when bench mode is on; no-op for players. */
	public static void install() {
		if (!enabled()) {
			return;
		}
		INSTANCE = new OriginBench();
		com.origin.client.client.hooks.OriginHooks.registerObject(INSTANCE);
		log("bench mode ON: scenarios=" + SPEC);
	}

	private static OriginBench INSTANCE;

	// ---- configuration (system properties, all optional) ----
	private static final long SEED = 1234567L;
	private static final double WARMUP_S = num("origin.bench.warmup", 10);
	private static final double DURATION_S = num("origin.bench.duration", 30);
	private static final double SETTLE_S = num("origin.bench.settle", 15);
	private static final int RENDER_DISTANCE = (int) num("origin.bench.rd", 8);
	private static final String SIZE = str("origin.bench.size", "1920x1080");
	private static final int CAPACITY = (int) num("origin.bench.capacity", 600000);
	/** Particles spawned per client tick in the particle scene (each of crit and magic crit). */
	private static final int PARTICLES_PER_TICK = (int) num("origin.bench.particles", 200);

	/** ORIGIN_BENCH_SHOTS=1: one screenshot per scene at the end of its warm-up (outDir/screenshots). */
	private static final boolean SHOTS = "1".equals(str("origin.bench.shots", "0"));

	/** ORIGIN_BENCH_PROBE=1: Origin is inert, only this class runs (OptiFine-only baseline). */
	private static final boolean PROBE = "1".equals(str("origin.bench.probe", "0"));

	/** ORIGIN_BENCH_SAVE_OPTIONS=1: write options.txt / optionsof.txt before quitting (U6.0 key dump, isolated instance only). */
	private static final boolean SAVE_OPTIONS = "1".equals(str("origin.bench.save_options", "0"));

	/**
	 * ORIGIN_BENCH_IGNORE_FOCUS=1: do not mark a scene invalid because the window is not focused. Only for CI,
	 * where Xvfb has no window manager and the game never gets focus; the report says it was ignored.
	 */
	private static final boolean IGNORE_FOCUS = "1".equals(str("origin.bench.ignore_focus", "0"));

	private static final String[] ALL_SCENARIOS = {"idle", "hud", "particles", "menu", "flythrough"};
	/**
	 * Named only, never part of "all": 16 client-side fake players in a ring in front of the camera
	 * ("players": vanilla look, Origin's nametag / entity hooks; "cosmetics": the same players wearing
	 * Origin cosmetics -- capes with physics, hats, masks, wings -- via CosmeticsService.benchWear;
	 * "pause": the Esc menu over the world, i.e. Menu Blur's "pause" kind; "pvp-ui": the inventory opened
	 * for 1 s and closed for 1 s, over and over, as in a PvP refill -- Menu Blur's open/close cost).
	 */
	private static final String[] EXTRA_SCENARIOS = {"players", "cosmetics", "pause", "pvp-ui"};
	private static final int FAKE_PLAYERS = (int) num("origin.bench.players", 16);
	private final List<net.minecraft.client.entity.EntityOtherPlayerMP> fakes = new ArrayList<>();
	private Map<Object, Object> fakeInfoMap;

	// ---- per-frame recording (render thread only) ----
	private final long[] frames = new long[CAPACITY];
	private int count = 0;
	private long lastFrame = 0;
	private long recordStart = 0;
	private long recordEndAt = 0;
	private volatile boolean recording = false;
	private boolean truncated = false;

	// ---- per-tick samples during the measured window ----
	private long heapMin, heapMax, heapSum;
	private int heapSamples, unfocusedTicks;

	// ---- state machine ----
	private enum Phase { WAIT_TITLE, WAIT_WORLD, SETTLE, SETUP, WARMUP, MEASURE, DONE }
	private Phase phase = Phase.WAIT_TITLE;
	private final List<String> scenarios = new ArrayList<>();
	private int scenarioIndex = 0;
	private long phaseUntil = 0;   // nanoTime deadline for SETTLE / WARMUP
	private long scenarioTick = 0; // client ticks since the scenario's setup (drives the camera path)
	private double baseX, baseY, baseZ;
	private static final double FLY_Y = 120.0;   // above the hills along this seed's path east
	private long flyStartTick = -1;
	private double expectX = Double.NaN;
	private int cameraResets = 0;   // ticks where the camera was not where the script put it (counted while measuring)
	private final Random particleRng = new Random(42);

	// ---- restore bookkeeping ----
	private final Map<String, Boolean> savedModOn = new LinkedHashMap<>();
	private final Map<String, Boolean> savedFpsOpts = new LinkedHashMap<>();
	/** Limit Unfocused FPS as it was before the run (null = not touched). See saveAndApplySettings. */
	private Boolean savedLimitUnfocused;
	private int savedRd, savedFps, savedGuiScale;
	private float savedFov;
	private boolean savedVsync, savedPauseOnLostFocus;
	private EnumDifficulty savedDifficulty;
	private boolean settingsSaved = false;

	// ---- results ----
	private final JsonArray results = new JsonArray();
	private final List<String> warnings = new ArrayList<>();
	private File outDir;
	private String glRenderer = "?", glVendor = "?", glVersion = "?";
	private List<GarbageCollectorMXBean> gcs;
	private com.sun.management.ThreadMXBean threads;
	private long gcCount0, gcTime0, alloc0;
	// GC pauses inside the measured window, from the JVM's GC notifications (they arrive on the JVM's
	// own thread, never the render thread). gcNotify stays false if the JVM offers none.
	private final Object gcLock = new Object();
	private boolean gcNotify = false, gcMeasuring = false;
	private long gcPauseTotalMs = 0, gcPauseMaxMs = 0, gcPauseCount = 0;
	private String startedAt;
	// Phase 6.1 startup metric: JVM start -> first tick on the title screen, in ms (-1 = not seen). Same
	// probe in every variant (the OptiFine-only one too), so tools/bench/ab189.py pairs it like a scene.
	private long startupMs = -1;

	private OriginBench() {
		for (String s : SPEC.toLowerCase(Locale.ROOT).split(",")) {
			s = s.trim();
			if (s.equals("all") || s.equals("1") || s.equals("true")) {
				scenarios.addAll(Arrays.asList(ALL_SCENARIOS));
			} else if (Arrays.asList(ALL_SCENARIOS).contains(s) || Arrays.asList(EXTRA_SCENARIOS).contains(s)) {
				scenarios.add(s);
			} else if (!s.isEmpty()) {
				warnings.add("unknown scenario '" + s + "' ignored");
			}
		}
		if (scenarios.isEmpty()) {
			scenarios.addAll(Arrays.asList(ALL_SCENARIOS));
		}
		if (PROBE && scenarios.remove("hud")) {
			warnings.add("probe run (Origin inert): the hud scene needs Origin's HUD and was skipped");
		}
	}

	// ======================================================================
	// Per frame: the only hot path. One nanoTime, one array store.
	// ======================================================================

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent e) {
		if (e.phase != TickEvent.Phase.START || !recording) {
			return;
		}
		long now = System.nanoTime();
		if (lastFrame != 0) {
			if (count < frames.length) {
				frames[count++] = now - lastFrame;
			} else {
				truncated = true;
			}
		}
		lastFrame = now;
		if (now >= recordEndAt) {
			recording = false;
			if (BenchProf.ON) {
				BenchProf.stop();
			}
		}
	}

	// ======================================================================
	// Per client tick: drives the scenario (camera, particles, screens).
	// ======================================================================

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent e) {
		if (e.phase != TickEvent.Phase.END || phase == Phase.DONE) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		try {
			step(mc);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.error("[origin-bench] failed in phase {}", phase, t);
			warnings.add("bench aborted in phase " + phase + ": " + t);
			finish(mc);
		}
	}

	private void step(Minecraft mc) throws Exception {
		long now = System.nanoTime();
		switch (phase) {
			case WAIT_TITLE: {
				if (!OriginDevShots.isTitle(mc.currentScreen)) {
					return;
				}
				startupMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
				log("[OriginBoot] title screen " + startupMs + " ms after JVM start");
				startedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
				outDir = resolveOutDir(mc);
				glRenderer = GL11.glGetString(GL11.GL_RENDERER);
				glVendor = GL11.glGetString(GL11.GL_VENDOR);
				glVersion = GL11.glGetString(GL11.GL_VERSION);
				gcs = ManagementFactory.getGarbageCollectorMXBeans();
				installGcListener();
				java.lang.management.ThreadMXBean tm = ManagementFactory.getThreadMXBean();
				if (tm instanceof com.sun.management.ThreadMXBean) {
					threads = (com.sun.management.ThreadMXBean) tm;
					if (!threads.isThreadAllocatedMemoryEnabled()) {
						threads.setThreadAllocatedMemoryEnabled(true);
					}
				}
				saveAndApplySettings(mc);
				if (BenchProf.ON) {
					BenchProf.wrapEventHandlers(com.origin.client.client.hooks.OriginHooks.registered(), this);
				}
				String[] wh = SIZE.toLowerCase(Locale.ROOT).split("x");
				OriginDevShots.resizeWindow(mc, Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim()));
				WorldSettings ws = new WorldSettings(SEED, WorldSettings.GameType.CREATIVE, true, false, WorldType.DEFAULT);
				ws.enableCommands();
				String folder = "OriginBench-" + System.currentTimeMillis();
				log("creating world " + folder + " (seed " + SEED + "), output " + outDir);
				phase = Phase.WAIT_WORLD;
				mc.launchIntegratedServer(folder, "Origin Bench", ws);
				return;
			}
			case WAIT_WORLD: {
				if (mc.currentScreen instanceof GuiIngameMenu) {
					mc.displayGuiScreen(null);
				}
				if (mc.theWorld == null || mc.thePlayer == null || mc.currentScreen != null) {
					return;
				}
				BlockPos spawn = mc.theWorld.getSpawnPoint();
				if (mc.theWorld.getChunkFromBlockCoords(spawn).isEmpty()) {
					// The terrain height below is only real once the spawn chunk has arrived: read
					// too early it is 0, and the camera ended up inside a hill (2026-10-25).
					return;
				}
				// World rules through the server's own commands (cheats are on). Feedback off first.
				mc.thePlayer.sendChatMessage("/gamerule sendCommandFeedback false");
				mc.thePlayer.sendChatMessage("/gamerule doDaylightCycle false");
				mc.thePlayer.sendChatMessage("/gamerule doMobSpawning false");
				mc.thePlayer.sendChatMessage("/time set 6000");
				mc.thePlayer.sendChatMessage("/weather clear 1000000");
				BlockPos top = mc.theWorld.getHeight(spawn);
				baseX = spawn.getX() + 0.5;
				baseZ = spawn.getZ() + 0.5;
				baseY = Math.max(top.getY(), 64) + 8.0;
				// Move the camera through the SERVER (/tp): the integrated server rejects a
				// client-side jump of several blocks and snaps the player back every other
				// tick (seen 2026-10-25: y flipped 64 <-> 72 each tick). After the /tp the
				// static scenes only hold the view angles; the flythrough moves in small steps.
				mc.thePlayer.sendChatMessage(String.format(Locale.ROOT, "/tp @p %.1f %.1f %.1f -45 15", baseX, baseY, baseZ));
				phase = Phase.SETTLE;
				phaseUntil = now + (long) (SETTLE_S * 1e9);
				log("world up; spawn " + spawn + ", camera at y " + baseY + "; settling " + SETTLE_S + " s");
				return;
			}
			case SETTLE: {
				holdCamera(mc, "idle");
				if (now < phaseUntil) {
					return;
				}
				mc.ingameGUI.getChatGUI().clearChatMessages();
				phase = Phase.SETUP;
				return;
			}
			case SETUP: {
				String sc = scenarios.get(scenarioIndex);
				scenarioTick = 0;
				setupScenario(mc, sc);
				phase = Phase.WARMUP;
				phaseUntil = now + (long) (WARMUP_S * 1e9);
				log("scenario " + sc + ": warm-up " + WARMUP_S + " s");
				return;
			}
			case WARMUP: {
				String sc = scenarios.get(scenarioIndex);
				driveScenario(mc, sc);
				if (now < phaseUntil) {
					return;
				}
				if (SHOTS && outDir != null) {
					// Proof of what the scene looks like, taken in the warm-up so it never lands in the numbers.
					// 1.8.9's ScreenShotHelper fails the first shot if screenshots/ doesn't exist yet.
					new File(outDir, "screenshots").mkdirs();
					OriginDevShots.grab(mc, outDir.getAbsolutePath(), "scene-" + sc + ".png");
				}
				beginMeasure();
				phase = Phase.MEASURE;
				log("scenario " + sc + ": measuring " + DURATION_S + " s");
				return;
			}
			case MEASURE: {
				String sc = scenarios.get(scenarioIndex);
				driveScenario(mc, sc);
				long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
				heapMin = Math.min(heapMin, used);
				heapMax = Math.max(heapMax, used);
				heapSum += used;
				heapSamples++;
				if (!Display.isActive()) {
					unfocusedTicks++;
				}
				if (recording) {
					return;
				}
				results.add(endMeasure(mc, sc));
				teardownScenario(mc, sc);
				scenarioIndex++;
				if (scenarioIndex >= scenarios.size()) {
					finish(mc);
				} else {
					phase = Phase.SETUP;
				}
				return;
			}
			default:
		}
	}

	// ======================================================================
	// Scenarios
	// ======================================================================

	private void setupScenario(Minecraft mc, String sc) throws Exception {
		mc.ingameGUI.getChatGUI().clearChatMessages();
		if (sc.equals("hud")) {
			// Every Origin HUD element's mod on, plus the FPS readout's frame-time extras.
			for (HudElements.Element el : HudElements.ALL) {
				String id = el.modId();
				if (!savedModOn.containsKey(id)) {
					savedModOn.put(id, Mods.on(id));
				}
				Mods.setOn(id, true);
			}
			for (String k : new String[]{"showLows", "showFrameTime", "showGraph"}) {
				savedFpsOpts.put(k, Mods.bool("fps", k));
				Mods.set("fps", k, true);
			}
		} else if (sc.equals("menu")) {
			mc.displayGuiScreen(new GuiContainerCreative(mc.thePlayer));
		} else if (sc.equals("pause")) {
			mc.displayGuiScreen(new GuiIngameMenu());
		} else if (sc.equals("players") || sc.equals("cosmetics")) {
			spawnFakePlayers(mc, sc.equals("cosmetics"));
		} else if (sc.equals("flythrough")) {
			mc.thePlayer.sendChatMessage(String.format(Locale.ROOT, "/tp @p %.1f %.1f %.1f -90 20", baseX, FLY_Y, baseZ));
			flyStartTick = -1;
		}
	}

	private void teardownScenario(Minecraft mc, String sc) {
		if (sc.equals("hud")) {
			restoreMods();
		} else if (sc.equals("menu") || sc.equals("pause") || sc.equals("pvp-ui")) {
			mc.displayGuiScreen(null);
		} else if (sc.equals("players") || sc.equals("cosmetics")) {
			removeFakePlayers(mc);
		} else if (sc.equals("flythrough") && mc.thePlayer != null) {
			// back to the static camera, or every scene after this one renders the flight's end point
			mc.thePlayer.sendChatMessage(String.format(Locale.ROOT, "/tp @p %.1f %.1f %.1f -45 15", baseX, baseY, baseZ));
		}
	}

	/**
	 * Client-side players (never on the server) in a ring 6-9 blocks in front of the camera. Each gets a
	 * NetworkPlayerInfo so vanilla treats it as a real player (cape layer gate, skins, nametag) and every
	 * skin part on. "cosmetics": half wear cape + halo + mask (cape physics path), half wings + crown + mask.
	 */
	@SuppressWarnings("unchecked")
	private void spawnFakePlayers(Minecraft mc, boolean cosmetics) throws Exception {
		removeFakePlayers(mc);
		fakeInfoMap = null;
		java.util.UUID self = mc.thePlayer.getUniqueID();
		for (java.lang.reflect.Field f : mc.getNetHandler().getClass().getDeclaredFields()) {
			if (Map.class.isAssignableFrom(f.getType())) {
				f.setAccessible(true);
				Map<Object, Object> m = (Map<Object, Object>) f.get(mc.getNetHandler());
				if (m != null && m.containsKey(self)) {
					fakeInfoMap = m;   // the player-info map: the only one keyed by your UUID
				}
			}
		}
		if (fakeInfoMap == null) {
			warnings.add("players scene: no player-info map found; fake players have no cape layer");
		}
		Map<java.util.UUID, com.origin.client.client.cosmetics.CosmeticsService.Look> looks = new LinkedHashMap<>();
		com.origin.client.client.cosmetics.CosmeticsService.Look capeLook = new com.origin.client.client.cosmetics.CosmeticsService.Look(
				"cape-inferno", "halo-orbit", "mask-shades", null, null);
		com.origin.client.client.cosmetics.CosmeticsService.Look wingLook = new com.origin.client.client.cosmetics.CosmeticsService.Look(
				null, "crown-pixel", "mask-shades", "wings-ember", null);
		for (int i = 0; i < FAKE_PLAYERS; i++) {
			java.util.UUID id = new java.util.UUID(0x0B3E7C0000000000L, i + 1);
			com.mojang.authlib.GameProfile gp = new com.mojang.authlib.GameProfile(id, "Bench" + i);
			if (fakeInfoMap != null) {
				fakeInfoMap.put(id, new net.minecraft.client.network.NetworkPlayerInfo(gp));
			}
			net.minecraft.client.entity.EntityOtherPlayerMP p = new net.minecraft.client.entity.EntityOtherPlayerMP(mc.theWorld, gp);
			p.getDataWatcher().updateObject(10, (byte) 0x7F);   // every skin part on, the cape included
			double[] at = ringPos(i, 0);
			p.setPositionAndRotation(at[0], at[1], at[2], (float) at[3], 0f);
			p.rotationYawHead = (float) at[3];
			mc.theWorld.addEntityToWorld(-4242000 - i, p);
			fakes.add(p);
			if (cosmetics) {
				looks.put(id, i % 2 == 0 ? capeLook : wingLook);
			}
		}
		com.origin.client.client.cosmetics.CosmeticsService.benchWear(cosmetics ? looks : null);
	}

	/** Ring slot i at scene tick k: centre 6 blocks out along the camera's yaw, radius 3, turning slowly. */
	private double[] ringPos(int i, long k) {
		double a = 2 * Math.PI * i / Math.max(1, FAKE_PLAYERS) + k * 0.02;
		double cx = baseX + 4.5, cz = baseZ + 4.5;
		double x = cx + 3 * Math.cos(a), z = cz + 3 * Math.sin(a);
		double yaw = Math.toDegrees(a) + 180.0;   // walking along the ring (tangent), so capes trail
		return new double[]{x, baseY - 2.5, z, yaw};
	}

	private void removeFakePlayers(Minecraft mc) {
		com.origin.client.client.cosmetics.CosmeticsService.benchWear(null);
		for (int i = 0; i < fakes.size(); i++) {
			if (mc.theWorld != null) {
				mc.theWorld.removeEntityFromWorld(-4242000 - i);
			}
			if (fakeInfoMap != null) {
				fakeInfoMap.remove(fakes.get(i).getUniqueID());
			}
		}
		fakes.clear();
	}

	private void driveScenario(Minecraft mc, String sc) {
		scenarioTick++;
		if (sc.equals("flythrough")) {
			// 0.55 blocks/tick (= creative flying speed, 11 b/s) due east at y 120 (above
			// the terrain, new chunks generate and load the whole way), with a slow
			// +-30 degree yaw sweep every 10 s so the frustum keeps changing. Starts once
			// the /tp from setupScenario has landed.
			EntityPlayer p = mc.thePlayer;
			if (flyStartTick < 0) {
				aim(mc, -90f, 20f);
				if (Math.abs(p.posY - FLY_Y) < 0.5 && Math.abs(p.posX - baseX) < 0.5) {
					flyStartTick = scenarioTick;
					expectX = Double.NaN;
				}
				return;
			}
			if (!Double.isNaN(expectX) && Math.abs(p.posX - expectX) > 0.3) {
				cameraResets++;   // the server snapped us back: this run's flythrough is not what it claims
			}
			long k = scenarioTick - flyStartTick;
			double x = baseX + k * 0.55;
			float yaw = -90f + 30f * (float) Math.sin(k * (2 * Math.PI / 200.0));
			place(mc, x, FLY_Y, baseZ, yaw, 20f);
			expectX = x;
			return;
		}
		if (scenarioTick > 40 && mc.thePlayer != null
				&& (Math.abs(mc.thePlayer.posX - baseX) > 0.3 || Math.abs(mc.thePlayer.posY - baseY) > 0.3)) {
			cameraResets++;   // the static camera is not where it should be
		}
		holdCamera(mc, sc);
		if (!fakes.isEmpty()) {
			// walk the ring: interpolated moves (3 steps), so limbs swing and capes / cape physics move
			for (int i = 0; i < fakes.size(); i++) {
				double[] at = ringPos(i, scenarioTick);
				fakes.get(i).setPositionAndRotation2(at[0], at[1], at[2], (float) at[3], 0f, 3, false);
				fakes.get(i).setRotationYawHead((float) at[3]);
			}
		}
		if (sc.equals("particles")) {
			// Crit + magic-crit bursts in a box 4-8 blocks in front of the camera (PvP proxy).
			double cx = baseX + 4.0, cz = baseZ + 4.0;   // yaw -45 looks toward +x +z
			for (int i = 0; i < PARTICLES_PER_TICK; i++) {
				double px = cx + particleRng.nextDouble() * 4 - 2;
				double py = baseY - 1.5 + particleRng.nextDouble() * 3;
				double pz = cz + particleRng.nextDouble() * 4 - 2;
				mc.theWorld.spawnParticle(EnumParticleTypes.CRIT, true, px, py, pz,
						particleRng.nextGaussian() * 0.2, particleRng.nextDouble() * 0.2, particleRng.nextGaussian() * 0.2);
				mc.theWorld.spawnParticle(EnumParticleTypes.CRIT_MAGIC, true, px, py, pz,
						particleRng.nextGaussian() * 0.2, particleRng.nextDouble() * 0.2, particleRng.nextGaussian() * 0.2);
			}
		} else if (sc.equals("menu") && !(mc.currentScreen instanceof GuiContainerCreative)) {
			mc.displayGuiScreen(new GuiContainerCreative(mc.thePlayer));
		} else if (sc.equals("pause") && !(mc.currentScreen instanceof GuiIngameMenu)) {
			mc.displayGuiScreen(new GuiIngameMenu());
		} else if (sc.equals("pvp-ui")) {
			// 20 ticks open, 20 closed: a new inventory screen every 2 s (a refill), the world running throughout
			boolean open = (scenarioTick / 20) % 2 == 0;
			boolean isOpen = mc.currentScreen instanceof GuiContainerCreative;
			if (open && !isOpen) {
				mc.displayGuiScreen(new GuiContainerCreative(mc.thePlayer));
			} else if (!open && isOpen) {
				mc.displayGuiScreen(null);
			}
		}
	}

	/** The fixed camera of the static scenes: above spawn, looking south-east and a little down. */
	private void holdCamera(Minecraft mc, String sc) {
		aim(mc, -45f, 15f);
	}

	/** View angles only (the server never rejects those); flying on so nothing falls. */
	private static void aim(Minecraft mc, float yaw, float pitch) {
		EntityPlayer p = mc.thePlayer;
		if (p == null) {
			return;
		}
		p.capabilities.isFlying = true;
		p.motionX = 0;
		p.motionY = 0;
		p.motionZ = 0;
		p.rotationYaw = yaw;
		p.rotationPitch = pitch;
		p.rotationYawHead = yaw;
	}

	/** Set at tick END: the next tick's lastTickPos is this tick's pos, so frames interpolate smoothly. */
	private static void place(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
		EntityPlayer p = mc.thePlayer;
		if (p == null) {
			return;
		}
		p.capabilities.isFlying = true;
		p.motionX = 0;
		p.motionY = 0;
		p.motionZ = 0;
		p.setPosition(x, y, z);
		p.rotationYaw = yaw;
		p.rotationPitch = pitch;
		p.rotationYawHead = yaw;
	}

	// ======================================================================
	// Measurement window
	// ======================================================================

	private void beginMeasure() {
		count = 0;
		truncated = false;
		heapMin = Long.MAX_VALUE;
		heapMax = 0;
		heapSum = 0;
		heapSamples = 0;
		unfocusedTicks = 0;
		cameraResets = 0;
		gcCount0 = gcCount();
		gcTime0 = gcTime();
		alloc0 = allocated();
		synchronized (gcLock) {
			gcPauseTotalMs = 0;
			gcPauseMaxMs = 0;
			gcPauseCount = 0;
			gcMeasuring = true;
		}
		if (BenchProf.ON) {
			BenchProf.start();   // calibrates its own timer first (a few ms), then opens its window
		}
		lastFrame = 0;
		recordStart = System.nanoTime();
		recordEndAt = recordStart + (long) (DURATION_S * 1e9);
		recording = true;
	}

	private JsonObject endMeasure(Minecraft mc, String sc) {
		long wall = Math.max(1, (lastFrame - recordStart));
		long gcCount = gcCount() - gcCount0;
		long gcTime = gcTime() - gcTime0;
		long alloc = allocated() - alloc0;
		long pauseTotal, pauseMax, pauseCount;
		synchronized (gcLock) {
			gcMeasuring = false;
			pauseTotal = gcPauseTotalMs;
			pauseMax = gcPauseMaxMs;
			pauseCount = gcPauseCount;
		}
		int n = count;
		long[] sorted = Arrays.copyOf(frames, n);
		Arrays.sort(sorted);
		long sum = 0;
		for (int i = 0; i < n; i++) {
			sum += frames[i];
		}
		JsonObject r = new JsonObject();
		r.addProperty("scenario", sc);
		r.addProperty("frames", n);
		r.addProperty("seconds", sum / 1e9);
		if (n > 0) {
			double medianNs = pct(sorted, 50);
			int stutters = 0, hitches = 0;
			for (int i = 0; i < n; i++) {
				if (frames[i] > 2 * medianNs) {
					stutters++;
				}
				if (frames[i] >= 20_000_000L) {
					hitches++;
				}
			}
			r.addProperty("avgFps", round(n / (sum / 1e9), 1));
			r.addProperty("low1Fps", round(lowFps(sorted, 0.01), 1));
			r.addProperty("low01Fps", round(lowFps(sorted, 0.001), 1));
			r.addProperty("p50Ms", round(medianNs / 1e6, 3));
			r.addProperty("p95Ms", round(pct(sorted, 95) / 1e6, 3));
			r.addProperty("p99Ms", round(pct(sorted, 99) / 1e6, 3));
			r.addProperty("maxMs", round(sorted[n - 1] / 1e6, 3));
			r.addProperty("stutters", stutters);
			r.addProperty("stutterPct", round(100.0 * stutters / n, 3));
			r.addProperty("hitches20ms", hitches);
		}
		r.addProperty("heapMinMb", heapSamples == 0 ? 0 : round(heapMin / 1048576.0, 1));
		r.addProperty("heapAvgMb", heapSamples == 0 ? 0 : round(heapSum / (double) heapSamples / 1048576.0, 1));
		r.addProperty("heapMaxMb", round(heapMax / 1048576.0, 1));
		r.addProperty("heapLimitMb", round(Runtime.getRuntime().maxMemory() / 1048576.0, 0));
		r.addProperty("gcCount", gcCount);
		r.addProperty("gcTimeMs", gcTime);
		if (gcNotify) {
			// Pause = the collection's own duration (whole milliseconds), from the JVM's notifications.
			r.addProperty("gcPauseCount", pauseCount);
			r.addProperty("gcPauseTotalMs", pauseTotal);
			r.addProperty("gcPauseMaxMs", pauseMax);
		}
		r.addProperty("renderThreadAllocMbPerSec", alloc < 0 ? -1 : round(alloc / 1048576.0 / (wall / 1e9), 2));
		r.addProperty("unfocusedTicks", unfocusedTicks);
		r.addProperty("cameraOffTicks", cameraResets);
		r.addProperty("truncated", truncated);
		r.addProperty("valid", (IGNORE_FOCUS || unfocusedTicks == 0) && cameraResets == 0 && !truncated && n > 0);
		if (BenchProf.ON) {
			BenchProf.stop();
			r.add("prof", BenchProf.report(n));
			r.add("microbench", BenchProf.microbench());
		}
		writeFrames(sc, n);
		log(String.format(Locale.ROOT, "scenario %s: %d frames, avg %.1f fps", sc, n,
				n == 0 ? 0.0 : n / (sum / 1e9)));
		return r;
	}

	/** "1% low" = the average FPS over the slowest 1% of frames (0.1% low likewise). */
	private static double lowFps(long[] sorted, double fraction) {
		int n = sorted.length;
		int k = Math.max(1, (int) Math.ceil(n * fraction));
		long s = 0;
		for (int i = n - k; i < n; i++) {
			s += sorted[i];
		}
		return 1e9 / (s / (double) k);
	}

	/** Nearest-rank percentile of a sorted array. */
	private static double pct(long[] sorted, double p) {
		int n = sorted.length;
		int idx = (int) Math.ceil(p / 100.0 * n) - 1;
		return sorted[Math.max(0, Math.min(n - 1, idx))];
	}

	private void installGcListener() {
		NotificationListener listener = new NotificationListener() {
			@Override
			public void handleNotification(Notification n, Object handback) {
				try {
					if (!com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) {
						return;
					}
					com.sun.management.GarbageCollectionNotificationInfo info =
							com.sun.management.GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
					long ms = info.getGcInfo().getDuration();
					synchronized (gcLock) {
						if (gcMeasuring) {
							gcPauseTotalMs += ms;
							gcPauseMaxMs = Math.max(gcPauseMaxMs, ms);
							gcPauseCount++;
						}
					}
				} catch (Throwable ignored) {
					// a malformed notification only costs one data point
				}
			}
		};
		for (GarbageCollectorMXBean g : gcs) {
			if (g instanceof NotificationEmitter) {
				try {
					((NotificationEmitter) g).addNotificationListener(listener, null, null);
					gcNotify = true;
				} catch (Throwable t) {
					warnings.add("no GC notifications from " + g.getName() + ": " + t);
				}
			}
		}
	}

	private long gcCount() {
		long c = 0;
		for (GarbageCollectorMXBean g : gcs) {
			c += Math.max(0, g.getCollectionCount());
		}
		return c;
	}

	private long gcTime() {
		long c = 0;
		for (GarbageCollectorMXBean g : gcs) {
			c += Math.max(0, g.getCollectionTime());
		}
		return c;
	}

	private long allocated() {
		try {
			return threads == null ? -1 : threads.getThreadAllocatedBytes(Thread.currentThread().getId());
		} catch (Throwable t) {
			return -1;
		}
	}

	// ======================================================================
	// Settings: save, apply fixed bench values, restore
	// ======================================================================

	private void saveAndApplySettings(Minecraft mc) {
		net.minecraft.client.settings.GameSettings g = mc.gameSettings;
		savedRd = g.renderDistanceChunks;
		savedFps = g.limitFramerate;
		savedVsync = g.enableVsync;
		savedPauseOnLostFocus = g.pauseOnLostFocus;
		savedDifficulty = g.difficulty;
		savedFov = g.fovSetting;
		savedGuiScale = g.guiScale;
		settingsSaved = true;
		g.renderDistanceChunks = RENDER_DISTANCE;
		g.limitFramerate = 260;          // 260 = "Unlimited" on 1.8.9
		g.enableVsync = false;
		Display.setVSyncEnabled(false);
		g.pauseOnLostFocus = false;      // a click elsewhere must not open the pause menu mid-run
		g.difficulty = EnumDifficulty.PEACEFUL;   // the integrated server reads it at world start
		g.fovSetting = 70f;
		g.guiScale = 2;
		// CI (Xvfb) never focuses the window. With Limit Unfocused FPS on (the default) Origin then
		// skips its HUD elements and cosmetic sparkles (FrameWatch.idle), so an unfocused run would
		// measure an Origin that draws nothing. Off for the run, restored after. Probe: Origin is inert.
		if (IGNORE_FOCUS && !PROBE) {
			savedLimitUnfocused = Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps");
			Mods.set(Mods.PERFORMANCE_ID, "limitUnfocusedFps", false);
		}
	}

	private void restoreSettings(Minecraft mc) {
		if (!settingsSaved) {
			return;
		}
		net.minecraft.client.settings.GameSettings g = mc.gameSettings;
		g.renderDistanceChunks = savedRd;
		g.limitFramerate = savedFps;
		g.enableVsync = savedVsync;
		g.pauseOnLostFocus = savedPauseOnLostFocus;
		g.difficulty = savedDifficulty;
		g.fovSetting = savedFov;
		g.guiScale = savedGuiScale;
		if (savedLimitUnfocused != null) {
			Mods.set(Mods.PERFORMANCE_ID, "limitUnfocusedFps", savedLimitUnfocused);
			savedLimitUnfocused = null;
		}
		settingsSaved = false;
	}

	/**
	 * U6.0 (tools/bench/optifine-keys-189.txt): leave a freshly generated options.txt + optionsof.txt in the
	 * game folder and list GameSettings' own of* fields (name, type, value) in of-fields.txt. OptiFine's
	 * saveOptions also writes optionsof.txt; if this build does not, its private saveOfOptions is called.
	 */
	private void dumpOptions(Minecraft mc) {
		net.minecraft.client.settings.GameSettings g = mc.gameSettings;
		g.saveOptions();
		try {
			File of = new File(mc.mcDataDir, "optionsof.txt");
			if (!of.isFile()) {
				java.lang.reflect.Method m = g.getClass().getDeclaredMethod("saveOfOptions");
				m.setAccessible(true);
				m.invoke(g);
				warnings.add("optionsof.txt was not written by saveOptions; called saveOfOptions by reflection");
			}
		} catch (Throwable t) {
			warnings.add("no optionsof.txt (OptiFine not loaded?): " + t);
		}
		try {
			StringBuilder sb = new StringBuilder("# GameSettings fields named of<Upper>*: name<TAB>type<TAB>value\n");
			for (java.lang.reflect.Field f : g.getClass().getDeclaredFields()) {
				String n = f.getName();
				if (n.length() > 2 && n.startsWith("of") && Character.isUpperCase(n.charAt(2))) {
					f.setAccessible(true);
					Object v = f.get(java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? null : g);
					String val = v == null ? "null" : v.getClass().isArray() ? arrayString(v) : String.valueOf(v);
					sb.append(n).append('\t').append(f.getType().getSimpleName()).append('\t').append(val).append('\n');
				}
			}
			write(new File(outDir, "of-fields.txt"), sb.toString());
		} catch (Throwable t) {
			warnings.add("of-fields.txt: " + t);
		}
	}

	private static String arrayString(Object array) {
		StringBuilder sb = new StringBuilder("[");
		int len = java.lang.reflect.Array.getLength(array);
		for (int i = 0; i < len; i++) {
			sb.append(i == 0 ? "" : ",").append(java.lang.reflect.Array.get(array, i));
		}
		return sb.append(']').toString();
	}

	private void restoreMods() {
		for (Map.Entry<String, Boolean> en : savedModOn.entrySet()) {
			Mods.setOn(en.getKey(), en.getValue());
		}
		savedModOn.clear();
		for (Map.Entry<String, Boolean> en : savedFpsOpts.entrySet()) {
			Mods.set("fps", en.getKey(), en.getValue());
		}
		savedFpsOpts.clear();
	}

	// ======================================================================
	// Report + quit
	// ======================================================================

	private void finish(Minecraft mc) {
		if (phase == Phase.DONE) {
			return;
		}
		phase = Phase.DONE;
		recording = false;
		JsonObject env = new JsonObject();
		try {
			env.addProperty("startedAt", startedAt);
			env.addProperty("finishedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
			env.addProperty("build", str("origin.bench.build", "?"));   // set by bench-189.ps1: jar version + git commit
			env.addProperty("minecraft", "1.8.9");
			env.addProperty("optifine", com.origin.client.OriginClient.isOptiFineLoaded());
			env.addProperty("gpu", glRenderer);
			env.addProperty("glVendor", glVendor);
			env.addProperty("glVersion", glVersion);
			env.addProperty("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ", "
					+ System.getProperty("java.vm.name") + ")");
			env.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
			env.addProperty("cpuThreads", Runtime.getRuntime().availableProcessors());
			env.addProperty("heapLimitMb", Runtime.getRuntime().maxMemory() / 1048576);
			env.addProperty("jvmArgs", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
			env.addProperty("resolution", mc.displayWidth + "x" + mc.displayHeight);
			env.addProperty("fullscreen", mc.isFullScreen());
			net.minecraft.client.settings.GameSettings g = mc.gameSettings;
			env.addProperty("renderDistance", g.renderDistanceChunks);
			env.addProperty("maxFps", g.limitFramerate >= 260 ? "unlimited" : String.valueOf(g.limitFramerate));
			env.addProperty("vsync", g.enableVsync);
			env.addProperty("graphics", g.fancyGraphics ? "fancy" : "fast");
			env.addProperty("smoothLighting", g.ambientOcclusion);
			env.addProperty("particles", g.particleSetting == 0 ? "all" : g.particleSetting == 1 ? "decreased" : "minimal");
			env.addProperty("guiScale", g.guiScale);
			env.addProperty("useVbo", g.useVbo);
			env.addProperty("mipmaps", g.mipmapLevels);
			env.addProperty("variant", str("origin.bench.variant", "?"));
			env.addProperty("originActive", !PROBE);
			// B-04 shader variant: prod189 --shaderpack fails the launch when this is false (a pack that did not
			// load would otherwise be measured as "shaders on"). Reads OptiFine's own flag; false without OptiFine.
			env.addProperty("shaderPackActive", com.origin.client.client.shaders.IrisBridge.shadersActive());
			env.addProperty("profiling", BenchProf.ON);
			env.addProperty("startupMs", startupMs);
			env.addProperty("picksOff", BenchSwitches.describe());
			env.addProperty("fakePlayers", FAKE_PLAYERS);
			env.addProperty("focusIgnored", IGNORE_FOCUS);
			env.addProperty("menuBlur", PROBE ? "n/a (Origin inert)"
					: Mods.on("menublur") + " (inventories " + Mods.bool("menublur", "inventory") + ")");
			env.addProperty("seed", SEED);
			env.addProperty("warmupS", WARMUP_S);
			env.addProperty("durationS", DURATION_S);
			env.addProperty("particlesPerTick", PARTICLES_PER_TICK * 2);
			StringBuilder hudOn = new StringBuilder();
			for (HudElements.Element el : PROBE ? java.util.Collections.<HudElements.Element>emptyList() : HudElements.ALL) {
				if (Mods.on(el.modId()) && hudOn.indexOf(el.modId()) < 0) {
					hudOn.append(hudOn.length() == 0 ? "" : ", ").append(el.modId());
				}
			}
			env.addProperty("hudOnOutsideHudScene", hudOn.toString());
		} catch (Throwable t) {
			warnings.add("environment capture: " + t);
		}
		try {
			restoreMods();
			restoreSettings(mc);
			if (SAVE_OPTIONS) {
				dumpOptions(mc);
			}
		} catch (Throwable t) {
			warnings.add("restore failed: " + t);
		}
		try {
			JsonObject root = new JsonObject();
			root.add("environment", env);
			root.add("scenarios", results);
			JsonArray w = new JsonArray();
			for (String s : warnings) {
				w.add(new com.google.gson.JsonPrimitive(s));
			}
			root.add("warnings", w);
			write(new File(outDir, "bench.md"), markdown(env));
			// bench.json last: its presence is the "run finished" marker the script waits for.
			write(new File(outDir, "bench.json"), new GsonBuilder().setPrettyPrinting().create().toJson(root));
			log("report written to " + outDir.getAbsolutePath());
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.error("[origin-bench] could not write the report", t);
		}
		if (!"false".equalsIgnoreCase(str("origin.bench.quit", "true"))) {
			mc.shutdown();
		}
	}

	private String markdown(JsonObject env) {
		StringBuilder sb = new StringBuilder();
		sb.append("# Origin 1.8.9 benchmark\n\n");
		sb.append("Run ").append(str(env, "startedAt")).append(" on ").append(str(env, "gpu"))
				.append(", ").append(str(env, "resolution")).append(", OptiFine ")
				.append("true".equals(str(env, "optifine")) ? "on" : "off").append(".\n\n");
		sb.append("| Scene | Avg FPS | 1% low | 0.1% low | Frame p50 / p95 / p99 (ms) | Stutters | Hitches 20ms+ | Heap avg / max (MB) | GC (count, ms) | Valid |\n");
		sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
		for (int i = 0; i < results.size(); i++) {
			JsonObject r = results.get(i).getAsJsonObject();
			sb.append("| ").append(str(r, "scenario"))
					.append(" | ").append(str(r, "avgFps"))
					.append(" | ").append(str(r, "low1Fps"))
					.append(" | ").append(str(r, "low01Fps"))
					.append(" | ").append(str(r, "p50Ms")).append(" / ").append(str(r, "p95Ms")).append(" / ").append(str(r, "p99Ms"))
					.append(" | ").append(str(r, "stutters")).append(" (").append(str(r, "stutterPct")).append("%)")
					.append(" | ").append(str(r, "hitches20ms"))
					.append(" | ").append(str(r, "heapAvgMb")).append(" / ").append(str(r, "heapMaxMb"))
					.append(" | ").append(str(r, "gcCount")).append(", ").append(str(r, "gcTimeMs"))
					.append(" | ").append("true".equals(str(r, "valid")) ? "yes" : "NO").append(" |\n");
		}
		sb.append("\n## What the numbers mean\n\n");
		sb.append("- **Avg FPS**: frames drawn per second over the measured window.\n");
		sb.append("- **1% low / 0.1% low**: the average FPS of the slowest 1% / 0.1% of frames. This is what you feel as hitching.\n");
		sb.append("- **Frame p50 / p95 / p99**: how long a typical frame takes (p50), and the slow tail (95th and 99th percentile).\n");
		sb.append("- **Stutters**: frames that took more than twice as long as a typical frame.\n");
		sb.append("- **Hitches 20ms+**: frames slower than 20 ms (under 50 FPS for that one frame): the ones you can see.\n");
		sb.append("- **Valid = NO** means the window lost focus, the camera was knocked off its path, or the frame buffer filled up; don't trust that row.\n\n");
		sb.append("## Scenes\n\n");
		sb.append("- **idle**: standing above spawn, fixed camera, your normal HUD.\n");
		sb.append("- **hud**: same spot with every Origin HUD element on, plus the FPS frame-time graph.\n");
		sb.append("- **particles**: same spot with ").append(str(env, "particlesPerTick"))
				.append(" crit / magic-crit particles spawned every tick in front of you (a PvP fight, exaggerated).\n");
		sb.append("- **menu**: the creative inventory open over the world (Menu Blur ").append(str(env, "menuBlur")).append(").\n");
		sb.append("- **pvp-ui**: the creative inventory opened for 1 s and closed for 1 s, over and over (a refill).\n");
		sb.append("- **flythrough**: flying east at creative speed through new terrain with a slow camera sweep.\n\n");
		sb.append("## Setup\n\n");
		for (Map.Entry<String, com.google.gson.JsonElement> en : env.entrySet()) {
			sb.append("- ").append(en.getKey()).append(": ").append(en.getValue().isJsonPrimitive()
					? en.getValue().getAsString() : en.getValue().toString()).append('\n');
		}
		if (!warnings.isEmpty()) {
			sb.append("\n## Warnings\n\n");
			for (String w : warnings) {
				sb.append("- ").append(w).append('\n');
			}
		}
		return sb.toString();
	}

	private void writeFrames(String sc, int n) {
		if (outDir == null) {
			return;
		}
		StringBuilder sb = new StringBuilder(n * 9 + 16);
		sb.append("frame_ns\n");
		for (int i = 0; i < n; i++) {
			sb.append(frames[i]).append('\n');
		}
		try {
			write(new File(outDir, "frames-" + sc + ".csv"), sb.toString());
		} catch (IOException e) {
			warnings.add("could not write frames-" + sc + ".csv: " + e);
		}
	}

	private static void write(File f, String s) throws IOException {
		File parent = f.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("cannot create " + parent);
		}
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
			w.write(s);
		}
	}

	private static File resolveOutDir(Minecraft mc) {
		String out = prop("origin.bench.out", "ORIGIN_BENCH_OUT");
		if (out != null && !out.trim().isEmpty()) {
			return new File(out.trim());
		}
		String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
		return new File(new File(mc.mcDataDir, "origin-bench"), stamp);
	}

	// ---- small helpers ----

	private static String prop(String key, String env) {
		String v = System.getProperty(key);
		return v != null ? v : System.getenv(env);
	}

	/** -Dorigin.bench.<x>, else env ORIGIN_BENCH_<X> (the dev runClient passes env, not -D). */
	private static String raw(String key) {
		String v = System.getProperty(key);
		if (v == null) {
			v = System.getenv(key.replace('.', '_').toUpperCase(Locale.ROOT));
		}
		return v == null || v.trim().isEmpty() ? null : v.trim();
	}

	private static String str(String key, String def) {
		String v = raw(key);
		return v == null ? def : v;
	}

	private static double num(String key, double def) {
		try {
			String v = raw(key);
			return v == null ? def : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "-";
	}

	private static double round(double v, int places) {
		double f = Math.pow(10, places);
		return Math.round(v * f) / f;
	}

	private static void log(String msg) {
		com.origin.client.OriginClient.LOGGER.info("[origin-bench] " + msg);
	}
}
