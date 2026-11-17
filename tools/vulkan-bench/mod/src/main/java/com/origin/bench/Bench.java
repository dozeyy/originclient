package com.origin.bench;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.Difficulty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Scratch-only frame-rate bench (OpenGL+Sodium vs VulkanMod). Env BENCH_PLAN = ';'-separated steps, BENCH_OUT = dir.
 * Steps (DX/DZ relative to world spawn, DY relative to the surface there):
 *   case NAME RD DX DY DZ YAW PITCH        stand still, wait for terrain, warm up, sample frame times, screenshot
 *   field NS COUNT DX DZ                   Create/Trainworks cog field, rows facing south from the surface at DX,DZ
 *   fly NAME RD DX Y DZ PITCH SECONDS      jump to fresh ground, time terrain load, then fly east 1 block/tick, sample
 * Frame times = intervals between HUD renders (one per frame). Never shipped.
 */
public final class Bench implements ClientModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("vk-bench");
	static final String WORLD = System.getenv("BENCH_WORLD") == null ? "bench" : System.getenv("BENCH_WORLD");
	static final long SEED = 20261117L;

	static String out;
	static List<String[]> steps = new ArrayList<>();
	static int stepIdx = -1;
	static int phase;
	static long at;
	static long warmMs = 15000, sampleMs = 30000;
	static boolean prime;

	// spot state
	static double px, py, pz;
	static float yaw, pitch;
	static boolean posReady;
	static int lastCount = -1;
	static long lastChange, t0, readyAt, sampleStart;
	static boolean flying;
	static double flyX;

	// frame recording
	static long lastFrame;
	static boolean recording;
	static long[] frames = new long[400_000];
	static int nFrames;
	static int secFrames;
	static long secStart;

	@Override
	public void onInitializeClient() {
		out = System.getenv("BENCH_OUT");
		String plan = System.getenv("BENCH_PLAN");
		if (out == null || plan == null) {
			return;
		}
		prime = "1".equals(System.getenv("BENCH_PRIME"));
		if (System.getenv("BENCH_WARM") != null) warmMs = Long.parseLong(System.getenv("BENCH_WARM"));
		if (System.getenv("BENCH_SAMPLE") != null) sampleMs = Long.parseLong(System.getenv("BENCH_SAMPLE"));
		for (String s : plan.split(";")) {
			if (!s.isBlank()) steps.add(s.trim().split("\\s+"));
		}
		new File(out).mkdirs();
		ClientTickEvents.END_CLIENT_TICK.register(Bench::tick);
		HudRenderCallback.EVENT.register((g, d) -> frame());
		log("armed: pid=" + ProcessHandle.current().pid() + " " + steps.size() + " steps, warm " + warmMs + " sample " + sampleMs + " prime " + prime
				+ " vulkanmod=" + FabricLoader.getInstance().isModLoaded("vulkanmod") + " sodium=" + FabricLoader.getInstance().isModLoaded("sodium")
				+ " iris=" + FabricLoader.getInstance().isModLoaded("iris") + " flywheel=" + FabricLoader.getInstance().isModLoaded("flywheel"));
	}

	static void frame() {
		long now = System.nanoTime();
		if (lastFrame != 0 && recording && nFrames < frames.length) {
			frames[nFrames++] = now - lastFrame;
		}
		lastFrame = now;
		secFrames++;
	}

	static void log(String s) {
		LOG.info("[vk-bench] " + s);
		try {
			Files.writeString(new File(out, "bench.log").toPath(), System.currentTimeMillis() + " " + s + "\n",
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}

	static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (secStart == 0) secStart = now;
		if (now - secStart >= 1000) {
			if (mc.level != null && stepIdx >= 0) {
				log("sec fps=" + secFrames + " chunks=" + (mc.level == null ? -1 : mc.level.getChunkSource().getLoadedChunksCount()) + " phase=" + phase + " rec=" + recording);
			}
			secFrames = 0;
			secStart = now;
		}
		try {
			if (stepIdx < 0) {
				boot(mc, now);
				return;
			}
			if (mc.player == null || mc.level == null) {
				return;
			}
			if (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
				mc.setScreen(null);
			}
			hold(mc);
			if (stepIdx >= steps.size()) {
				if (phase != 99) {
					phase = 99;
					Files.writeString(new File(out, "bench.done").toPath(), "ok\n");
					log("done, quitting");
					mc.stop();
				}
				return;
			}
			run(mc, now, steps.get(stepIdx));
		} catch (Throwable t) {
			LOG.error("[vk-bench] failed at step {} phase {}", stepIdx, phase, t);
			log("FAILED " + t);
			phase = 99;
			mc.stop();
		}
	}

	static void boot(Minecraft mc, long now) {
		if (phase == 0) {
			if (!(mc.screen instanceof TitleScreen) || now < at) {
				if (mc.screen instanceof TitleScreen && at == 0) {
					log("title screen up");
					at = now + 1000;
				}
				return;
			}
			mc.getWindow().setWindowed(1920, 1080);
			var flows = mc.createWorldOpenFlows();
			if (mc.getLevelSource().levelExists(WORLD)) {
				flows.openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
			} else {
				var settings = new LevelSettings("Bench", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
				flows.createFreshLevel(WORLD, settings, new WorldOptions(SEED, true, false), ra -> ra
						.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), new TitleScreen());
			}
			phase = 1;
			at = now + 2000;
			return;
		}
		// Datapack mods (Terralith, Tectonic...) mark a copied world "experimental": answer the warning the way
		// "I know what I'm doing" does (no backup), through the screen's own listener, found by type.
		if (mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen bc) {
			try {
				for (var f : net.minecraft.client.gui.screens.BackupConfirmScreen.class.getDeclaredFields()) {
					if (f.getType() == net.minecraft.client.gui.screens.BackupConfirmScreen.Listener.class) {
						f.setAccessible(true);
						var l = (net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) f.get(bc);
						log("experimental-world warning: proceeding without backup");
						l.proceed(false, false);
						break;
					}
				}
			} catch (Throwable t) {
				log("could not answer the experimental warning: " + t);
			}
			return;
		}
		MinecraftServer server = mc.getSingleplayerServer();
		if (now < at || mc.level == null || mc.player == null || server == null) {
			return;
		}
		if (mc.screen != null) {
			mc.setScreen(null);
		}
		cmd(server, "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
				"gamerule randomTickSpeed 3", "gamerule doPokemonSpawning false", "time set 6000", "weather clear 1000000", "kill @e[type=!player]", "gamemode creative @p");
		log("in world; window " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " focused=" + mc.isWindowActive()
				+ " glfwSize=" + mc.getWindow().getScreenWidth() + "x" + mc.getWindow().getScreenHeight());
		stepIdx = 0;
		phase = 0;
	}

	static void cmd(MinecraftServer server, String... cmds) {
		server.execute(() -> {
			var src = server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
			for (String c : cmds) server.getCommands().performPrefixedCommand(src, c);
		});
	}

	/** Keeps the player exactly on the spot (or on the fly line), flying, no drift. */
	static void hold(Minecraft mc) {
		if (!posReady) return;
		var p = mc.player;
		p.getAbilities().flying = true;
		p.setDeltaMovement(0, 0, 0);
		if (flying) {
			flyX += 1.0;
			p.setPos(flyX, py, pz);
		} else {
			p.setPos(px, py, pz);
		}
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.yRotO = yaw;
		p.xRotO = pitch;
		p.setYHeadRot(yaw);
	}

	static int surface(MinecraftServer server, int x, int z) throws Exception {
		CompletableFuture<Integer> f = new CompletableFuture<>();
		server.execute(() -> {
			try {
				ServerLevel l = server.overworld();
				l.getChunk(x >> 4, z >> 4);
				f.complete(l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f.get();
	}

	static BlockPos spawn(Minecraft mc) {
		return mc.getSingleplayerServer().overworld().getSharedSpawnPos();
	}

	static void place(Minecraft mc, double x, double y, double z, float yw, float pt) {
		px = x; py = y; pz = z; yaw = yw; pitch = pt;
		cmd(mc.getSingleplayerServer(), String.format(java.util.Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", x, y, z, yw, pt));
		// the client follows the server's teleport; holding starts once it has arrived (phase 1)
		posReady = false;
		lastCount = -1;
	}

	static void run(Minecraft mc, long now, String[] s) throws Exception {
		MinecraftServer server = mc.getSingleplayerServer();
		BlockPos sp = spawn(mc);
		switch (s[0]) {
			case "field" -> {
				int dx = Integer.parseInt(s[3]), dz = Integer.parseInt(s[4]);
				int x = sp.getX() + dx, z = sp.getZ() + dz;
				int y = surface(server, x, z);
				BlockPos feet = new BlockPos(x, y, z);
				int count = Integer.parseInt(s[2]);
				String ns = s[1];
				CompletableFuture<Integer> f = new CompletableFuture<>();
				server.execute(() -> {
					try {
						f.complete(placeField(server.overworld(), ns, feet, Direction.SOUTH, count));
					} catch (Throwable t) {
						f.completeExceptionally(t);
					}
				});
				log("field " + ns + " placed " + f.get() + " cogs at " + feet.toShortString());
				stepIdx++;
			}
			case "cmd" -> {
				// "cmd <words...>": one server command (e.g. kill @e[type=!player] after a field step)
				String c = String.join(" ", java.util.Arrays.copyOfRange(s, 1, s.length));
				cmd(server, c);
				log("cmd " + c);
				stepIdx++;
			}
			case "case" -> caseStep(mc, now, s, false);
			case "fly" -> caseStep(mc, now, s, true);
			default -> {
				log("unknown step " + s[0]);
				stepIdx++;
			}
		}
	}

	static void caseStep(Minecraft mc, long now, String[] s, boolean fly) throws Exception {
		MinecraftServer server = mc.getSingleplayerServer();
		BlockPos sp = spawn(mc);
		String name = s[1];
		switch (phase) {
			case 0 -> {
				int rd = Integer.parseInt(s[2]);
				mc.options.renderDistance().set(rd);
				mc.options.save();
				if (fly) {
					double x = sp.getX() + Integer.parseInt(s[3]) + 0.5, y = Double.parseDouble(s[4]), z = sp.getZ() + Integer.parseInt(s[5]) + 0.5;
					flyX = x;
					place(mc, x, y, z, -90f, Float.parseFloat(s[6]));
				} else {
					int x = sp.getX() + Integer.parseInt(s[3]), z = sp.getZ() + Integer.parseInt(s[5]);
					int y = surface(server, x, z) + Integer.parseInt(s[4]);
					place(mc, x + 0.5, y, z + 0.5, Float.parseFloat(s[6]), Float.parseFloat(s[7]));
				}
				t0 = now;
				lastChange = now;
				readyAt = 0;
				log("case " + name + " start rd=" + rd + " pos=" + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", px, py, pz) + " yaw=" + yaw + " pitch=" + pitch);
				phase = 1;
			}
			case 1 -> { // wait: every chunk within RD-2 of the spot is on the client and its surface section is meshed
				if (!posReady) {
					if (mc.player.distanceToSqr(px, py, pz) > 4) {
						if (now - t0 > 20_000) throw new IllegalStateException("teleport never arrived");
						return;
					}
					posReady = true;
					log("case " + name + " arrived t=" + (now - t0));
				}
				int c = mc.level.getChunkSource().getLoadedChunksCount();
				int rd = Integer.parseInt(s[2]);
				int r = Math.max(2, rd - 2);
				int pcx = ((int) Math.floor(px)) >> 4, pcz = ((int) Math.floor(pz)) >> 4;
				int total = 0, loaded = 0, meshed = 0;
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (dx * dx + dz * dz > r * r) continue;
						total++;
						int cx = pcx + dx, cz = pcz + dz;
						if (!mc.level.getChunkSource().hasChunk(cx, cz)) continue;
						loaded++;
						int bx = (cx << 4) + 8, bz = (cz << 4) + 8;
						int y = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1;
						if (mc.levelRenderer.isSectionCompiled(new BlockPos(bx, y, bz))) meshed++;
					}
				}
				if (loaded != lastCount) {
					lastCount = loaded;
					lastChange = now;
				}
				if (now - at >= 1000) {
					at = now;
					log("case " + name + " waiting t=" + (now - t0) + " loaded=" + loaded + " meshed=" + meshed + "/" + total);
				}
				// each stack's own "no visible section left to build" (Sodium and VulkanMod both override it; both only
				// mesh what the camera can see, so a full-disk mesh check never completes)
				boolean built = mc.levelRenderer.hasRenderedAllSections();
				boolean ok = loaded == total && built;
				if (!ok) {
					readyAt = 0;
				} else if (readyAt == 0) {
					readyAt = now;
				}
				if (ok && now - readyAt >= 1000 && now - t0 >= 1500) {
					long firstOk = readyAt;
					readyAt = now;
					log("case " + name + " terrain ready: readyMs=" + (firstOk - t0) + " lastChunkMs=" + (lastChange - t0) + " disk=" + total
							+ " meshedSurface=" + meshed + " clientChunks=" + c + " stats=" + mc.levelRenderer.getSectionStatistics());
					phase = 2;
					at = fly ? now : now + (prime ? 0 : warmMs);
				} else if (now - t0 > 240_000) {
					log("case " + name + " terrain NOT ready after 240 s: loaded=" + loaded + " meshed=" + meshed + "/" + total + " allSections=" + built + "; continuing");
					readyAt = now;
					phase = 2;
					at = now + (prime ? 0 : warmMs);
				}
			}
			case 2 -> { // warm-up done -> sample
				if (now < at) return;
				if (prime) {
					log("case " + name + " primed");
					phase = 0;
					posReady = false;
					stepIdx++;
					return;
				}
				nFrames = 0;
				recording = true;
				if (fly) {
					flying = true;
				}
				sampleStart = now;
				long dur = fly ? Long.parseLong(s[7]) * 1000 : sampleMs;
				at = now + dur;
				log("case " + name + " sample start epochMs=" + now + " focused=" + mc.isWindowActive() + " fb=" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
				phase = 3;
			}
			case 3 -> {
				if (now < at) return;
				recording = false;
				flying = false;
				long end = System.currentTimeMillis();
				StringBuilder sb = new StringBuilder(nFrames * 9);
				for (int i = 0; i < nFrames; i++) sb.append(frames[i]).append('\n');
				Files.writeString(new File(out, name + ".frames").toPath(), sb.toString());
				long sum = 0;
				for (int i = 0; i < nFrames; i++) sum += frames[i];
				log("case " + name + " sample end epochMs=" + end + " frames=" + nFrames + " avgFps=" + String.format(java.util.Locale.ROOT, "%.1f", nFrames / (sum / 1e9))
						+ " chunks=" + mc.level.getChunkSource().getLoadedChunksCount() + " stats=" + mc.levelRenderer.getSectionStatistics()
						+ " focused=" + mc.isWindowActive());
				if (fly) {
					// back onto the line's end, still, for the screenshot
					px = flyX;
				}
				phase = 4;
				at = now + 300;
			}
			case 4 -> {
				if (now < at) return;
				Screenshot.grab(new File(out), name + ".png", mc.getMainRenderTarget(), c -> {
				});
				log("case " + name + " shot");
				phase = 0;
				posReady = false;
				stepIdx++;
				at = now + 500;
			}
			default -> {
			}
		}
	}

	// ---- cog field (copied from the flywheel-vulkan CI test mod's CreateRig.placeField, plus a cleared box) ----
	static int placeField(ServerLevel level, String ns, BlockPos feet, Direction facing, int cogs) {
		Direction right = facing.getClockWise();
		Direction.Axis axis = right.getAxis();
		final int perRow = 20;
		final int depthRows = 8;
		int rows = (cogs + perRow - 1) / perRow;
		int top = 1 + 2 * ((rows - 1) / depthRows);
		// clear the box the field and the camera sit in, so terrain never hides it (same blocks every run)
		BlockPos a = feet.relative(right, -perRow / 2 - 4).relative(facing, -12);
		BlockPos b = feet.relative(right, perRow / 2 + 4).relative(facing, 22).above(top + 6);
		for (BlockPos p : BlockPos.betweenClosed(a, b)) {
			if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		for (BlockPos p : BlockPos.betweenClosed(a.below(), new BlockPos(b.getX(), a.getY() - 1, b.getZ()))) {
			level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		int placed = 0;
		for (int r = 0; r < rows; r++) {
			int depth = 5 + 2 * (r % depthRows);
			int height = 1 + 2 * (r / depthRows);
			BlockPos rowStart = feet.relative(facing, depth).above(height).relative(right, -perRow / 2 - 1);
			put(level, ns, "creative_motor", rowStart, right, axis);
			int n = Math.min(perRow, cogs - r * perRow);
			for (int i = 1; i <= n; i++) {
				if (put(level, ns, "cogwheel", rowStart.relative(right, i), right, axis)) placed++;
			}
		}
		return placed;
	}

	static boolean put(ServerLevel level, String ns, String id, BlockPos pos, Direction right, Direction.Axis axis) {
		Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(ns, id));
		if (block == Blocks.AIR) {
			LOG.warn("[vk-bench] {}:{} not found", ns, id);
			return false;
		}
		BlockState state = block.defaultBlockState();
		state = with(state, "facing", right.getSerializedName());
		state = with(state, "axis", axis.getSerializedName());
		return level.setBlock(pos, state, Block.UPDATE_ALL);
	}

	static BlockState with(BlockState state, String name, String value) {
		Property<?> p = state.getBlock().getStateDefinition().getProperty(name);
		return p == null ? state : set(state, p, value);
	}

	static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> p, String value) {
		return p.getValue(value).map(v -> state.setValue(p, v)).orElse(state);
	}
}
