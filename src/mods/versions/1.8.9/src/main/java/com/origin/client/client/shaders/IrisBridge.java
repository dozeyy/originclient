package com.origin.client.client.shaders;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// All shader-loader access lives here, reflection-only and fully guarded.
//
// 1.8.9: OPTIFINE EDITION. Shaders on 1.8.9 are OptiFine's (the launcher
// installs it quietly; there is no Iris for this era). The class keeps the
// IrisBridge name and its public shape because it is the cross-area contract
// (the mod menu calls currentPack() / reloadIfPackActive() on every version).
// OptiFine is not a compile dependency and is not obfuscated, so its own names
// are used directly; its packages moved across builds, so every class is tried
// under each root it has used:
//   net.optifine.shaders.*        (HD_U_M builds)
//   shadersmod.client.*           (older builds, the merged ShadersMod)
// OptiFine member names below are 1.8.9 NAME UNVERIFIED (no OptiFine jar on the
// lookup classpath): every lookup is optional, a miss = that feature off.
public final class IrisBridge {
	private IrisBridge() {
	}

	private static final String[] SHADERS_CLASSES = {
			"net.optifine.shaders.Shaders", "shadersmod.client.Shaders"};
	private static final String[] GUI_SHADERS_CLASSES = {
			"net.optifine.shaders.gui.GuiShaders", "net.optifine.gui.GuiShaders", "shadersmod.client.GuiShaders"};

	private static volatile Class<?> shaders;
	private static volatile boolean shadersLooked;

	private static Class<?> shadersClass() {
		if (!shadersLooked) {
			shaders = firstClass(SHADERS_CLASSES);
			shadersLooked = true;
		}
		return shaders;
	}

	private static Class<?> firstClass(String[] names) {
		for (String n : names) {
			try {
				return Class.forName(n, false, IrisBridge.class.getClassLoader());
			} catch (Throwable ignored) {
				// next package root
			}
		}
		return null;
	}

	/** True when OptiFine's shader support is present. */
	public static boolean installed() {
		return shadersClass() != null;
	}

	/** OptiFine reads packs from <gameDir>/shaderpacks (Shaders.shaderpacksdir). */
	public static File shaderpacksDir() {
		return new File(Minecraft.getMinecraft().mcDataDir, "shaderpacks");
	}

	/** Shaderpack candidates: .zip files and directories in shaderpacks/. */
	public static List<String> listPacks() {
		List<String> packs = new ArrayList<>();
		try {
			File[] files = shaderpacksDir().listFiles();
			if (files != null) {
				Arrays.sort(files);
				for (File f : files) {
					String name = f.getName();
					if (f.isDirectory() || name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
						packs.add(name);
					}
				}
			}
		} catch (Throwable ignored) {
			// missing folder = no packs yet
		}
		return packs;
	}

	private static Object staticField(Class<?> c, String... names) {
		if (c == null) {
			return null;
		}
		for (String n : names) {
			try {
				Field f = c.getDeclaredField(n);
				f.setAccessible(true);
				return f.get(null);
			} catch (Throwable ignored) {
				// next name
			}
		}
		return null;
	}

	/** 1.8.9: true while an OptiFine shader pack is loaded and rendering. Menu
	 *  Blur steps aside then (OptiFine's composite pipeline owns the frame). */
	public static boolean shadersActive() {
		Object loaded = staticField(shadersClass(), "shaderPackLoaded");
		return loaded instanceof Boolean && (Boolean) loaded;
	}

	/** The active pack name, or null when shaders are off. OptiFine stores "OFF"
	 *  (and "(internal)" for its built-in pack) as the selection when no pack is in use. */
	public static String currentPack() {
		try {
			if (!shadersActive()) {
				return null;
			}
			Object name = staticField(shadersClass(), "currentShaderName", "currentshadername");
			if (!(name instanceof String)) {
				return null;
			}
			String s = (String) name;
			return s.isEmpty() || "OFF".equals(s) || "(internal)".equals(s) ? null : s;
		} catch (Throwable t) {
			return null;
		}
	}

	/** Applies a pack (null = shaders off) the way OptiFine's own screen does:
	 *  setShaderPack(name), then uninit() so the pipeline rebuilds on the next
	 *  frame. True on success. */
	public static boolean apply(String packName) {
		try {
			Class<?> c = shadersClass();
			if (c == null) {
				return false;
			}
			Method set = c.getMethod("setShaderPack", String.class);
			set.invoke(null, packName == null ? "OFF" : packName);
			uninit(c);
			return true;
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("OptiFine shader apply failed, falling back to OptiFine's screen", t);
			return false;
		}
	}

	/**
	 * Rebuilds the shader pipeline only if a pack is actually in use (Iris's
	 * reloadIfPackActive role: a setting feeding the pack changed at runtime).
	 * OptiFine re-initialises lazily after uninit(). Fail-soft no-op otherwise.
	 */
	public static void reloadIfPackActive() {
		try {
			if (shadersActive()) {
				uninit(shadersClass());
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("OptiFine shader reload failed", t);
		}
	}

	private static void uninit(Class<?> c) throws Exception {
		if (c != null) {
			c.getMethod("uninit").invoke(null);
		}
	}

	/** Opens OptiFine's own shader screen (GuiShaders(parent, settings)). */
	public static boolean openIrisScreen() {
		Minecraft mc = Minecraft.getMinecraft();
		try {
			Class<?> gui = firstClass(GUI_SHADERS_CLASSES);
			if (gui == null) {
				return false;
			}
			Constructor<?> ctor = gui.getConstructor(GuiScreen.class, GameSettings.class);
			mc.displayGuiScreen((GuiScreen) ctor.newInstance(mc.currentScreen, mc.gameSettings));
			return true;
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Could not open OptiFine's shader screen", t);
			return false;
		}
	}

	/** 1.8.9: is this OptiFine's shader screen? Matched by simple name -- its
	 *  package differs across OptiFine builds and it is no compile dependency. */
	public static boolean isShaderScreen(GuiScreen screen) {
		return screen != null && "GuiShaders".equals(screen.getClass().getSimpleName())
				&& !screen.getClass().getName().startsWith("com.origin.");
	}
}
