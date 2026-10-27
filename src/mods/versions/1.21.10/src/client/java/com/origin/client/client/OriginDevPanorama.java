package com.origin.client.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DEV-ONLY title-panorama capture (ORIGIN_SHOTS_SET=panorama, .github/workflows/panoramas.yml).
 * Never runs in a player's game: OriginDevShots only routes here when ORIGIN_SHOTS is set.
 *
 * <p>Makes the 6 cube faces Minecraft's title screen spins through (assets/minecraft/textures/gui/
 * title/background/panorama_0..5.png) from a real world: a fixed-seed world, the spot from
 * {@code ORIGIN_PANO_SPOT} (one entry of tools/panorama/spots.json: dimension, /locate target,
 * camera height and yaw, the update's mobs to stand in view, extra commands), clear weather at a
 * fixed daytime, GUI hidden, FOV 90 in a square window, and whatever shader pack CI selected
 * through Iris. Faces are taken in vanilla's order (Minecraft.grabPanoramixScreenshot): 0 = yaw f,
 * 1 = f+90, 2 = f+180, 3 = f-90 (all level), 4 = f looking straight up, 5 = f straight down.
 * Each face waits until every visible section is built and enough frames have passed for the
 * shader's temporal passes (TAA, cloud reprojection) to settle.
 */
final class OriginDevPanorama {
	private OriginDevPanorama() {
	}

	/** The square window the faces are rendered in (CI shrinks them to the shipped size). */
	private static final int FACE = 1024;
	private static final String LEVEL = "origin-panorama";
	private static final Pattern COORDS = Pattern.compile("\\[(-?\\d+), (~|-?\\d+), (-?\\d+)\\]");

	private static int phase = 0;
	private static long nextAt = 0;
	private static long phaseAt = 0;
	private static JsonObject spot;
	private static volatile String locateResult;   // "x y z" (y may be "~"), or "FAIL ..." once the server answered
	private static volatile double[] camera;       // feet x y z once the scene is built
	private static int face = 0;
	private static int warm = 0;
	private static long faceFrames = 0;
	private static int settledFrames = 0;
	private static long lastFrames = 0;

	static void tick(Minecraft mc, String dir) {
		long now = System.currentTimeMillis();
		if (camera != null && mc.player != null) {
			holdCamera(mc);
		}
		if (now < nextAt) {
			return;
		}
		nextAt = now + 250;
		try {
			switch (phase) {
				case 0 -> {
					spot = JsonParser.parseString(System.getenv("ORIGIN_PANO_SPOT")).getAsJsonObject();
					log("[panorama] spot " + spot.get("id").getAsString() + ": " + spot);
					mc.options.renderDistance().set(num("renderDistance", 12));
					mc.options.simulationDistance().set(Math.min(num("renderDistance", 12), 12));
					mc.options.fov().set(90);
					mc.options.fovEffectScale().set(0.0);
					mc.options.bobView().set(false);
					mc.options.entityDistanceScaling().set(2.0);
					mc.options.onboardAccessibility = false;
					LevelSettings settings = levelSettings();
					long seed = spot.has("seed") ? spot.get("seed").getAsLong() : 4242424242L;
					mc.createWorldOpenFlows().createFreshLevel(LEVEL, settings, new WorldOptions(seed, true, false),
							WorldPresets::createNormalWorldDimensions, new TitleScreen());
					log("[panorama] creating world, seed " + seed);
					phase = 1;
				}
				case 1 -> {
					unpause(mc);   // focus loss under Xvfb
					MinecraftServer server = mc.getSingleplayerServer();
					if (mc.level == null || mc.player == null || server == null || screen(mc) != null) {
						return;
					}
					server.execute(() -> setupWorld(server));
					phase = 2;
					nextAt = now + 3000;
				}
				case 2 -> {
					if (locateResult == null) {
						return;
					}
					if (locateResult.startsWith("FAIL")) {
						throw new IllegalStateException("locate failed: " + locateResult);
					}
					MinecraftServer server = mc.getSingleplayerServer();
					String at = locateResult;
					server.execute(() -> buildScene(server, at));
					phase = 3;
				}
				case 3 -> {
					if (camera == null) {
						return;
					}
					// Square window: FOV 90 both ways, exactly one cube face.
					sizeWindow(mc);
					hideHud(mc);
					phase = 4;
					phaseAt = now;
					warm = 0;
					aim(mc, 0);
					nextAt = now + 8000;   // the client receives the teleport + the scene, chunks start building
				}
				case 4 -> {
					// Warm-up turn: every face once, so every direction's sections get built before any capture.
					unpause(mc);
					if (warm < 6) {
						aim(mc, warm++);
						nextAt = now + 6000;
						return;
					}
					log("[panorama] warm-up done after " + (now - phaseAt) / 1000 + " s; window " + mc.getWindow().getWidth()
							+ "x" + mc.getWindow().getHeight() + " shader=" + com.origin.client.client.shaders.IrisBridge.currentPack());
					face = 0;
					startFace(mc, now);
					phase = 5;
				}
				case 5 -> {
					unpause(mc);
					long frames = com.origin.client.client.hud.FrameStats.frames();
					boolean built = mc.levelRenderer.hasRenderedAllSections();
					if (frames != lastFrames) {
						settledFrames = built ? settledFrames + (int) (frames - lastFrames) : 0;
						lastFrames = frames;
					}
					long waited = now - phaseAt;
					int needFrames = num("settleFrames", 24);
					boolean ready = settledFrames >= needFrames && frames - faceFrames >= needFrames && waited >= 5000;
					if (!ready && waited < 240_000) {
						return;
					}
					if (mc.getWindow().getWidth() != FACE || mc.getWindow().getHeight() != FACE) {
						sizeWindow(mc);
						startFace(mc, now);
						return;
					}
					String name = "panorama_" + face + ".png";
					grab(mc, dir, name);
					log("[dev-shots] captured " + name);
					log(String.format(Locale.ROOT, "[panorama] face %d: %d frames over %.1f s, built=%s%s", face,
							frames - faceFrames, waited / 1000.0, built, ready ? "" : " (gave up waiting)"));
					if (++face < 6) {
						startFace(mc, now);
						return;
					}
					com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — 6 shots in {}", dir);
					phase = 6;
					mc.stop();
				}
				default -> {
				}
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] step {} failed", phase, t);
			phase = 6;
			mc.stop();
		}
	}

	private static void startFace(Minecraft mc, long now) {
		aim(mc, face);
		phaseAt = now;
		faceFrames = com.origin.client.client.hud.FrameStats.frames();
		lastFrames = faceFrames;
		settledFrames = 0;
	}

	/** Vanilla's face order (Minecraft.grabPanoramixScreenshot). */
	private static float[] faceRot(int i) {
		float f = (float) num("yaw", 45.0);
		return switch (i) {
			case 1 -> new float[]{f + 90f, 0f};
			case 2 -> new float[]{f + 180f, 0f};
			case 3 -> new float[]{f - 90f, 0f};
			case 4 -> new float[]{f, -90f};
			case 5 -> new float[]{f, 90f};
			default -> new float[]{f, 0f};
		};
	}

	private static float[] aimRot = {0f, 0f};

	private static void aim(Minecraft mc, int i) {
		aimRot = faceRot(i);
		holdCamera(mc);
	}

	/** Every tick: the exact rotation (no interpolation from the last one) and no drift. */
	private static void holdCamera(Minecraft mc) {
		var p = mc.player;
		p.setYRot(aimRot[0]);
		p.setXRot(aimRot[1]);
		p.yRotO = aimRot[0];
		p.xRotO = aimRot[1];
		p.setYHeadRot(aimRot[0]);
		p.yHeadRotO = aimRot[0];
		p.setDeltaMovement(Vec3.ZERO);
	}

	// ---- server thread ----

	private static void setupWorld(MinecraftServer server) {
		try {
			CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
			List<String> cmds = new ArrayList<>(List.of(
					// Both rule spellings: camelCase up to 1.21.10, snake_case ids from 1.21.11. The unknown one fails quietly.
					"gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
					"gamerule doFireTick false", "gamerule doInsomnia false", "gamerule doPatrolSpawning false",
					"gamerule doTraderSpawning false", "gamerule randomTickSpeed 0", "gamerule sendCommandFeedback false",
					"gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false",
					"gamerule fire_spread_radius_around_player 0", "gamerule spawn_phantoms false", "gamerule spawn_patrols false",
					"gamerule spawn_wandering_traders false", "gamerule random_tick_speed 0", "gamerule send_command_feedback false",
					"weather clear 1000000", "time set " + num("time", 3000), "gamemode spectator @a"));
			for (String c : cmds) {
				server.getCommands().performPrefixedCommand(src, c);
			}
			ServerLevel level = level(server);
			List<String> said = new ArrayList<>();
			CommandSource rec = new CommandSource() {
				@Override
				public void sendSystemMessage(Component message) {
					said.add(message.getString());
				}

				@Override
				public boolean acceptsSuccess() {
					return true;
				}

				@Override
				public boolean acceptsFailure() {
					return true;
				}

				@Override
				public boolean shouldInformAdmins() {
					return false;
				}
			};
			BlockPos origin = server.getPlayerList().getPlayers().get(0).blockPosition();   // near the world spawn (ServerLevel.getSharedSpawnPos is gone by 1.21.10)
			CommandSourceStack locSrc = server.createCommandSourceStack().withSource(rec).withLevel(level)
					.withPosition(Vec3.atCenterOf(origin));
			String locate = spot.get("locate").getAsString();
			server.getCommands().performPrefixedCommand(locSrc, "locate " + locate);
			for (String s : said) {
				log("[panorama] locate " + locate + " -> " + s);
				Matcher m = COORDS.matcher(s);
				if (m.find()) {
					locateResult = m.group(1) + " " + m.group(2) + " " + m.group(3);
					return;
				}
			}
			locateResult = "FAIL " + said;
		} catch (Throwable t) {
			locateResult = "FAIL " + t;
			com.origin.client.OriginClient.LOGGER.warn("[panorama] world setup failed", t);
		}
	}

	private static void buildScene(MinecraftServer server, String at) {
		try {
			ServerLevel level = level(server);
			String[] xyz = at.split(" ");
			int x = Integer.parseInt(xyz[0]) + (int) arr("offset", 0, 0);
			int z = Integer.parseInt(xyz[2]) + (int) arr("offset", 1, 0);
			int locY = xyz[1].equals("~") ? level.getSeaLevel() : Integer.parseInt(xyz[1]);
			boolean floor = "floor".equals(str("ground", "surface"));
			int vista = num("vista", 0);
			if (vista > 0 && !floor) {
				// An overlook instead of a hollow: the highest open, level ground within `vista` blocks. Open = no
				// tree within 2 blocks of the camera (a cherry crown beside the eye filled every face, run
				// 40453214783); level = little drop 5 and 10 blocks out, so the mobs stand in view, not down a slope.
				int bx = x, bz = z;
				double best = Double.NEGATIVE_INFINITY;
				for (int dx = -vista; dx <= vista; dx += 4) {
					for (int dz = -vista; dz <= vista; dz += 4) {
						int h = groundAt(level, x + dx, z + dz);
						int drop = 0;
						for (int i = 0; i < 16; i++) {
							double a = Math.PI / 4 * i;
							int r = i < 8 ? 5 : 10;   // where the mobs stand (4-11 blocks out)
							int g = groundAt(level, x + dx + (int) Math.round(Math.cos(a) * r), z + dz + (int) Math.round(Math.sin(a) * r));
							drop = Math.max(drop, h - g);
						}
						double score = h - 1.5 * drop - (open(level, x + dx, h, z + dz) ? 0 : 1000);
						if (score > best) {
							best = score;
							bx = x + dx;
							bz = z + dz;
						}
					}
				}
				x = bx;
				z = bz;
			}
			int ground = floor ? floorAt(level, x, locY, z, num("headroom", 6)) : groundAt(level, x, z);
			double cy = ground + num("up", 2.0);
			double cx = x + 0.5, cz = z + 0.5;
			float yaw = (float) num("yaw", 45.0);
			String dim = str("dimension", "minecraft:overworld");
			CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput().withLevel(level);
			server.getCommands().performPrefixedCommand(src, String.format(Locale.ROOT,
					"execute in %s run tp @a %.2f %.2f %.2f %.1f 0", dim, cx, cy, cz, yaw));
			log(String.format(Locale.ROOT, "[panorama] camera at %.1f %.1f %.1f (ground %d) yaw %.0f in %s", cx, cy, cz, ground, yaw, dim));
			// Extra commands run AT the camera's ground point facing the front face: ~ and ^ work.
			CommandSourceStack here = src.withPosition(new Vec3(cx, ground, cz)).withRotation(new Vec2(0f, yaw));
			if (spot.has("commands")) {
				for (JsonElement c : spot.getAsJsonArray("commands")) {
					server.getCommands().performPrefixedCommand(here, c.getAsString());
				}
			}
			if (spot.has("mobs")) {
				for (JsonElement e : spot.getAsJsonArray("mobs")) {
					JsonObject mob = e.getAsJsonObject();
					double a = Math.toRadians(yaw + (mob.has("angle") ? mob.get("angle").getAsDouble() : 0));
					double d = mob.get("dist").getAsDouble();
					double mx = cx - Math.sin(a) * d, mz = cz + Math.cos(a) * d;
					int bx = (int) Math.floor(mx), bz = (int) Math.floor(mz);
					int my = floor ? floorAt(level, bx, ground + 4, bz, 3)
							: groundAt(level, bx, bz);
					double up = mob.has("up") ? mob.get("up").getAsDouble() : 0;
					// Facing back toward the camera, plus the spot's own turn for a 3/4 view.
					float face = (float) (yaw + 180 + (mob.has("angle") ? mob.get("angle").getAsDouble() : 0)
							+ (mob.has("turn") ? mob.get("turn").getAsDouble() : 30));
					String nbt = mob.has("nbt") ? mob.get("nbt").getAsString() : "";
					String tags = "NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:1b,Rotation:[" + face + "f,0f]";
					nbt = nbt.isEmpty() ? "{" + tags + "}" : "{" + tags + "," + nbt.substring(1);
					String cmd = String.format(Locale.ROOT, "summon %s %.2f %.2f %.2f %s", mob.get("id").getAsString(), mx, my + up, mz, nbt);
					server.getCommands().performPrefixedCommand(src, cmd);
					log("[panorama] " + cmd);
				}
			}
			camera = new double[]{cx, cy, cz};
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[panorama] scene failed", t);
			camera = new double[]{0, 0, 0};
		}
	}

	/**
	 * Heightmap of a column, generating its chunk first. Level.getHeight answers the build floor for a chunk
	 * that isn't loaded (it never loads one), which put the first camera at y -62 under the mountains.
	 */
	private static int height(ServerLevel level, Heightmap.Types type, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		return level.getHeight(type, x, z);
	}

	/**
	 * The ground a person would stand on: the no-leaves heightmap, then down through logs, leaves and mangrove roots
	 * (the heightmap stops on a trunk, which put "the highest ground" on top of a tree). Water counts as ground.
	 */
	private static int groundAt(ServerLevel level, int x, int z) {
		int y = height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y - 1, z);
		while (p.getY() > minY(level)) {
			var state = level.getBlockState(p);
			if (!(state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(Blocks.MANGROVE_ROOTS)
					|| state.is(Blocks.MUDDY_MANGROVE_ROOTS) || state.is(Blocks.MOSS_CARPET) || state.is(Blocks.VINE))) {
				break;
			}
			p.move(0, -1, 0);
		}
		return p.getY() + 1;
	}

	/** No leaves, logs or solid block within 2 blocks of the camera's eye (2 to 5 above the ground; lower is terrain). */
	private static boolean open(ServerLevel level, int x, int ground, int z) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = 2; dy <= 5; dy++) {
					p.set(x + dx, ground + dy, z + dz);
					var state = level.getBlockState(p);
					if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || !state.getCollisionShape(level, p).isEmpty()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * Cave / Nether floor: scanning down from just above `fromY`, the first solid top with `headroom` air blocks over
	 * it. Starts below the dimension's ceiling (the Nether's bedrock roof is a "floor" with open sky above it).
	 */
	private static int floorAt(ServerLevel level, int x, int fromY, int z, int headroom) {
		int top = Math.min(fromY + 12, minY(level) + level.getLogicalHeight() - 6);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int airRun = 0;
		for (int y = top; y > minY(level) + 1; y--) {
			p.set(x, y, z);
			boolean air = level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty();
			airRun = air ? airRun + 1 : 0;
			p.set(x, y - 1, z);
			if (airRun >= headroom && level.getBlockState(p).isFaceSturdy(level, p, net.minecraft.core.Direction.UP)) {
				return y;
			}
		}
		return fromY;
	}

	// ---- the per-version seams: the only lines that differ between the module copies ----

	private static LevelSettings levelSettings() {
		return new LevelSettings(LEVEL, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()),
				WorldDataConfiguration.DEFAULT);
	}

	private static int minY(ServerLevel level) {
		return level.getMinY();
	}

	/** Square window: FOV 90 both ways, exactly one cube face. */
	private static void sizeWindow(Minecraft mc) {
		org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), FACE, FACE);
		mc.resizeDisplay();
	}

	/** Writes screenshots/<name> under dir (async on the IO pool; check_shots.py confirms the file at the end). */
	private static void grab(Minecraft mc, String dir, String name) {
		Screenshot.grab(new File(dir), name, mc.getMainRenderTarget(), 1, c -> {
		});
	}

	private static void hideHud(Minecraft mc) {
		mc.options.hideGui = true;
	}

	private static Object screen(Minecraft mc) {
		return mc.screen;
	}

	private static void unpause(Minecraft mc) {
		if (mc.screen instanceof PauseScreen) {
			mc.setScreen(null);
		}
	}

	// ---- end of the seams ----

	private static ServerLevel level(MinecraftServer server) {
		String dim = str("dimension", "minecraft:overworld");
		ServerLevel l = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim)));   // seam: Identifier from 1.21.11
		return l != null ? l : server.getLevel(Level.OVERWORLD);
	}

	private static int num(String key, int def) {
		return spot != null && spot.has(key) ? spot.get(key).getAsInt() : def;
	}

	private static double num(String key, double def) {
		return spot != null && spot.has(key) ? spot.get(key).getAsDouble() : def;
	}

	private static String str(String key, String def) {
		return spot != null && spot.has(key) ? spot.get(key).getAsString() : def;
	}

	private static double arr(String key, int i, double def) {
		if (spot == null || !spot.has(key)) {
			return def;
		}
		JsonArray a = spot.getAsJsonArray(key);
		return i < a.size() ? a.get(i).getAsDouble() : def;
	}

	private static void log(String msg) {
		com.origin.client.OriginClient.LOGGER.info(msg);
	}
}
