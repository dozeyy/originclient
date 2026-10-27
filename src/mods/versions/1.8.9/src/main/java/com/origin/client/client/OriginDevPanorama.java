package com.origin.client.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.Collections;
import java.util.Locale;
import java.util.Random;

/**
 * DEV-ONLY: renders one title-screen panorama (ORIGIN_SHOTS_SET=panorama, .github/workflows/panoramas.yml),
 * the 1.8.9 (Forge, MCP names) take on 1.21.1's OriginDevPanorama. Same output: panorama_0..5.png square
 * faces in vanilla's order (0 = yaw f, 1 = f+90, 2 = f+180, 3 = f-90, 4 = up, 5 = down), the spot read from
 * ORIGIN_PANO_SPOT (tools/panorama/spots.json).
 *
 * What differs on 1.8.9, and why:
 *   * no /locate for biomes: the spot names a biome by its numeric id ("biome": 132 = flower forest) and
 *     the harness asks the world's own biome map (WorldChunkManager.findBiomePosition) for the nearest one;
 *   * no shader pack: OptiFine is the only shader layer here and it isn't in the CI dev game, so the faces
 *     are plain daytime vanilla (panoramas.yml skips the pack for "shader": false spots);
 *   * no "all sections built" signal: each face waits a fixed time (1.8.9 builds chunks fast);
 *   * window and capture through OriginDevShots' LWJGL 2 helpers (resizeWindow / grab).
 * Never runs in a player's game: only with ORIGIN_SHOTS set.
 */
final class OriginDevPanorama {
	private OriginDevPanorama() {
	}

	private static final int FACE = 1024;

	private static int phase = 0;
	private static long nextAt = 0;
	private static JsonObject spot;
	private static volatile String found;          // "x z" of the biome, or "FAIL ..."
	private static volatile double[] camera;       // feet x y z once the scene is built
	private static float[] aimRot = {0f, 0f};
	private static int face = 0;
	private static int warm = 0;

	static void tick(Minecraft mc, String dir) {
		long now = System.currentTimeMillis();
		if (camera != null && mc.thePlayer != null) {
			holdCamera(mc.thePlayer);
		}
		if (now < nextAt) {
			return;
		}
		nextAt = now + 250;
		try {
			if (mc.currentScreen instanceof GuiIngameMenu) {
				mc.displayGuiScreen(null);   // focus loss under Xvfb
			}
			switch (phase) {
				case 0: {
					spot = new JsonParser().parse(System.getenv("ORIGIN_PANO_SPOT")).getAsJsonObject();
					log("[panorama] spot " + spot.get("id").getAsString() + ": " + spot);
					// 1.8.9 re-opens the pause menu every tick the window lacks focus (always under Xvfb).
					mc.gameSettings.pauseOnLostFocus = false;
					mc.gameSettings.renderDistanceChunks = num("renderDistance", 12);
					mc.gameSettings.fovSetting = 90f;
					mc.gameSettings.viewBobbing = false;
					long seed = spot.has("seed") ? spot.get("seed").getAsLong() : 4242424242L;
					WorldSettings settings = new WorldSettings(seed, WorldSettings.GameType.CREATIVE, true, false,
							WorldType.DEFAULT).enableCommands();
					log("[panorama] creating world, seed " + seed);
					phase = 1;
					mc.launchIntegratedServer("origin-panorama-" + now, "origin-panorama", settings);
					return;
				}
				case 1: {
					final IntegratedServer server = mc.getIntegratedServer();
					if (mc.theWorld == null || mc.thePlayer == null || server == null || mc.currentScreen != null) {
						return;
					}
					server.addScheduledTask(new Runnable() {
						@Override
						public void run() {
							setupWorld(server);
						}
					});
					phase = 2;
					nextAt = now + 3000;
					return;
				}
				case 2: {
					if (found == null) {
						return;
					}
					if (found.startsWith("FAIL")) {
						throw new IllegalStateException("biome search failed: " + found);
					}
					final IntegratedServer server = mc.getIntegratedServer();
					final String at = found;
					server.addScheduledTask(new Runnable() {
						@Override
						public void run() {
							buildScene(server, at);
						}
					});
					phase = 3;
					return;
				}
				case 3: {
					if (camera == null) {
						return;
					}
					OriginDevShots.resizeWindow(mc, FACE, FACE);   // square: FOV 90 both ways, one cube face
					mc.gameSettings.hideGUI = true;
					warm = 0;
					aim(mc, 0);
					phase = 4;
					nextAt = now + 8000;   // the teleport + the scene arrive, chunks start building
					return;
				}
				case 4: {
					// Warm-up turn: every face once, so every direction's chunks are built before any capture.
					if (warm < 6) {
						aim(mc, warm++);
						nextAt = now + 4000;
						return;
					}
					log("[panorama] warm-up done; window " + mc.displayWidth + "x" + mc.displayHeight + " (no shader on 1.8.9)");
					face = 0;
					aim(mc, face);
					phase = 5;
					nextAt = now + 6000;
					return;
				}
				case 5: {
					if (mc.displayWidth != FACE || mc.displayHeight != FACE) {
						OriginDevShots.resizeWindow(mc, FACE, FACE);
						nextAt = now + 3000;
						return;
					}
					OriginDevShots.grab(mc, dir, "panorama_" + face + ".png");   // logs "[dev-shots] captured ..."
					if (++face < 6) {
						aim(mc, face);
						nextAt = now + 6000;
						return;
					}
					com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — 6 shots in {}", dir);
					phase = 6;
					mc.shutdown();
					return;
				}
				default:
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] step " + phase + " failed", t);
			phase = 6;
			mc.shutdown();
		}
	}

	/** Vanilla's face order (GuiMainMenu.drawPanorama). */
	private static void aim(Minecraft mc, int i) {
		float f = (float) dnum("yaw", 45.0);
		switch (i) {
			case 1: aimRot = new float[]{f + 90f, 0f}; break;
			case 2: aimRot = new float[]{f + 180f, 0f}; break;
			case 3: aimRot = new float[]{f - 90f, 0f}; break;
			case 4: aimRot = new float[]{f, -90f}; break;
			case 5: aimRot = new float[]{f, 90f}; break;
			default: aimRot = new float[]{f, 0f};
		}
		if (mc.thePlayer != null) {
			holdCamera(mc.thePlayer);
		}
	}

	/** Every tick: the exact rotation (no interpolation from the last one) and no drift. */
	private static void holdCamera(EntityPlayerSP p) {
		p.rotationYaw = aimRot[0];
		p.rotationPitch = aimRot[1];
		p.prevRotationYaw = aimRot[0];
		p.prevRotationPitch = aimRot[1];
		p.rotationYawHead = aimRot[0];
		p.prevRotationYawHead = aimRot[0];
		p.motionX = 0;
		p.motionY = 0;
		p.motionZ = 0;
	}

	// ---- server thread ----

	private static void run(IntegratedServer server, String cmd) {
		server.getCommandManager().executeCommand(server, cmd);
	}

	private static void setupWorld(IntegratedServer server) {
		try {
			String[] cmds = {"gamerule doDaylightCycle false", "gamerule doMobSpawning false", "gamerule doFireTick false",
					"gamerule randomTickSpeed 0", "gamerule sendCommandFeedback false", "gamerule commandBlockOutput false",
					"weather clear 1000000", "time set " + num("time", 3000), "gamemode 3 @a"};
			for (String c : cmds) {
				run(server, c);
			}
			WorldServer world = server.worldServerForDimension(0);
			BiomeGenBase biome = BiomeGenBase.getBiomeGenArray()[num("biome", 132)];
			BlockPos spawn = world.getSpawnPoint();
			for (int range = 256; range <= 4096; range *= 2) {
				BlockPos p = world.getWorldChunkManager().findBiomePosition(spawn.getX(), spawn.getZ(), range,
						Collections.singletonList(biome), new Random(world.getSeed()));
				if (p != null) {
					log("[panorama] biome " + biome.biomeName + " found at " + p.getX() + " " + p.getZ() + " (range " + range + ")");
					found = p.getX() + " " + p.getZ();
					return;
				}
			}
			found = "FAIL no " + biome.biomeName + " within 4096 blocks";
		} catch (Throwable t) {
			found = "FAIL " + t;
			com.origin.client.OriginClient.LOGGER.warn("[panorama] world setup failed", t);
		}
	}

	private static void buildScene(IntegratedServer server, String at) {
		try {
			WorldServer world = server.worldServerForDimension(0);
			String[] xz = at.split(" ");
			int x = Integer.parseInt(xz[0]) + (int) arr("offset", 0, 0);
			int z = Integer.parseInt(xz[1]) + (int) arr("offset", 1, 0);
			int vista = num("vista", 0);
			if (vista > 0) {
				// The highest ground within `vista` blocks: an overlook instead of a hollow.
				int bx = x, bz = z, by = Integer.MIN_VALUE;
				for (int dx = -vista; dx <= vista; dx += 4) {
					for (int dz = -vista; dz <= vista; dz += 4) {
						int h = ground(world, x + dx, z + dz);
						if (h > by) {
							by = h;
							bx = x + dx;
							bz = z + dz;
						}
					}
				}
				x = bx;
				z = bz;
			}
			int ground = ground(world, x, z);
			double cy = ground + dnum("up", 1.6);
			double cx = x + 0.5, cz = z + 0.5;
			float yaw = (float) dnum("yaw", 45.0);
			run(server, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f 0", cx, cy, cz, yaw));
			log(String.format(Locale.ROOT, "[panorama] camera at %.1f %.1f %.1f (ground %d) yaw %.0f", cx, cy, cz, ground, yaw));
			if (spot.has("mobs")) {
				for (JsonElement e : spot.getAsJsonArray("mobs")) {
					JsonObject mob = e.getAsJsonObject();
					double angle = mob.has("angle") ? mob.get("angle").getAsDouble() : 0;
					double a = Math.toRadians(yaw + angle);
					double d = mob.get("dist").getAsDouble();
					double mx = cx - Math.sin(a) * d, mz = cz + Math.cos(a) * d;
					int my = ground(world, (int) Math.floor(mx), (int) Math.floor(mz));
					double up = mob.has("up") ? mob.get("up").getAsDouble() : 0;
					// Facing back toward the camera, plus the spot's own turn for a 3/4 view.
					float faceYaw = (float) (yaw + 180 + angle + (mob.has("turn") ? mob.get("turn").getAsDouble() : 30));
					String nbt = mob.has("nbt") ? mob.get("nbt").getAsString() : "";
					String tags = "NoAI:1b,PersistenceRequired:1b,Silent:1b,Rotation:[" + faceYaw + "f,0f]";
					nbt = nbt.isEmpty() ? "{" + tags + "}" : "{" + tags + "," + nbt.substring(1);
					String cmd = String.format(Locale.ROOT, "summon %s %.2f %.2f %.2f %s", mob.get("id").getAsString(), mx, my + up, mz, nbt);
					run(server, cmd);
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
	 * The ground a person would stand on (the first air above it). World.getTopSolidOrLiquidBlock skips leaves
	 * but stops on a log, so a tree top would win the vista search; step down through trunks too. It loads
	 * (generates) the chunk first on a server world, so it never answers for an empty chunk.
	 */
	private static int ground(WorldServer world, int x, int z) {
		BlockPos p = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z));
		while (p.getY() > 1) {
			net.minecraft.block.Block below = world.getBlockState(p.down()).getBlock();
			if (below == Blocks.log || below == Blocks.log2 || below == Blocks.leaves || below == Blocks.leaves2
					|| (!below.getMaterial().blocksMovement() && !below.getMaterial().isLiquid())) {
				p = p.down();
			} else {
				break;
			}
		}
		return p.getY();
	}

	private static int num(String key, int def) {
		return spot != null && spot.has(key) ? spot.get(key).getAsInt() : def;
	}

	private static double dnum(String key, double def) {
		return spot != null && spot.has(key) ? spot.get(key).getAsDouble() : def;
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
