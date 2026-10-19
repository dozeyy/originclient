package com.origin.client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev-only boot timeline, part two (CI: boot-time.yml with -PbootWorld): after
 * the title is interactive, open one fixed singleplayer world and time how long
 * until its terrain is built, so a boot saving that moves work into the world
 * join (ModernFix dynamic_resources bakes block models on first use) is measured
 * there too. OFF unless {@code -Dorigin.bootLog.world=true} (with origin.bootLog).
 *
 * <p>The world is "origin-boot", fixed seed, created by the first (warm-up) boot
 * and opened as a saved world by every later one: a returning player loading
 * their world. Marks (OriginBootLog): world-start (the open is requested),
 * world-in (player in the world, loading screen gone), world-terrain (the
 * renderer reports every visible section built for 20 frames in a row, with at
 * least one section drawn). The game quits a few seconds after world-terrain,
 * or 90 s after world-start whatever happens.
 */
public final class OriginBootWorld {
	public static final boolean ENABLED = OriginBootLog.ENABLED && Boolean.getBoolean("origin.bootLog.world");
	private static final String LEVEL = "origin-boot";
	private static final long SEED = 4242424242L;
	private static final int SETTLED_FRAMES = 20;
	private static final long QUIT_AFTER_MS = 3000L;
	private static final long GIVE_UP_MS = 90_000L;

	private static long startedAt = 0;   // System.nanoTime(); 0 = not yet
	private static long terrainAt = 0;
	private static boolean inWorld = false;
	private static int settled = 0;

	private OriginBootWorld() {
	}

	/** Once per frame from BootLogMixin (runTick HEAD), with the title already interactive. */
	public static void tick(Minecraft mc) {
		if (startedAt == 0) {
			if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
				startedAt = System.nanoTime();
				OriginBootLog.mark("world-start");
				open(mc);
			}
			return;
		}
		if (terrainAt != 0) {
			return;
		}
		if (!inWorld) {
			if (mc.level != null && mc.player != null && mc.screen == null) {
				inWorld = true;
				OriginBootLog.mark("world-in");
			}
			return;
		}
		boolean built = mc.levelRenderer.hasRenderedAllSections() && mc.levelRenderer.countRenderedSections() > 0;
		settled = built ? settled + 1 : 0;
		if (settled >= SETTLED_FRAMES) {
			terrainAt = System.nanoTime();
			OriginBootLog.mark("world-terrain");
			OriginBootLog.note("world-terrain sections=" + mc.levelRenderer.countRenderedSections());
		}
	}

	/** True when CI can close the game: a few seconds after the terrain was built, or long after the open. */
	public static boolean done() {
		long now = System.nanoTime();
		if (terrainAt != 0) {
			return (now - terrainAt) / 1_000_000L >= QUIT_AFTER_MS;
		}
		return startedAt != 0 && (now - startedAt) / 1_000_000L >= GIVE_UP_MS;
	}

	private static void open(Minecraft mc) {
		WorldOpenFlows flows = mc.createWorldOpenFlows();
		if (mc.getLevelSource().levelExists(LEVEL)) {
			flows.openWorld(LEVEL, () -> mc.setScreen(new TitleScreen()));
			return;
		}
		LevelSettings settings = new LevelSettings(LEVEL, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
				new GameRules(), WorldDataConfiguration.DEFAULT);
		flows.createFreshLevel(LEVEL, settings, new WorldOptions(SEED, true, false),
				WorldPresets::createNormalWorldDimensions, new TitleScreen());
	}
}
