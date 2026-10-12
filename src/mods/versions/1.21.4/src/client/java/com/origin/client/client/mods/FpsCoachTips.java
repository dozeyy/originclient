package com.origin.client.client.mods;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

// FPS Coach's tip catalogue: which settings are likely costing frames right now,
// ranked by how much they usually cost, each with the exact change "Try it"
// makes and the exact way back.
//
// Every change goes through the same door the player would use, so it persists
// the same way: vanilla's OptionInstance.set (which runs vanilla's own reaction
// -- chunk rebuild, VSync switch, frame limiter) then Options.save() (which also
// sends the usual client-information packet, exactly as closing Video Settings
// does). Sodium 0.6.13 draws its Video Settings from these same vanilla options;
// it has no public config API on 0.6.x, so no Sodium-only setting is coached.
//
// Shaders go through Iris's PUBLIC API only (net.irisshaders.iris.api.v0, all
// v0.0 methods), by reflection so a missing or changed Iris just means the
// shader tips never appear. The pack NAME isn't in that API, so it is read from
// Iris's own config file (read-only) purely for the tip's wording.
public final class FpsCoachTips {
	private FpsCoachTips() {
	}

	/** Applies a change; returns how to undo it exactly, or null if nothing changed. */
	public interface Change {
		Runnable apply(Minecraft mc);
	}

	/**
	 * One tip. `change` is the "Try it" label ("Render distance 24 → 14"); `apply`
	 * is null for advice-only tips (memory). `settleMs` is how long the game gets
	 * to settle after the change before FPS is measured again (chunk rebuilds,
	 * shader reloads).
	 */
	public record Tip(String id, int weight, String title, String detail, String change, Change apply, int settleMs) {
	}

	/** Every tip id, for "Reset Hidden Tips". */
	public static final String[] IDS = {"shaders", "shadermode", "fpslimit", "vsync", "renderdistance", "fabulous",
			"memory", "entities", "debug", "simdistance", "clouds", "particles", "biomeblend", "shadows"};

	private static final String HIDE_PREFIX = "fpscoach.hide.";

	public static boolean hidden(String id) {
		return Mods.metaBool(HIDE_PREFIX + id, false);
	}

	public static void hide(String id) {
		Mods.setMetaBool(HIDE_PREFIX + id, true);
	}

	public static int hiddenCount() {
		int n = 0;
		for (String id : IDS) {
			if (hidden(id)) {
				n++;
			}
		}
		return n;
	}

	public static void unhideAll() {
		for (String id : IDS) {
			if (hidden(id)) {
				Mods.setMetaBool(HIDE_PREFIX + id, false);
			}
		}
	}

	/**
	 * The tips that apply right now, most costly first, hidden ones left out.
	 * avgFps is the player's recent average (0 = unknown); usedMb/maxMb is the
	 * Java heap floor over the last ~20 s (0 = unknown).
	 */
	public static List<Tip> build(Minecraft mc, double avgFps, int target, long usedMb, long maxMb) {
		List<Tip> out = new ArrayList<>();
		Options o = mc.options;
		boolean shaders = Iris.shadersInUse();

		if (shaders) {
			String pack = Iris.packName();
			out.add(new Tip("shaders", 100,
					pack != null ? "Shaders are on (" + pack + ")" : "Shaders are on",
					"Shaders cost more FPS than any other setting.",
					"Shaders on → off", m -> shaders(false), 4000));
			if (!Mods.bool(Mods.PERFORMANCE_ID, "shaderPerformanceMode")) {
				out.add(new Tip("shadermode", 80, "Shader Performance Mode is off",
						"It draws shader shadows at half distance for a big FPS boost.",
						"Shader Performance Mode off → on", m -> shaderMode(true), 4000));
			}
		}

		int limit = o.framerateLimit().get();
		if (limit < 260 && limit < target) {
			int to = Math.min(260, (int) Math.ceil(target / 10.0) * 10);
			out.add(new Tip("fpslimit", 98, "Max framerate is set to " + limit,
					"That's below your target of " + target + ", so you can never reach it.",
					"Max framerate " + limit + " → " + (to >= 260 ? "Unlimited" : String.valueOf(to)),
					option(o.framerateLimit(), to), 1000));
		}

		int refresh = refreshRate(mc);
		if (o.enableVsync().get() && refresh > 0 && refresh < target) {
			out.add(new Tip("vsync", 96, "VSync holds you at " + refresh + " FPS",
					"It matches your screen's " + refresh + " Hz, which is below your target of " + target + ".",
					"VSync on → off", option(o.enableVsync(), false), 1000));
		}

		int rd = o.getEffectiveRenderDistance();
		if (rd > 12) {
			int to = Math.max(10, (int) Math.round(rd * 0.6));
			out.add(new Tip("renderdistance", 60 + 2 * (rd - 12), "Render distance " + rd + " is high for your PC",
					"Fewer chunks to draw. Far-away land just fades in a bit sooner.",
					"Render distance " + rd + " → " + to, option(o.renderDistance(), to), 3000));
		}

		if (o.graphicsMode().get() == GraphicsStatus.FABULOUS) {
			out.add(new Tip("fabulous", 55, "Fabulous graphics is on",
					"Fancy looks almost the same and runs much faster.",
					"Graphics Fabulous → Fancy", option(o.graphicsMode(), GraphicsStatus.FANCY), 3000));
		}

		if (maxMb > 0 && usedMb > maxMb * 0.85) {
			out.add(new Tip("memory", 50, "Minecraft is almost out of memory",
					"Using " + usedMb + " of " + maxMb + " MB. Raise RAM allocation in the Origin launcher's Settings.",
					null, null, 0));
		}

		if (mc.level != null && Mods.num(Mods.PERFORMANCE_ID, "entityDistance") > 50) {
			int n = mc.level.getEntityCount();
			if (n > 250) {
				double from = Mods.num(Mods.PERFORMANCE_ID, "entityDistance");
				out.add(new Tip("entities", 45, n + " mobs and items are loaded",
						"Origin can skip drawing the far-away ones.",
						"Entity Distance " + Math.round(from) + "% → 50%", m -> entityDistance(50), 1000));
			}
		}

		if (mc.getDebugOverlay().showDebugScreen()) {
			out.add(new Tip("debug", 40, "The F3 screen is open",
					"Its text and charts cost FPS. F3 closes it any time.",
					"F3 screen open → closed", m -> toggleDebug(m), 1000));
		}

		// Simulation distance only matters in singleplayer (on a server, the
		// server decides what keeps running).
		if (mc.hasSingleplayerServer()) {
			int sim = o.simulationDistance().get();
			if (sim > 12) {
				int to = Math.max(8, (int) Math.round(sim * 0.6));
				out.add(new Tip("simdistance", 30, "Simulation distance " + sim + " is high",
						"Fewer chunks around you keep running. Far-away farms and mobs pause.",
						"Simulation distance " + sim + " → " + to, option(o.simulationDistance(), to), 2000));
			}
		}

		if (o.cloudStatus().get() == CloudStatus.FANCY && rd >= 4) {
			out.add(new Tip("clouds", 20, "Clouds are set to Fancy",
					"Fast clouds are flat and much cheaper to draw.",
					"Clouds Fancy → Fast", option(o.cloudStatus(), CloudStatus.FAST), 1000));
		}

		// PER-VERSION: the ParticleStatus enum moved package in the 1.21.2+ line, so
		// it is reached through the option's own value instead of by name.
		var particles = o.particles();
		if (particles.get().name().equals("ALL")) {
			out.add(new Tip("particles", 15, "Particles are set to All",
					"Decreased drops some of the smoke, splashes and potion swirls.",
					"Particles All → Decreased", option(particles, sibling(particles.get(), "DECREASED")), 1000));
		}

		int blend = o.biomeBlendRadius().get();
		if (blend > 2) {
			String from = (blend * 2 + 1) + "x" + (blend * 2 + 1);
			out.add(new Tip("biomeblend", 15 + blend, "Biome blend " + from + " is high",
					"It smooths grass and water colours between biomes. 5x5 looks nearly the same.",
					"Biome blend " + from + " → 5x5", option(o.biomeBlendRadius(), 2), 3000));
		}

		// Shader packs draw their own shadows, so vanilla's are moot with one on.
		if (!shaders && o.entityShadows().get()) {
			out.add(new Tip("shadows", 10, "Entity shadows are on",
					"The small round shadows under mobs and players.",
					"Entity shadows on → off", option(o.entityShadows(), false), 1000));
		}

		out.removeIf(t -> hidden(t.id()));
		out.sort(Comparator.comparingInt(Tip::weight).reversed());
		return out;
	}

	/** Another constant of the same enum as `sample`, by name. */
	private static <E extends Enum<E>> E sibling(E sample, String name) {
		return Enum.valueOf(sample.getDeclaringClass(), name);
	}

	// ---- changes ----

	/** A vanilla option change through OptionInstance.set + Options.save. */
	private static <T> Change option(OptionInstance<T> opt, T to) {
		return mc -> {
			T from = opt.get();
			if (Objects.equals(from, to)) {
				return null;
			}
			opt.set(to);
			mc.options.save();
			return () -> {
				opt.set(from);
				mc.options.save();
			};
		};
	}

	private static Runnable shaders(boolean on) {
		if (!Iris.setEnabled(on)) {
			return null;
		}
		return () -> Iris.setEnabled(!on);
	}

	private static Runnable shaderMode(boolean on) {
		boolean from = Mods.bool(Mods.PERFORMANCE_ID, "shaderPerformanceMode");
		if (from == on) {
			return null;
		}
		Mods.set(Mods.PERFORMANCE_ID, "shaderPerformanceMode", on);
		Iris.reloadIfInUse();   // the pack picks the new shadow distance up on reload
		return () -> {
			Mods.set(Mods.PERFORMANCE_ID, "shaderPerformanceMode", from);
			Iris.reloadIfInUse();
		};
	}

	private static Runnable entityDistance(double to) {
		double from = Mods.num(Mods.PERFORMANCE_ID, "entityDistance");
		if (from == to) {
			return null;
		}
		Mods.set(Mods.PERFORMANCE_ID, "entityDistance", to);
		return () -> Mods.set(Mods.PERFORMANCE_ID, "entityDistance", from);
	}

	private static Runnable toggleDebug(Minecraft mc) {
		if (!mc.getDebugOverlay().showDebugScreen()) {
			return null;
		}
		mc.getDebugOverlay().toggleOverlay();
		return () -> {
			if (!mc.getDebugOverlay().showDebugScreen()) {
				mc.getDebugOverlay().toggleOverlay();
			}
		};
	}

	private static int refreshRate(Minecraft mc) {
		try {
			return mc.getWindow().getRefreshRate();
		} catch (Throwable t) {
			return 0;
		}
	}

	// ---- Iris (public API v0, reflection-only, fail-soft) ----

	static final class Iris {
		private Iris() {
		}

		private static Object api() throws Exception {
			if (!FabricLoader.getInstance().isModLoaded("iris")) {
				return null;
			}
			Class<?> cls = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			return cls.getMethod("getInstance").invoke(null);
		}

		/** True when a shader pack is actually rendering (IrisApi.isShaderPackInUse). */
		static boolean shadersInUse() {
			try {
				Object api = api();
				return api != null && (boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
			} catch (Throwable t) {
				return false;
			}
		}

		/** IrisApiConfig.setShadersEnabledAndApply: saves Iris's config and reloads. */
		static boolean setEnabled(boolean on) {
			try {
				Object api = api();
				if (api == null) {
					return false;
				}
				Object cfg = api.getClass().getMethod("getConfig").invoke(api);
				Class<?> cfgType = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
				cfgType.getMethod("setShadersEnabledAndApply", boolean.class).invoke(cfg, on);
				return true;
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.warn("FPS Coach: couldn't switch shaders through Iris's API", t);
				return false;
			}
		}

		/** Re-applies the current state, which makes Iris rebuild the active pack. */
		static void reloadIfInUse() {
			if (shadersInUse()) {
				setEnabled(true);
			}
		}

		/** The selected pack's file name from config/iris.properties, or null. */
		static String packName() {
			try {
				Path file = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
				if (!Files.isRegularFile(file)) {
					return null;
				}
				Properties p = new Properties();
				try (Reader r = Files.newBufferedReader(file)) {
					p.load(r);
				}
				String name = p.getProperty("shaderPack");
				if (name == null || name.isBlank()) {
					return null;
				}
				name = name.trim();
				if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
					name = name.substring(0, name.length() - 4);
				}
				return name.length() > 28 ? name.substring(0, 27) + "…" : name;
			} catch (Throwable t) {
				return null;
			}
		}
	}
}
