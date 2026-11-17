package com.origin.probescene;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;

/**
 * Dev-only probe scene (vulkan-probe). With env ORIGIN_PROBE_SCENE=&lt;dir&gt; it opens one normal world with a
 * fixed seed (created on the first run, reopened after, so every run sees the same chunks), freezes the time at
 * noon with clear weather, stands the player at the world spawn with a cow in front, and takes the same five
 * shots: four compass directions slightly below the horizon and one looking down at the ground. Then it quits.
 * An OpenGL run and a Vulkan run of the same run folder therefore show the same spot, for a side-by-side.
 * Does nothing without the env var. Never shipped; loaded through -PextraMods only.
 */
public final class ProbeScene implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("probe-scene");
	private static final String WORLD = "probe_scene";
	private static final long SEED = 20261117L;
	private static final long SETTLE_MS = 35_000;   // chunk meshing + shadow map after the world opens
	private static final float[][] VIEWS = {{0, 12}, {90, 12}, {180, 12}, {270, 12}, {0, 60}};

	private static String dir;
	private static int step;
	private static long at;
	private static int view;

	@Override
	public void onInitializeClient() {
		dir = System.getenv("ORIGIN_PROBE_SCENE");
		if (dir == null || dir.isBlank()) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(ProbeScene::tick);
		LOG.info("[probe-scene] armed, shots -> {}", dir);
	}

	private static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (now < at) {
			return;
		}
		try {
			switch (step) {
				case 0 -> {
					if (!(mc.screen instanceof TitleScreen)) {
						return;
					}
					mc.getWindow().setWindowed(1920, 1080);
					var flows = mc.createWorldOpenFlows();
					if (mc.getLevelSource().levelExists(WORLD)) {
						flows.openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
					} else {
						var settings = new LevelSettings("Probe Scene", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
								new GameRules(), WorldDataConfiguration.DEFAULT);
						flows.createFreshLevel(WORLD, settings, new WorldOptions(SEED, false, false), ra -> ra
								.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL)
								.value().createWorldDimensions(), new TitleScreen());
					}
					step = 1;
					at = now + 2000;
				}
				case 1 -> {
					if (mc.screen instanceof PauseScreen) {
						mc.setScreen(null);
					}
					MinecraftServer server = mc.getSingleplayerServer();
					if (mc.level == null || mc.player == null || mc.screen != null || server == null) {
						return;
					}
					server.execute(() -> {
						var src = server.createCommandSourceStack().withSuppressedOutput();
						var cmds = server.getCommands();
						for (String c : new String[]{"gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
								"gamerule doMobSpawning false", "gamerule doEntityDrops false", "time set 6000", "weather clear 1000000",
								"kill @e[type=!player]",
								"execute as @p at @p run tp @s ~ ~ ~ 0 12",
								"execute at @p run summon cow ^ ^ ^5 {NoAI:1b,Rotation:[90f,0f]}"}) {
							cmds.performPrefixedCommand(src, c);
						}
					});
					LOG.info("[probe-scene] in world, settling {} s", SETTLE_MS / 1000);
					step = 2;
					at = now + SETTLE_MS;
				}
				case 2 -> {
					if (mc.player == null) {
						return;
					}
					if (mc.screen instanceof PauseScreen) {
						mc.setScreen(null);
					}
					float[] v = VIEWS[view];
					mc.player.setYRot(v[0]);
					mc.player.setXRot(v[1]);
					mc.player.yRotO = v[0];
					mc.player.xRotO = v[1];
					step = 3;
					at = now + 4000;
				}
				case 3 -> {
					float[] v = VIEWS[view];
					String name = "scene_" + (int) v[0] + "_" + (int) v[1] + ".png";
					Screenshot.grab(new File(dir), name, mc.getMainRenderTarget(), c -> {
					});
					LOG.info("[probe-scene] captured {}", name);
					view++;
					step = view < VIEWS.length ? 2 : 4;
					at = now + 1500;
				}
				case 4 -> {
					Files.writeString(new File(dir, "probe-scene.done").toPath(), "ok\n");
					LOG.info("[probe-scene] done, quitting");
					step = 5;
					mc.stop();
				}
				default -> {
				}
			}
		} catch (Throwable t) {
			LOG.error("[probe-scene] step {} failed", step, t);
			step = 5;
			mc.stop();
		}
	}
}
