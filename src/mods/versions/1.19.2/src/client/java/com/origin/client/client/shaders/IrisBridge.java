package com.origin.client.client.shaders;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// All Iris access lives here, reflection-only and fully guarded: the bundled
// Iris version is pinned (gradle.properties), so these internal names are
// stable for what we ship, and if any call ever stops matching the tab
// degrades gracefully (APPLY falls back to opening Iris's own screen) instead
// of crashing the client.
public final class IrisBridge {
	private IrisBridge() {
	}

	private static Boolean installed;

	public static boolean installed() {
		if (installed == null) {
			installed = FabricLoader.getInstance().isModLoaded("iris");
		}
		return installed;
	}

	public static Path shaderpacksDir() {
		return FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
	}

	/** Shaderpack candidates: .zip files and directories in shaderpacks/. */
	public static List<String> listPacks() {
		List<String> packs = new ArrayList<>();
		try (var stream = Files.list(shaderpacksDir())) {
			stream.sorted().forEach(p -> {
				String name = p.getFileName().toString();
				if (Files.isDirectory(p) || name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
					packs.add(name);
				}
			});
		} catch (Exception ignored) {
			// missing folder = no packs yet
		}
		return packs;
	}

	// Iris moved its internals from net.coderbot.iris to net.irisshaders.iris in
	// 1.7.0. The Iris builds this module's players get are on the OLD root
	// (PerformanceModCatalog.Data.cs; the 1.20 jar also runs 1.20.1's Iris 1.7.6, on
	// the new one), and an irisshaders-only lookup silently made currentPack()
	// always null / apply() always fail here (2026-10-19). Try both roots; the
	// public API (net.irisshaders.iris.api.v0) never moved and is used as-is.
	private static final String[] IRIS_ROOTS = {"net.coderbot.iris.", "net.irisshaders.iris."};

	private static Class<?> irisClass(String suffix) throws ClassNotFoundException {
		ClassNotFoundException last = null;
		for (String root : IRIS_ROOTS) {
			try {
				return Class.forName(root + suffix);
			} catch (ClassNotFoundException e) {
				last = e;
			}
		}
		throw last;
	}

	private static Object irisConfig() throws Exception {
		Class<?> iris = irisClass("Iris");
		return iris.getMethod("getIrisConfig").invoke(null);
	}

	/** The active pack name, or null when shaders are disabled. */
	public static String currentPack() {
		try {
			Object cfg = irisConfig();
			boolean on = (boolean) cfg.getClass().getMethod("areShadersEnabled").invoke(cfg);
			if (!on) {
				return null;
			}
			@SuppressWarnings("unchecked")
			Optional<String> name = (Optional<String>) cfg.getClass().getMethod("getShaderPackName").invoke(cfg);
			return name.orElse(null);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Applies a pack (null = shaders off) and hot-reloads. True on success. */
	public static boolean apply(String packName) {
		try {
			Object cfg = irisConfig();
			cfg.getClass().getMethod("setShaderPackName", String.class).invoke(cfg, packName);
			try {
				cfg.getClass().getMethod("setShadersEnabled", boolean.class).invoke(cfg, packName != null);
			} catch (NoSuchMethodException ignored) {
				// some builds fold enable-state into setShaderPackName(null)
			}
			cfg.getClass().getMethod("save").invoke(cfg);
			irisClass("Iris").getMethod("reload").invoke(null);
			return true;
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Iris apply via config failed, falling back to Iris screen", t);
			return false;
		}
	}

	/**
	 * Hot-reloads Iris only if a shaderpack is actually in use. Used when a
	 * setting that feeds Iris's pipeline (shader performance mode) changes at
	 * runtime: the shadow renderer caches directive values at pipeline creation
	 * while the per-frame uniforms read them live, so without a rebuild the two
	 * disagree and shadows glitch until the next manual reload.
	 *
	 * Uses Iris's stable public API (net.irisshaders.iris.api.v0.IrisApi) for
	 * the "is a pack active" check — that package is present on EVERY Iris we
	 * ship, unlike the internal config path above which is net.coderbot on this
	 * module's Iris 1.6.4. The reload entry point still moved packages across
	 * versions, so both roots are tried. Fail-soft no-op when Iris is absent or
	 * shaders are off.
	 */
	public static void reloadIfPackActive() {
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object inst = api.getMethod("getInstance").invoke(null);
			boolean inUse = (boolean) api.getMethod("isShaderPackInUse").invoke(inst);
			if (!inUse) {
				return;
			}
		} catch (Throwable t) {
			return; // Iris absent or API changed — nothing to reload
		}
		// Iris.reload() is net.coderbot on 1.6.4, net.irisshaders on 1.7+.
		for (String cls : new String[]{"net.coderbot.iris.Iris", "net.irisshaders.iris.Iris"}) {
			try {
				Class.forName(cls).getMethod("reload").invoke(null);
				return;
			} catch (Throwable ignored) {
				// try the other package root
			}
		}
		com.origin.client.OriginClient.LOGGER.warn("Iris reload after settings change failed: no known Iris entry point");
	}

	/** Opens Iris's own shader screen (full per-pack options UI). */
	public static boolean openIrisScreen() {
		Minecraft mc = Minecraft.getInstance();
		try {
			Class<?> cls = irisClass("gui.screen.ShaderPackScreen");
			Screen screen = (Screen) cls.getConstructor(Screen.class).newInstance(mc.screen);
			mc.setScreen(screen);
			return true;
		} catch (Throwable t) {
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				Object inst = api.getMethod("getInstance").invoke(null);
				Object screen = api.getMethod("openMainIrisScreenObj", Object.class).invoke(inst, mc.screen);
				mc.setScreen((Screen) screen);
				return true;
			} catch (Throwable t2) {
				com.origin.client.OriginClient.LOGGER.warn("Could not open Iris screen", t2);
				return false;
			}
		}
	}
}
