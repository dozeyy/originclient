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
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
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

	/**
	 * Each face renders at FOV 98 in a square WINDOW px window and finish.py crops the centre FACE px: 1024 *
	 * tan(49 deg) = 1177.98, so the crop is exactly 90 degrees. Every screen-edge effect a shader pack draws
	 * (vignette, SSAO and bloom fall-off, TAA edge clamping) lands outside the crop. A shader's vignette darkening
	 * each face's border was the black vertical seam in the sky (seam_check.py measured the border at 0.72-0.84
	 * of the brightness 24 px in, 2026-10-27). finish.py reads the numbers from panorama_meta.json.
	 */
	private static final int FACE = 1024;
	private static final int FOV = 98;
	private static final int WINDOW = 1178;
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
					mc.options.fov().set(FOV);
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
					// Local renders (tools/panorama/local_render.py) gate the capture on a file: it appears once the far-terrain
					// mod (Distant Horizons) has generated every direction. No gate in CI.
					String gate = System.getenv("ORIGIN_PANO_WAIT");
					if (gate != null && !new File(gate).exists() && now - phaseAt < 40 * 60_000L) {
						if (now - gateLogAt > 30_000) {
							gateLogAt = now;
							log("[panorama] waiting for far terrain (" + gate + ")");
						}
						nextAt = now + 1000;
						return;
					}
					writeMeta(dir);
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
					if (mc.getWindow().getWidth() != WINDOW || mc.getWindow().getHeight() != WINDOW) {
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

	private static long gateLogAt = 0;

	/** screenshots/panorama_meta.json: how finish.py crops the faces, and where the camera stood. */
	private static void writeMeta(String dir) {
		try {
			java.nio.file.Path p = java.nio.file.Path.of(dir, "screenshots", "panorama_meta.json");
			java.nio.file.Files.createDirectories(p.getParent());
			java.nio.file.Files.writeString(p, String.format(Locale.ROOT,
					"{\"window\":%d,\"face\":%d,\"fov\":%d,\"camera\":[%.2f,%.2f,%.2f],\"yaw\":%.1f,\"spot\":\"%s\"}\n",
					WINDOW, FACE, FOV, camera[0], camera[1], camera[2], yaw0, spot.get("id").getAsString()));
		} catch (Exception e) {
			com.origin.client.OriginClient.LOGGER.warn("[panorama] meta not written", e);
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
		float f = yaw0;
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
	/** Face 0's yaw: the spot's "yaw", or the one chooseOpen picked. Set on the server thread before `camera`. */
	private static volatile float yaw0 = 45f;

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
			// "at": [x, z] or [x, y, z] -- a researched seed's coordinates. With a "biome" too, the nearest such biome
			// is located FROM there (a seed page's coordinates were off by 100+ blocks for 1.21.1's cherry grove, so
			// the point is a hint); without one, the point is used as is.
			BlockPos origin = server.getPlayerList().getPlayers().get(0).blockPosition();   // near the world spawn (ServerLevel.getSharedSpawnPos is gone by 1.21.10)
			String locate = str("locate", "");
			if (spot.has("at")) {
				JsonArray at = spot.getAsJsonArray("at");
				String start = at.size() > 2 ? at.get(0).getAsInt() + " " + at.get(1).getAsInt() + " " + at.get(2).getAsInt()
						: at.get(0).getAsInt() + " ~ " + at.get(1).getAsInt();
				log("[panorama] start at " + start);
				if (!spot.has("biome")) {
					locateResult = start;
					return;
				}
				origin = new BlockPos(at.get(0).getAsInt(), at.size() > 2 ? at.get(1).getAsInt() : level.getSeaLevel(), at.get(at.size() - 1).getAsInt());
				locate = "biome " + str("biome", "").split(Pattern.quote("|"))[0];
			}
			List<String> said = SAID;
			CommandSource rec = REC = new CommandSource() {
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
			CommandSourceStack locSrc = server.createCommandSourceStack().withSource(rec).withLevel(level)
					.withPosition(Vec3.atCenterOf(origin));
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
			// /locate biome answers a 3D biome cell, which can sit under a hill or in a ravine wall whose surface is
			// another biome (1.19's mangrove swamp: a jungle hillside, run 40455429322). On the surface, walk out to
			// the nearest column whose SURFACE is the biome, and keep the vista search inside it.
			String biome = !floor && str("locate", "").startsWith("biome ") ? str("locate", "").substring(6).trim() : null;
			if (biome != null && !surfaceIs(level, x, z, biome)) {
				search:
				for (int r = 8; r <= 128; r += 8) {
					for (int dx = -r; dx <= r; dx += 8) {
						for (int dz = -r; dz <= r; dz += 8) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) == r && surfaceIs(level, x + dx, z + dz, biome)) {
								log("[panorama] surface " + biome + " found " + r + " blocks out");
								x += dx;
								z += dz;
								break search;
							}
						}
					}
				}
			}
			int vista = num("vista", 0);
			float autoYaw = (float) num("yaw", 45.0);
			if (spot.has("open") && !floor) {
				String want = spot.has("biome") ? str("biome", "") : biome;
				double[] pick = chooseOpen(level, x, z, want, num("open", 48));
				x = (int) pick[0];
				z = (int) pick[1];
				autoYaw = (float) pick[2];
			} else if (vista > 0 && !floor) {
				// An overlook instead of a hollow: the highest open, level ground within `vista` blocks. Open = no
				// tree within 2 blocks of the camera (a cherry crown beside the eye filled every face, run
				// 40453214783); level = little drop 5 and 10 blocks out, so the mobs stand in view, not down a slope.
				// Per spot: maxRise keeps the camera in the located biome (a mangrove swamp's highest open point
				// was a jungle hill), flatWeight 0 lets a mountain spot stand on a peak.
				int base = groundAt(level, x, z);
				int maxRise = num("maxRise", 1000);
				double flatWeight = num("flatWeight", 1.5);
				int bx = x, bz = z;
				double best = Double.NEGATIVE_INFINITY;
				for (int dx = -vista; dx <= vista; dx += 4) {
					for (int dz = -vista; dz <= vista; dz += 4) {
						int h = groundAt(level, x + dx, z + dz);
						if (h > base + maxRise || (biome != null && !surfaceIs(level, x + dx, z + dz, biome))) {
							continue;
						}
						int drop = 0;
						for (int i = 0; i < 16; i++) {
							double a = Math.PI / 4 * i;
							int r = i < 8 ? 5 : 10;   // where the mobs stand (4-11 blocks out)
							int g = groundAt(level, x + dx + (int) Math.round(Math.cos(a) * r), z + dz + (int) Math.round(Math.sin(a) * r));
							drop = Math.max(drop, h - g);
						}
						double score = h - flatWeight * drop - (open(level, x + dx, h, z + dz) ? 0 : 1000);
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
			if (floor) {
				// Underground the camera must not stand against a stem or a wall (1.16's crimson stem filled the corner of
				// three faces, 2026-10-27): the nearest floor spot within 10 blocks with nothing solid within 2 blocks of
				// the body and the eye.
				int[] clear = null;
				for (int r = 0; r <= 10 && clear == null; r++) {
					for (int dx = -r; dx <= r && clear == null; dx++) {
						for (int dz = -r; dz <= r && clear == null; dz++) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
								continue;
							}
							int fy = floorAt(level, x + dx, locY, z + dz, num("headroom", 6));
							if (floorClear(level, x + dx, fy, z + dz)) {
								clear = new int[]{x + dx, z + dz};
							}
						}
					}
				}
				if (clear != null) {
					x = clear[0];
					z = clear[1];
				} else {
					log("[panorama] no clear floor spot within 10 blocks; keeping the located one");
				}
			}
			int ground = floor ? floorAt(level, x, locY, z, num("headroom", 6)) : groundAt(level, x, z);
			// "up": feet above the ground. 0 = standing on it (eye at +1.62, the player's own view).
			double cy = ground + num("up", 0.0);
			double cx = x + 0.5, cz = z + 0.5;
			float yaw = spot.has("yaw") ? (float) num("yaw", 45.0) : autoYaw;
			if (spot.has("lookAt")) {   // [x, z]: face 0 looks at this point (a temple, a village well)
				yaw = (float) Math.toDegrees(Math.atan2(-(arr("lookAt", 0, 0) - (x + 0.5)), arr("lookAt", 1, 0) - (z + 0.5)));
			}
			yaw0 = yaw;
			String dim = str("dimension", "minecraft:overworld");
			CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput().withLevel(level);
			server.getCommands().performPrefixedCommand(src, String.format(Locale.ROOT,
					"execute in %s run tp @a %.2f %.2f %.2f %.1f 0", dim, cx, cy, cz, yaw));
			log(String.format(Locale.ROOT, "[panorama] camera at %.1f %.1f %.1f (ground %d) yaw %.0f in %s", cx, cy, cz, ground, yaw, dim));
			// Extra commands run AT the camera's ground point facing the front face: ~ and ^ work.
			// Their answers are logged: a failed fill was silent before (26.2's outcrop never showed, run 40455429322).
			// /fill refuses any box touching a chunk that isn't loaded ("That position is not loaded"): the cave light
			// fills all failed that way and the lush and sulfur caves rendered black (run 40461789308). Load every
			// chunk within 2 of the camera first, synchronously, on this (server) thread.
			for (int ccx = (x >> 4) - 2; ccx <= (x >> 4) + 2; ccx++) {
				for (int ccz = (z >> 4) - 2; ccz <= (z >> 4) + 2; ccz++) {
					level.getChunk(ccx, ccz);
				}
			}
			// Tall grass, ferns and flowers right at the camera fill the lower half of a face at eye height (1.20's cherry
			// meadow, 2026-10-27): clear plants within 3 blocks. Only replaceable non-fluid blocks (no trees, no terrain).
			if (!floor) {
				BlockPos.MutableBlockPos cp = new BlockPos.MutableBlockPos();
				int cleared = 0;
				for (int dx = -3; dx <= 3; dx++) {
					for (int dz = -3; dz <= 3; dz++) {
						for (int dy = -1; dy <= 2; dy++) {
							cp.set(x + dx, ground + dy, z + dz);
							var st = level.getBlockState(cp);
							if (!st.isAir() && st.canBeReplaced() && level.getFluidState(cp).isEmpty()) {
								level.setBlock(cp, Blocks.AIR.defaultBlockState(), 2);
								cleared++;
							}
						}
					}
				}
				log("[panorama] cleared " + cleared + " plants around the camera");
			}
			CommandSourceStack here = server.createCommandSourceStack().withSource(REC).withLevel(level)
					.withPosition(new Vec3(cx, ground, cz)).withRotation(new Vec2(0f, yaw));
			if (spot.has("commands")) {
				for (JsonElement c : spot.getAsJsonArray("commands")) {
					SAID.clear();
					server.getCommands().performPrefixedCommand(here, c.getAsString());
					log("[panorama] " + c.getAsString() + " -> " + String.join(" / ", SAID));
				}
			}
			// "light": [[dy, level, radius], ...] fills every air block of a square layer (radius, default 14) dy above the camera's ground with an
			// invisible light block, so a cave scene is lit under the shader. Placed here, not by /fill, which refused
			// the not-yet-loaded chunks (run 40461789308).
			if (spot.has("light")) {
				BlockPos.MutableBlockPos lp = new BlockPos.MutableBlockPos();
				for (JsonElement e : spot.getAsJsonArray("light")) {
					int dy = e.getAsJsonArray().get(0).getAsInt();
					int lvl = e.getAsJsonArray().get(1).getAsInt();
					int rad = e.getAsJsonArray().size() > 2 ? e.getAsJsonArray().get(2).getAsInt() : 14;
					var lightState = Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, lvl);
					int placed = 0;
					for (int dx = -rad; dx <= rad; dx++) {
						for (int dz = -rad; dz <= rad; dz++) {
							lp.set(x + dx, ground + dy, z + dz);
							if (level.getBlockState(lp).isAir()) {
								level.setBlock(lp, lightState, 2);
								placed++;
							}
						}
					}
					log("[panorama] light level " + lvl + " at +" + dy + ": " + placed + " blocks");
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

	/** Top of whatever stands in a column (terrain, trunks, leaves, water): what a view ray hits. Cached per column. */
	private static final java.util.Map<Long, Integer> TOPS = new java.util.HashMap<>();

	private static int topAt(ServerLevel level, int x, int z) {
		return TOPS.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), k -> height(level, Heightmap.Types.MOTION_BLOCKING, x, z));
	}

	/**
	 * The ground spot with the clearest all-round view near (x0, z0), and face 0's yaw. Will 2026-10-27: no face
	 * blocked by a nearby cliff, wall or trunk (1.21.1's old panorama had a cliff filling the back faces), camera on
	 * the ground, the update's biome in view. Every column on a 4-block grid within `radius` with dry ground and
	 * open sky over the head is scored:
	 *   - obstruction: in 16 directions, the steepest angle above the eye of anything 2-40 blocks out (trees count);
	 *     every degree over 6 costs 1 point (a cliff 10 blocks off, rising 20 blocks, costs ~57);
	 *   - biome: the share of surface samples (8-48 blocks out, 16 directions) in `biome`, worth up to 150, and a
 *     spot with at least a 25% share always beats one without;
 *   - bump: 4 points per column within 2 blocks that rises to the feet or higher (a step filling a face);
	 *   - height: half a point per block above the ground 32 blocks around (an overlook sees further).
	 * Face 0 then looks where the biome shows most and the view is clearest (a 90-degree window of directions).
	 * Returns {x, z, yaw}.
	 */
	private static double[] chooseOpen(ServerLevel level, int x0, int z0, String biome, int radius) {
		double best = Double.NEGATIVE_INFINITY;
		double[] pick = {x0, z0, num("yaw", 45.0)};
		String bestWhy = "";
		// "wet": the update's biome is mostly shallow water (mangrove swamp): the camera may stand on the water surface.
		boolean wet = spot.has("wet") && spot.get("wet").getAsBoolean();
		// A spot where the biome fills at least a quarter of the view beats any spot where it doesn't (1.19 picked a 6%
		// plains meadow next to the mangroves, 2026-10-27).
		boolean bestInBiome = false;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx += 4) {
			for (int dz = -radius; dz <= radius; dz += 4) {
				int cx = x0 + dx, cz = z0 + dz;
				int g = groundAt(level, cx, cz);
				p.set(cx, g - 1, cz);
				if ((!wet && !level.getFluidState(p).isEmpty()) || !level.getFluidState(p.move(0, 1, 0)).isEmpty() || topAt(level, cx, cz) > g + 1) {
					continue;   // standing in water, or under a canopy
				}
				double eye = g + 1.62;
				double[] obs = new double[16];
				int[] hits = new int[16];
				double penalty = 0, around = 0;
				for (int i = 0; i < 16; i++) {
					double a = Math.toRadians(i * 22.5);
					double sx = -Math.sin(a), sz = Math.cos(a);   // Minecraft yaw: 0 = +z (south), 90 = -x (west)
					double steep = -90, near = -90;
					for (int d = 2; d <= 40; d += 2) {
						int h = topAt(level, cx + (int) Math.round(sx * d), cz + (int) Math.round(sz * d));
						double ang = Math.toDegrees(Math.atan2(h - eye, d));
						steep = Math.max(steep, ang);
						if (d <= 12) {
							near = Math.max(near, ang);
						}
					}
					obs[i] = steep;
					// Close walls (snow steps, a trunk, a bank) fill a face: they cost three times as much.
					penalty += Math.max(0, steep - 6) + 2 * Math.max(0, near - 3);
					around += topAt(level, cx + (int) Math.round(sx * 32), cz + (int) Math.round(sz * 32));
					if (biome != null) {
						for (int d : new int[]{8, 16, 32, 48}) {
							if (surfaceIs(level, cx + (int) Math.round(sx * d), cz + (int) Math.round(sz * d), biome)) {
								hits[i]++;
							}
						}
					}
				}
				int total = 0;
				for (int h : hits) {
					total += h;
				}
				double share = biome == null ? 1 : total / 64.0;
				double rise = Math.max(-10, Math.min(6, g - around / 16));
				// The mobs stand 4-11 blocks out: ground that falls away there hides them below the frame.
				double slope = 0;
				for (int i = 0; i < 16; i++) {
					double a = Math.toRadians(i * 22.5);
					slope += Math.abs(g - groundAt(level, cx + (int) Math.round(-Math.sin(a) * 7), cz + (int) Math.round(Math.cos(a) * 7)));
				}
				// A single block at feet height right beside the camera fills a face's lower half (1.20's dirt step,
				// 2026-10-27): 4 each within 2 blocks. The 16 rays start 2 blocks out and miss both cases.
				// Anything reaching eye height within 3 blocks (1.18's snow pillar filled two faces) is a wall: 30 each.
				double bump = 0;
				for (int bx = -3; bx <= 3; bx++) {
					for (int bz = -3; bz <= 3; bz++) {
						int t = topAt(level, cx + bx, cz + bz);
						if ((bx != 0 || bz != 0) && t > g) {
							bump += t > g + 1 ? 30 : (Math.abs(bx) <= 2 && Math.abs(bz) <= 2 ? 4 : 0);
						}
					}
				}
				// The update's biome must carry the view: 150 points for a full share (60 let a 3% cherry share win).
				double score = 150 * share - penalty + 0.5 * rise - slope - bump;
				boolean inBiome = share >= 0.25;
				if (inBiome && !bestInBiome || inBiome == bestInBiome && score > best) {
					best = score;
					bestInBiome = inBiome;
					// Face 0: the 90-degree window (5 directions) with the most biome and the least obstruction.
					int bestDir = 0;
					double dirBest = Double.NEGATIVE_INFINITY;
					for (int i = 0; i < 16; i++) {
						double s = 0;
						for (int k = -2; k <= 2; k++) {
							int j = (i + k + 16) % 16;
							s += 3 * hits[j] - Math.max(0, obs[j] - 6);
						}
						if (s > dirBest) {
							dirBest = s;
							bestDir = i;
						}
					}
					double worst = -90;
					for (double o : obs) {
						worst = Math.max(worst, o);
					}
					pick = new double[]{cx, cz, bestDir * 22.5};
					bestWhy = String.format(Locale.ROOT, "score %.1f, steepest %.1f deg, obstruction %.1f, biome %.0f%%, rise %.1f, slope %.0f, bump %.0f",
							score, worst, penalty, share * 100, rise, slope, bump);
				}
			}
		}
		log(String.format(Locale.ROOT, "[panorama] open view at %d %d yaw %.1f (%s)", (int) pick[0], (int) pick[1], pick[2], bestWhy));
		return pick;
	}

	/** What the commands run through REC answered (the locate result, fill counts, errors). */
	private static final List<String> SAID = new ArrayList<>();
	private static CommandSource REC;

	/** Whether the biome at the ground surface of this column is `id`, or one of "a|b|c". */
	private static boolean surfaceIs(ServerLevel level, int x, int z, String id) {
		String name = level.getBiome(new BlockPos(x, groundAt(level, x, z), z)).getRegisteredName();
		for (String b : id.split(Pattern.quote("|"))) {
			if (b.equals(name)) {
				return true;
			}
		}
		return false;
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
	/** Nothing with collision (stems, walls, pillars) within 2 blocks of the body and the eye, standing at y. */
	private static boolean floorClear(ServerLevel level, int x, int y, int z) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = 0; dy <= 2; dy++) {
					p.set(x + dx, y + dy, z + dz);
					if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty() || !level.getFluidState(p).isEmpty()) {
						return false;
					}
				}
			}
		}
		return true;
	}

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
		org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), WINDOW, WINDOW);
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
		ServerLevel l = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));   // seam: Identifier from 1.21.11
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
