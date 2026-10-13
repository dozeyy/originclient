package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.settings.GameSettings;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

// FPS Coach's tip catalogue: which settings are likely costing frames right now,
// ranked by how much they usually cost, each with the exact change "Try it"
// makes and the exact way back.
//
// Every change goes through the same door the player would use, so it persists
// the same way: the GameSettings field is set together with the reaction
// vanilla's own Video Settings control runs for it (chunk rebuild, VSync switch
// -- GameSettings.setOptionValue / setOptionFloatValue's side effects), then
// GameSettings.saveOptions(), exactly as closing Video Settings does.
//
// 1.8.9 (OptiFine is on every player's game): the catalogue follows what this
// era's Video Settings actually has. Gone: Fabulous graphics, biome blend and
// simulation distance (1.13+ / 1.15+ / 1.18+), Iris (1.8.9's shaders are
// OptiFine's). Added: Graphics Fancy -> Fast (leaves, cloud and item detail --
// a real cost on the hardware 1.8.9 players use) and Use VBOs off -> on (1.8.9's
// own "faster chunk drawing" switch). Clouds read OptiFine's own Clouds option
// when OptiFine is there (it replaces vanilla's on its Details page). Particles
// is the particleSetting int (0 All, 1 Decreased, 2 Minimal).
//
// Shaders go through OptiFine's own static entry points (Config.isShaders,
// Shaders.currentshadername / setShaderPack / uninit / storeConfig -- what its
// shader screen calls), by reflection with no compile dependency, so a missing
// or changed OptiFine just means the shader tip never appears.
public final class FpsCoachTips {
	private FpsCoachTips() {
	}

	/** Applies a change; returns how to undo it exactly, or null if nothing changed. */
	public interface Change {
		Runnable apply(Minecraft mc);
	}

	/**
	 * One tip. `change` is the "Try it" label ("Render distance 16 → 10"); `apply`
	 * is null for advice-only tips (memory). `settleMs` is how long the game gets
	 * to settle after the change before FPS is measured again (chunk rebuilds,
	 * shader reloads).
	 */
	// Java 8 has no records: a final value class with the same canonical
	// constructor and accessors the record had.
	public static final class Tip {
		private final String id;
		private final int weight;
		private final String title;
		private final String detail;
		private final String change;
		private final Change apply;
		private final int settleMs;

		public Tip(String id, int weight, String title, String detail, String change, Change apply, int settleMs) {
			this.id = id;
			this.weight = weight;
			this.title = title;
			this.detail = detail;
			this.change = change;
			this.apply = apply;
			this.settleMs = settleMs;
		}

		public String id() {
			return this.id;
		}

		public int weight() {
			return this.weight;
		}

		public String title() {
			return this.title;
		}

		public String detail() {
			return this.detail;
		}

		public String change() {
			return this.change;
		}

		public Change apply() {
			return this.apply;
		}

		public int settleMs() {
			return this.settleMs;
		}
	}

	/** Every tip id, for "Reset Hidden Tips" (1.8.9's set; ids shared with other versions keep their meaning). */
	public static final String[] IDS = {"shaders", "fpslimit", "vsync", "renderdistance", "graphics", "vbo",
			"memory", "entities", "debug", "clouds", "particles", "shadows"};

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
		final GameSettings o = mc.gameSettings;
		boolean shaders = OptiFine.shadersInUse();

		if (shaders) {
			String pack = OptiFine.packName();
			out.add(new Tip("shaders", 100,
					pack != null ? "Shaders are on (" + pack + ")" : "Shaders are on",
					"Shaders cost more FPS than any other setting.",
					"Shaders on → off", m -> OptiFine.shadersOff(), 4000));
		}

		// 1.8.9: limitFramerate 260 is "Unlimited"; the limiter reads it every frame.
		int limit = o.limitFramerate;
		if (limit < 260 && limit < target) {
			int to = Math.min(260, (int) Math.ceil(target / 10.0) * 10);
			out.add(new Tip("fpslimit", 98, "Max framerate is set to " + limit,
					"That's below your target of " + target + ", so you can never reach it.",
					"Max framerate " + limit + " → " + (to >= 260 ? "Unlimited" : String.valueOf(to)),
					option(() -> o.limitFramerate, v -> o.limitFramerate = v, to), 1000));
		}

		int refresh = refreshRate();
		if (o.enableVsync && refresh > 0 && refresh < target) {
			out.add(new Tip("vsync", 96, "VSync holds you at " + refresh + " FPS",
					"It matches your screen's " + refresh + " Hz, which is below your target of " + target + ".",
					"VSync on → off", option(() -> o.enableVsync, v -> {
						o.enableVsync = v;
						Display.setVSyncEnabled(v);   // what GameSettings.setOptionValue(ENABLE_VSYNC) runs
					}, false), 1000));
		}

		int rd = o.renderDistanceChunks;
		if (rd > 12) {
			int to = Math.max(10, (int) Math.round(rd * 0.6));
			out.add(new Tip("renderdistance", 60 + 2 * (rd - 12), "Render distance " + rd + " is high for your PC",
					"Fewer chunks to draw. Far-away land just fades in a bit sooner.",
					"Render distance " + rd + " → " + to, option(() -> o.renderDistanceChunks, v -> {
						o.renderDistanceChunks = v;
						mc.renderGlobal.setDisplayListEntitiesDirty();
					}, to), 3000));
		}

		if (o.fancyGraphics) {
			out.add(new Tip("graphics", 35, "Graphics is set to Fancy",
					"Fast draws leaves solid and skips some detail, and runs noticeably faster.",
					"Graphics Fancy → Fast", option(() -> o.fancyGraphics, v -> {
						o.fancyGraphics = v;
						mc.renderGlobal.loadRenderers();
					}, false), 3000));
		}

		if (OpenGlHelper.vboSupported && !o.useVbo) {
			out.add(new Tip("vbo", 30, "Use VBOs is off",
					"Vertex buffers let your graphics card draw chunks faster.",
					"Use VBOs off → on", option(() -> o.useVbo, v -> {
						o.useVbo = v;
						mc.renderGlobal.loadRenderers();
					}, true), 3000));
		}

		if (maxMb > 0 && usedMb > maxMb * 0.85) {
			out.add(new Tip("memory", 50, "Minecraft is almost out of memory",
					"Using " + usedMb + " of " + maxMb + " MB. Raise RAM allocation in the Origin launcher's Settings.",
					null, null, 0));
		}

		if (mc.theWorld != null && Mods.num(Mods.PERFORMANCE_ID, "entityDistance") > 50) {
			int n = mc.theWorld.loadedEntityList.size();
			if (n > 250) {
				double from = Mods.num(Mods.PERFORMANCE_ID, "entityDistance");
				out.add(new Tip("entities", 45, n + " mobs and items are loaded",
						"Origin can skip drawing the far-away ones.",
						"Entity Distance " + Math.round(from) + "% → 50%", m -> entityDistance(50), 1000));
			}
		}

		if (o.showDebugInfo) {
			out.add(new Tip("debug", 40, "The F3 screen is open",
					"Its text and charts cost FPS. F3 closes it any time.",
					"F3 screen open → closed", m -> toggleDebug(m), 1000));
		}

		if (rd >= 4 && cloudsFancy(o)) {
			out.add(new Tip("clouds", 20, "Clouds are set to Fancy",
					"Fast clouds are flat and much cheaper to draw.",
					"Clouds Fancy → Fast", m -> cloudsFast(m), 1000));
		}

		if (o.particleSetting == 0) {
			out.add(new Tip("particles", 15, "Particles are set to All",
					"Decreased drops some of the smoke, splashes and potion swirls.",
					"Particles All → Decreased", option(() -> o.particleSetting, v -> o.particleSetting = v, 1), 1000));
		}

		// Shader packs draw their own shadows, so vanilla's are moot with one on.
		if (!shaders && o.entityShadows) {
			out.add(new Tip("shadows", 10, "Entity shadows are on",
					"The small round shadows under mobs and players.",
					"Entity shadows on → off", option(() -> o.entityShadows, v -> o.entityShadows = v, false), 1000));
		}

		out.removeIf(t -> hidden(t.id()));
		Collections.sort(out, Comparator.comparingInt(Tip::weight).reversed());
		return out;
	}

	// ---- changes ----

	/** A vanilla option change: the field + vanilla's reaction (set), then saveOptions. */
	private static <T> Change option(Supplier<T> get, Consumer<T> set, T to) {
		return mc -> {
			T from = get.get();
			if (Objects.equals(from, to)) {
				return null;
			}
			set.accept(to);
			mc.gameSettings.saveOptions();
			return () -> {
				set.accept(from);
				mc.gameSettings.saveOptions();
			};
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

	// 1.8.9: the F3 screen is the plain GameSettings.showDebugInfo flag.
	private static Runnable toggleDebug(Minecraft mc) {
		if (!mc.gameSettings.showDebugInfo) {
			return null;
		}
		mc.gameSettings.showDebugInfo = false;
		return () -> mc.gameSettings.showDebugInfo = true;
	}

	// LWJGL2: the desktop mode's refresh rate (a windowed Display reports 0).
	private static int refreshRate() {
		try {
			return Display.getDesktopDisplayMode().getFrequency();
		} catch (Throwable t) {
			return 0;
		}
	}

	// ---- clouds: OptiFine's Clouds option when present, else vanilla's ----
	//
	// Vanilla 1.8.9: GameSettings.clouds 0 off / 1 fast / 2 fancy. OptiFine adds
	// ofClouds (0 Default / 1 Fast / 2 Fancy / 3 Off) and shows only that one, so
	// with OptiFine the tip appears only for an explicit Fancy there and moves it
	// to Fast; "Default" (vanilla's own value decides) is left to vanilla's field.

	private static boolean cloudsFancy(GameSettings o) {
		Field of = OptiFine.field(GameSettings.class, "ofClouds");
		if (of != null) {
			try {
				int v = of.getInt(o);
				if (v != 0) {
					return v == 2;
				}
			} catch (Throwable t) {
				return false;
			}
		}
		return o.clouds == 2;
	}

	private static Runnable cloudsFast(Minecraft mc) {
		final GameSettings o = mc.gameSettings;
		final Field of = OptiFine.field(GameSettings.class, "ofClouds");
		try {
			if (of != null && of.getInt(o) != 0) {
				final int from = of.getInt(o);
				if (from != 2) {
					return null;
				}
				of.setInt(o, 1);
				OptiFine.updateClouds(o);
				o.saveOptions();
				return () -> {
					try {
						of.setInt(o, from);
						OptiFine.updateClouds(o);
						o.saveOptions();
					} catch (Throwable t) {
						// the change simply stays
					}
				};
			}
		} catch (Throwable t) {
			return null;
		}
		return option(() -> o.clouds, v -> o.clouds = v, 1).apply(mc);
	}

	// ---- OptiFine (reflection-only, fail-soft; no compile dependency) ----

	static final class OptiFine {
		private OptiFine() {
		}

		// OptiFine HD U for 1.8.9 keeps its shader classes in shadersmod.client;
		// later OptiFine moved them to net.optifine.shaders. Config is in the
		// default package in both.
		private static Class<?> shadersClass() {
			for (String n : new String[]{"shadersmod.client.Shaders", "net.optifine.shaders.Shaders"}) {
				try {
					return Class.forName(n);
				} catch (Throwable t) {
					// try the next name
				}
			}
			return null;
		}

		static Field field(Class<?> owner, String name) {
			try {
				Field f = owner.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (Throwable t) {
				return null;
			}
		}

		/** True when a shader pack is actually rendering (OptiFine's Config.isShaders()). */
		static boolean shadersInUse() {
			try {
				Method m = Class.forName("Config").getMethod("isShaders");
				return (Boolean) m.invoke(null);
			} catch (Throwable t) {
				return false;
			}
		}

		/** The selected pack's name (Shaders.currentshadername), shortened, or null. */
		static String packName() {
			try {
				Class<?> sh = shadersClass();
				Field f = sh == null ? null : field(sh, "currentshadername");
				Object v = f == null ? null : f.get(null);
				if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
					return null;
				}
				String name = ((String) v).trim();
				if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
					name = name.substring(0, name.length() - 4);
				}
				return name.length() > 28 ? name.substring(0, 27) + "..." : name;
			} catch (Throwable t) {
				return null;
			}
		}

		/** Switches shaders off the way OptiFine's shader screen does (pack "OFF",
		 *  reload, save); returns how to switch the old pack back on, or null. */
		static Runnable shadersOff() {
			final String from = rawPackName();
			if (from == null || !select("OFF")) {
				return null;
			}
			return () -> select(from);
		}

		private static String rawPackName() {
			try {
				Class<?> sh = shadersClass();
				Field f = sh == null ? null : field(sh, "currentshadername");
				Object v = f == null ? null : f.get(null);
				return v instanceof String ? (String) v : null;
			} catch (Throwable t) {
				return null;
			}
		}

		// Shaders.setShaderPack(name) + Shaders.uninit() (the pack reloads on the
		// next frame) + Shaders.storeConfig() (optionsshaders.txt), as OptiFine's
		// own GuiShaders does when a pack is picked and the screen closed.
		private static boolean select(String pack) {
			try {
				Class<?> sh = shadersClass();
				if (sh == null) {
					return false;
				}
				sh.getMethod("setShaderPack", String.class).invoke(null, pack);
				sh.getMethod("uninit").invoke(null);
				try {
					sh.getMethod("storeConfig").invoke(null);
				} catch (Throwable t) {
					// applied for this session; just not saved
				}
				return true;
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.warn("FPS Coach: couldn't switch OptiFine shaders", t);
				return false;
			}
		}

		// OptiFine re-reads ofClouds through its own updateRenderClouds() when the
		// option is changed from its screen; call it when it exists.
		static void updateClouds(GameSettings o) {
			try {
				Method m = GameSettings.class.getDeclaredMethod("updateRenderClouds");
				m.setAccessible(true);
				m.invoke(o);
			} catch (Throwable t) {
				// not present on this OptiFine: the field alone is read per frame
			}
		}
	}
}
