package com.origin.client.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.ASMEventHandler;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.IEventListener;
import net.minecraftforge.fml.common.eventhandler.ListenerList;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Bench-only per-hook profiler (Phase 6 U6.3 measurement, 2026-10-25). OFF unless the bench runs with
 * ORIGIN_BENCH_PROF=1 on a non-probe variant: {@link #ON} is a static final false for every player, so the
 * {@code if (BenchProf.ON)} guards at the call sites are folded away by the JIT.
 *
 * What it records, only inside OriginBench's measured window, render thread only:
 *  - every Origin @SubscribeEvent handler (all go through OriginHooks.registerObject): calls, wall ns and
 *    render-thread allocated bytes. The handlers are wrapped once at the title screen by re-registering each
 *    ASMEventHandler behind a timing IEventListener on the same bus id and priority (no hook code changes);
 *  - named sections in the per-entity / per-screen mixins and layers that are not event handlers
 *    (Menu Blur backdrop, nametag label, cape physics, cosmetic layer, entity-distance cull);
 *  - plain counters for paths too hot to time per call (particle render/spawn, FontRenderer.drawString,
 *    Origin Tessellator draws in Gfx/OriginUi). Their cost comes from {@link #microbench} instead.
 * The timer's own cost is calibrated at the start of every window and reported, so it can be subtracted.
 */
public final class BenchProf {
	public static final boolean ON = OriginBench.profiling();

	private static final int MAX = 160;
	private static final String[] NAMES = new String[MAX];
	private static final boolean[] SECTION = new boolean[MAX];
	private static int slots = 0;
	private static final long[] NS = new long[MAX], BYTES = new long[MAX], CALLS = new long[MAX], MAXNS = new long[MAX];
	private static final long[] T0 = new long[MAX], A0 = new long[MAX];
	private static final int[] DEPTH = new int[MAX];

	/** Counters (per measured window; only counted while it is open). */
	private static long particleRender, particleSpawn, fontDrawString, gfxDraw, gfxText;

	public static void countParticleRender() {
		if (active) {
			particleRender++;
		}
	}

	public static void countParticleSpawn() {
		if (active) {
			particleSpawn++;
		}
	}

	public static void countFontDrawString() {
		if (active) {
			fontDrawString++;
		}
	}

	public static void countGfxDraw() {
		if (active) {
			gfxDraw++;
		}
	}

	public static void countGfxText() {
		if (active) {
			gfxText++;
		}
	}

	public static final int S_MENU_BLUR = section("mixin GuiScreenBackground.drawWorldBackground (Menu Blur)");
	public static final int S_NAMETAG = section("mixin Render.renderLivingLabel (Nametags/LevelHead/Party/Plus)");
	public static final int S_CAPE = section("mixin LayerCape.doRenderLayer HEAD (cape physics)");
	public static final int S_COSMETIC_LAYER = section("layer CosmeticLayer.doRenderLayer (hats/masks/backs)");
	public static final int S_ENTITY_CULL = section("mixin RenderManager.shouldRender (Entity Distance)");
	// HUD phases inside HudElements.renderAll (summed over every element) and the minimap's scan/draw split
	// (Phase 6 picks 3/4): they say where an element's time goes, which the per-element section cannot.
	public static final int S_HUD_MEASURE = section("hud phase: measure (all elements)");
	public static final int S_HUD_BACKING = section("hud phase: drawBacking (all elements)");
	public static final int S_HUD_RENDER = section("hud phase: renderer (all elements)");
	public static final int S_MM_UPDATE = section("minimap: MinimapData.update (terrain scan)");
	public static final int S_MM_SCAN = section("minimap: one chunk scan");
	public static final int S_MM_DRAWMAP = section("minimap: drawMap (geometry)");
	public static final int S_MM_INFO = section("minimap: info lines + box");

	private static volatile boolean active = false;
	private static com.sun.management.ThreadMXBean threads;
	private static long tid = -1;
	private static double overheadNs = 0, overheadBytes = 0, pairCostNs = 0;
	private static int wrapped = 0;
	private static final List<String> notes = new ArrayList<>();
	/** Keeps microbench results alive so the JIT cannot drop the loop. */
	public static volatile double sink;

	private BenchProf() {
	}

	private static synchronized int slot(String name, boolean section) {
		for (int i = 0; i < slots; i++) {
			if (NAMES[i].equals(name)) {
				return i;
			}
		}
		if (slots >= MAX) {
			return MAX - 1;
		}
		NAMES[slots] = name;
		SECTION[slots] = section;
		return slots++;
	}

	private static int section(String name) {
		return slot(name, true);
	}

	private static int[] hudSlots = new int[0];

	/** Section slot for HUD element {@code index} (HudElements.renderAll), made on first use. */
	public static int hudElement(int index, String modId) {
		if (index >= hudSlots.length) {
			int[] n = Arrays.copyOf(hudSlots, index + 16);
			Arrays.fill(n, hudSlots.length, n.length, -1);
			hudSlots = n;
		}
		int s = hudSlots[index];
		if (s < 0) {
			s = section("hud element " + modId);
			hudSlots[index] = s;
		}
		return s;
	}

	// ---- call-site API (guarded by ON at the call site) ----

	public static void begin(int s) {
		if (!active || Thread.currentThread().getId() != tid) {
			return;
		}
		if (DEPTH[s]++ == 0) {
			A0[s] = alloc();
			T0[s] = System.nanoTime();
		}
	}

	public static void end(int s) {
		if (!active || DEPTH[s] == 0 || Thread.currentThread().getId() != tid) {
			return;
		}
		if (--DEPTH[s] == 0) {
			long t = System.nanoTime();
			long a = alloc();
			long d = t - T0[s];
			NS[s] += d;
			BYTES[s] += a - A0[s];
			CALLS[s]++;
			if (d > MAXNS[s]) {
				MAXNS[s] = d;
			}
		}
	}

	private static long alloc() {
		try {
			return threads == null ? 0 : threads.getThreadAllocatedBytes(tid);
		} catch (Throwable t) {
			return 0;
		}
	}

	// ---- event handler wrapping ----

	/** A timing shim in front of one ASMEventHandler. */
	private static final class Timed implements IEventListener {
		private final IEventListener inner;
		private final int slot;

		Timed(IEventListener inner, int slot) {
			this.inner = inner;
			this.slot = slot;
		}

		@Override
		public void invoke(Event event) {
			begin(slot);
			try {
				inner.invoke(event);
			} finally {
				end(slot);
			}
		}

		@Override
		public String toString() {
			return "BenchProf(" + inner + ")";
		}
	}

	/** Wraps every Origin handler on both buses. Called once, on the render thread, at the title screen. */
	@SuppressWarnings("unchecked")
	public static void wrapEventHandlers(List<Object> handlers, Object skip) {
		tid = Thread.currentThread().getId();
		try {
			java.lang.management.ThreadMXBean tm = ManagementFactory.getThreadMXBean();
			if (tm instanceof com.sun.management.ThreadMXBean) {
				threads = (com.sun.management.ThreadMXBean) tm;
				if (!threads.isThreadAllocatedMemoryEnabled()) {
					threads.setThreadAllocatedMemoryEnabled(true);
				}
			}
		} catch (Throwable t) {
			notes.add("no allocation counter: " + t);
		}
		List<EventBus> buses = new ArrayList<>();
		buses.add(MinecraftForge.EVENT_BUS);
		try {
			EventBus fml = FMLCommonHandler.instance().bus();
			if (fml != null && fml != MinecraftForge.EVENT_BUS) {
				buses.add(fml);
			}
		} catch (Throwable ignored) {
			// one bus only
		}
		try {
			Field lf = EventBus.class.getDeclaredField("listeners");
			lf.setAccessible(true);
			Field idf = EventBus.class.getDeclaredField("busID");
			idf.setAccessible(true);
			for (EventBus bus : buses) {
				Map<Object, ArrayList<IEventListener>> map = (Map<Object, ArrayList<IEventListener>>) lf.get(bus);
				int busId = idf.getInt(bus);
				for (Object h : handlers) {
					if (h == null || h == skip) {
						continue;
					}
					ArrayList<IEventListener> list = map.get(h);
					if (list == null) {
						continue;
					}
					for (int i = 0; i < list.size(); i++) {
						IEventListener l = list.get(i);
						if (!(l instanceof ASMEventHandler)) {
							continue;
						}
						Method m = methodFor(h.getClass(), l.toString());
						if (m == null) {
							notes.add("unmatched listener " + l);
							continue;
						}
						Class<?> type = m.getParameterTypes()[0];
						java.lang.reflect.Constructor<?> ctr = type.getConstructor();
						ctr.setAccessible(true);
						ListenerList ll = ((Event) ctr.newInstance()).getListenerList();
						String tn = type.getName();
						String name = h.getClass().getSimpleName() + "." + m.getName() + "(" + tn.substring(tn.lastIndexOf('.') + 1) + ")";
						Timed t = new Timed(l, slot(name, false));
						ll.unregister(busId, l);
						ll.register(busId, ((ASMEventHandler) l).getPriority(), t);
						list.set(i, t);
						wrapped++;
					}
				}
			}
		} catch (Throwable t) {
			notes.add("event wrapping failed part-way (" + wrapped + " wrapped): " + t);
			OriginClientLog.warn("[origin-bench] BenchProf wrapping failed", t);
		}
	}

	/** The @SubscribeEvent method an ASMEventHandler's readable ("ASM: target name(desc)V") names. */
	private static Method methodFor(Class<?> cls, String readable) {
		for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getParameterTypes().length != 1 || !m.isAnnotationPresent(SubscribeEvent.class)) {
					continue;
				}
				String desc = " " + m.getName() + "(L" + m.getParameterTypes()[0].getName().replace('.', '/') + ";)V";
				if (readable.endsWith(desc)) {
					return m;
				}
			}
		}
		return null;
	}

	// ---- measured window ----

	public static void start() {
		Arrays.fill(NS, 0);
		Arrays.fill(BYTES, 0);
		Arrays.fill(CALLS, 0);
		Arrays.fill(MAXNS, 0);
		Arrays.fill(DEPTH, 0);
		particleRender = particleSpawn = fontDrawString = gfxDraw = gfxText = 0;
		calibrate();
		active = true;
	}

	public static void stop() {
		active = false;
	}

	/** What one begin/end pair costs on its own: the in-interval part (subtracted) and the whole pair. */
	private static void calibrate() {
		int n = 4000;
		long[] dn = new long[n], da = new long[n];
		long w0 = System.nanoTime();
		for (int i = 0; i < n; i++) {
			long a0 = alloc();
			long t0 = System.nanoTime();
			long t1 = System.nanoTime();
			long a1 = alloc();
			dn[i] = t1 - t0;
			da[i] = a1 - a0;
		}
		pairCostNs = (System.nanoTime() - w0) / (double) n;
		Arrays.sort(dn);
		Arrays.sort(da);
		overheadNs = dn[n / 2];
		overheadBytes = da[n / 2];
	}

	public static JsonObject report(int frames) {
		JsonObject o = new JsonObject();
		double f = Math.max(1, frames);
		o.addProperty("frames", frames);
		o.addProperty("wrappedHandlers", wrapped);
		o.addProperty("timerOverheadNsPerCall", overheadNs);
		o.addProperty("timerOverheadBytesPerCall", overheadBytes);
		o.addProperty("timerPairCostNs", round(pairCostNs));
		JsonArray hooks = new JsonArray();
		double handlerNs = 0, sectionNs = 0, handlerBytes = 0, sectionBytes = 0, calls = 0;
		for (int i = 0; i < slots; i++) {
			if (CALLS[i] == 0) {
				continue;
			}
			double ns = Math.max(0, NS[i] - CALLS[i] * overheadNs) / f;
			double by = Math.max(0, BYTES[i] - CALLS[i] * overheadBytes) / f;
			JsonObject h = new JsonObject();
			h.addProperty("name", NAMES[i]);
			h.addProperty("kind", SECTION[i] ? "section" : "handler");
			h.addProperty("callsPerFrame", round(CALLS[i] / f));
			h.addProperty("nsPerFrame", round(ns));
			h.addProperty("rawNsPerFrame", round(NS[i] / f));
			h.addProperty("bytesPerFrame", round(by));
			h.addProperty("maxCallUs", round(MAXNS[i] / 1000.0));
			hooks.add(h);
			calls += CALLS[i];
			if (SECTION[i]) {
				sectionNs += ns;
				sectionBytes += by;
			} else {
				handlerNs += ns;
				handlerBytes += by;
			}
		}
		o.add("hooks", hooks);
		o.addProperty("handlerNsPerFrame", round(handlerNs));
		o.addProperty("sectionNsPerFrame", round(sectionNs));
		o.addProperty("handlerBytesPerFrame", round(handlerBytes));
		o.addProperty("sectionBytesPerFrame", round(sectionBytes));
		o.addProperty("profilerCostNsPerFrame", round(calls * pairCostNs / f));
		JsonObject c = new JsonObject();
		c.addProperty("particleRenderPerFrame", round(particleRender / f));
		c.addProperty("particleSpawnPerFrame", round(particleSpawn / f));
		c.addProperty("fontDrawStringPerFrame", round(fontDrawString / f));
		c.addProperty("originTessDrawsPerFrame", round(gfxDraw / f));
		c.addProperty("originGfxTextPerFrame", round(gfxText / f));
		o.add("counters", c);
		if (!notes.isEmpty()) {
			JsonArray n = new JsonArray();
			for (String s : notes) {
				n.add(new com.google.gson.JsonPrimitive(s));
			}
			o.add("notes", n);
		}
		return o;
	}

	/**
	 * Per-call cost of the config reads the particle mixins make for EVERY particle (render) and every
	 * spawn, timed in a tight loop right after the window (same JIT state, nothing else running). The
	 * counters say how many calls a frame makes; ns/call x calls/frame is the per-frame cost.
	 */
	public static JsonObject microbench() {
		JsonObject o = new JsonObject();
		int n = 2_000_000;
		int crit = net.minecraft.util.EnumParticleTypes.CRIT.getParticleID();
		double acc = 0;
		for (int round = 0; round < 2; round++) {   // round 0 warms the loop up
			long t = System.nanoTime();
			for (int i = 0; i < n; i++) {
				acc += com.origin.client.client.mods.ParticleFilter.globalScale();
			}
			double scaleNs = (System.nanoTime() - t) / (double) n;
			t = System.nanoTime();
			for (int i = 0; i < n; i++) {
				acc += com.origin.client.client.mods.ParticleFilter.hidden(crit, i, 64, 0) ? 1 : 0;
			}
			double hiddenNs = (System.nanoTime() - t) / (double) n;
			t = System.nanoTime();
			for (int i = 0; i < n; i++) {
				acc += com.origin.client.client.mods.Mods.on("nametags") ? 1 : 0;
			}
			double onNs = (System.nanoTime() - t) / (double) n;
			if (round == 1) {
				o.addProperty("globalScaleNsPerCall", round(scaleNs));
				o.addProperty("hiddenNsPerCall", round(hiddenNs));
				o.addProperty("modsOnNsPerCall", round(onNs));
			}
		}
		sink = acc;
		return o;
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	/** Logger indirection so this class has no hard dependency on the mod's init order. */
	private static final class OriginClientLog {
		static void warn(String msg, Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn(msg, t);
		}
	}
}
