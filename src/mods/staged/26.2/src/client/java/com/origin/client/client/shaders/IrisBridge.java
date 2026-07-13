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

	private static Object irisConfig() throws Exception {
		Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
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
			Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
			return true;
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Iris apply via config failed, falling back to Iris screen", t);
			return false;
		}
	}

	/**
	 * Hot-reloads Iris only if a pack is actually active. Used when a setting
	 * that feeds Iris's pipeline (shader performance mode) changes at runtime:
	 * ShadowRenderer caches directive values at pipeline creation while the
	 * per-frame uniforms read them live, so without a rebuild the two disagree
	 * and shadows glitch until the next manual reload. Fail-soft no-op when
	 * Iris is absent or shaders are off.
	 */
	public static void reloadIfPackActive() {
		if (currentPack() == null) {
			return;
		}
		try {
			Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Iris reload after settings change failed", t);
		}
	}

	/** Opens Iris's own shader screen (full per-pack options UI). */
	public static boolean openIrisScreen() {
		Minecraft mc = Minecraft.getInstance();
		try {
			Class<?> cls = Class.forName("net.irisshaders.iris.gui.screen.ShaderPackScreen");
			Screen screen = (Screen) cls.getConstructor(Screen.class).newInstance(com.origin.client.client.OriginScreenState.current);
			mc.setScreenAndShow(screen);
			return true;
		} catch (Throwable t) {
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				Object inst = api.getMethod("getInstance").invoke(null);
				Object screen = api.getMethod("openMainIrisScreenObj", Object.class).invoke(inst, com.origin.client.client.OriginScreenState.current);
				mc.setScreenAndShow((Screen) screen);
				return true;
			} catch (Throwable t2) {
				com.origin.client.OriginClient.LOGGER.warn("Could not open Iris screen", t2);
				return false;
			}
		}
	}
}
