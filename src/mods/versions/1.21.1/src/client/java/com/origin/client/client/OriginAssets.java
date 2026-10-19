package com.origin.client.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Origin's own textures and metadata, decoded off the render thread.
 *
 * <p>Every Origin texture comes from the mod jar (classpath), not the resource
 * manager, so it can be decoded as soon as the game starts. Before this, each
 * loader (icon atlases, UI panels, MSDF fonts, the Origin+ badge, the loading
 * screen's own art) PNG-decoded and parsed on the render thread the first time
 * something drew it, which put 1152x3072 icon atlases and friends right on the
 * loading -> main menu hand-off. Now the startup reload's PREPARE stage decodes
 * all of it on the reload's worker threads ({@link #register}); a loader then
 * only takes the finished image ({@link #image}) and uploads it to the GPU. The
 * uploads themselves run one loader per frame while the loading screen is up
 * ({@link #uploadSlice}), after the decode is done.
 *
 * <p>Fail-soft and order-proof: a loader that asks before its image is decoded
 * decodes it itself (exactly the old behaviour), and the worker then skips it.
 * Images are handed over once (the taker owns and closes them); anything never
 * taken is closed when the slot is dropped.
 */
public final class OriginAssets {
	private static final Gson GSON = new Gson();
	private static final String UI = "/assets/originclient/textures/ui/";
	private static final String FONT = "/assets/originclient/textures/font/";

	/** Everything the loaders read. Order: what the hand-off needs first. */
	private static final String[] IMAGES = {
			UI + "mod_icons_sdf.png", UI + "mod_icons.png",
			UI + "button_fill.png", UI + "button_border.png", UI + "switch_track.png", UI + "switch_knob.png",
			UI + "radial_glow.png", UI + "ring-0.png", UI + "origin_logo.png", UI + "star.png",
			FONT + "inter_msdf.png", FONT + "inter_msdf_bold.png",
	};
	private static final String[] JSONS = {
			UI + "mod_icons.json", UI + "buttons.json", FONT + "inter_msdf.json", FONT + "inter_msdf_bold.json",
	};

	private static final Map<String, Slot<NativeImage>> images = new ConcurrentHashMap<>();
	private static final Map<String, Slot<JsonObject>> jsons = new ConcurrentHashMap<>();
	private static final Map<String, Slot<Object>> derived = new ConcurrentHashMap<>();
	private static volatile boolean prepared = false;
	private static boolean started = false;

	private OriginAssets() {
	}

	// ---- startup ----

	/**
	 * Registers the prefetch as a client reload listener: its prepare stage (the
	 * reload's background executor) decodes everything; it has no apply work at
	 * all, so it adds nothing to the render thread. Only the first reload decodes:
	 * these are jar files, a resource-pack change can't alter them.
	 */
	public static void register() {
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {
			private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", "asset_prefetch");

			@Override
			public ResourceLocation getFabricId() {
				return id;
			}

			@Override
			public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
												  ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler,
												  Executor background, Executor game) {
				return CompletableFuture.runAsync(OriginAssets::prefetch, background)
						.exceptionally(t -> null)
						.thenCompose(v -> barrier.wait(null));
			}
		});
	}

	private static void prefetch() {
		synchronized (OriginAssets.class) {
			if (started) {
				return;
			}
			started = true;
		}
		try {
			for (String p : JSONS) {
				slot(jsons, p, () -> readJson(p)).run();
			}
			for (String p : IMAGES) {
				slot(images, p, () -> readImage(p)).run();
			}
			// CPU work derived from those images: the Origin+ badge's three box-filtered sizes.
			slot(derived, PLUS_BADGE, com.origin.client.client.cosmetics.PlusBadge::buildLevels).run();
		} finally {
			prepared = true;
		}
	}

	/** Key of PlusBadge's prebuilt sizes ({@link #derived}). */
	public static final String PLUS_BADGE = "plus_badge_levels";

	// ---- loaders (render thread) ----

	/** The decoded image at this classpath path: prefetched, or decoded now if it isn't yet. Caller owns it. */
	public static NativeImage image(String path) throws Exception {
		NativeImage img = claim(images, path);
		return img != null ? img : readImage(path);
	}

	/** A prefetched image the loader decided not to use: free it now. */
	public static void discard(String path) {
		NativeImage img = claim(images, path);
		if (img != null) {
			img.close();
		}
	}

	// Hands a result over exactly once. The key keeps a spent marker, so a
	// prefetch that only reaches it later (a loader that asked early) skips it
	// instead of decoding a second copy nobody would ever take or close.
	@SuppressWarnings("unchecked")
	private static <T> T claim(Map<String, Slot<T>> map, String key) {
		Slot<T> s = map.put(key, (Slot<T>) SPENT);
		return s == null ? null : s.take();
	}

	private static final Slot<Object> SPENT = Slot.spent();

	/** The parsed JSON at this classpath path (shared, read-only). */
	public static JsonObject json(String path) throws Exception {
		Slot<JsonObject> s = jsons.get(path);
		JsonObject j = s == null ? null : s.peek();
		return j != null ? j : readJson(path);
	}

	/** A derived result built by the prefetch (e.g. {@link #PLUS_BADGE}), or null if it wasn't (build it yourself). */
	@SuppressWarnings("unchecked")
	public static <T> T derived(String key) {
		return (T) claim(derived, key);
	}

	/** True once the prefetch has run (everything above is a hand-over, not a decode). */
	public static boolean prepared() {
		return prepared;
	}

	// ---- per-frame upload slices ----

	private static int uploadStep = 0;

	/**
	 * Called once per loading-screen frame. After the prefetch, uploads one
	 * loader's textures per frame, so no single frame pays for all of them and
	 * the title's first frame pays for none. Each step is the loader's own
	 * idempotent ensure; a step that isn't ready yet (the icon shader arrives
	 * late in the load) is retried on later frames.
	 */
	public static void uploadSlice() {
		if (!prepared || uploadStep >= UPLOADS.size()) {
			return;
		}
		try {
			if (UPLOADS.get(uploadStep).getAsBoolean()) {
				uploadStep++;
			}
		} catch (Throwable t) {
			uploadStep++;   // an upload is only an early start: the loader still loads on demand
		}
	}

	private static final List<java.util.function.BooleanSupplier> UPLOADS = List.of(
			() -> {
				com.origin.client.client.gui.OriginUi.ready();
				return true;
			},
			// The icon atlas waits for the icon shader: ModIcons picks its atlas once,
			// from whether that shader exists, and the shader only arrives late in the
			// load. Asked earlier, every icon would stay on the blurrier mask atlas.
			() -> com.origin.client.client.gui.OriginShaders.ICON != null && com.origin.client.client.gui.ModIcons.preload(),
			() -> {
				com.origin.client.client.cosmetics.PlusBadge.preload();
				return true;
			},
			() -> {
				com.origin.client.client.gui.OriginSdfFont.preload();
				return true;
			});

	// ---- decoding ----

	static NativeImage readImage(String path) throws Exception {
		try (InputStream in = open(path)) {
			return NativeImage.read(in);
		}
	}

	static JsonObject readJson(String path) throws Exception {
		try (InputStream in = open(path)) {
			return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
		}
	}

	private static InputStream open(String path) throws Exception {
		InputStream in = OriginAssets.class.getResourceAsStream(path);
		if (in == null) {
			throw new java.io.FileNotFoundException("Missing Origin asset: " + path);
		}
		return in;
	}

	private interface Builder<T> {
		T build() throws Exception;
	}

	@SuppressWarnings("unchecked")
	private static <T> Slot<T> slot(Map<String, Slot<T>> map, String key, Builder<? extends T> builder) {
		return map.computeIfAbsent(key, k -> new Slot<>((Builder<T>) builder));
	}

	/**
	 * One result, built once by whoever gets there first: the prefetch worker, or a
	 * loader on the render thread that can't wait (it then builds it itself and the
	 * worker skips it). A loader that finds it mid-build waits for that build.
	 */
	private static final class Slot<T> implements Runnable {
		private final Builder<T> builder;
		private final AtomicInteger state = new AtomicInteger();   // 0 idle, 1 building, 2 done
		private final CountDownLatch done = new CountDownLatch(1);
		private volatile T value;

		Slot(Builder<T> builder) {
			this.builder = builder;
		}

		/** Already handed over: never builds, always yields null. */
		static <T> Slot<T> spent() {
			Slot<T> s = new Slot<>(null);
			s.state.set(2);
			s.done.countDown();
			return s;
		}

		/** Worker: build it unless a loader already claimed it. */
		@Override
		public void run() {
			if (state.compareAndSet(0, 1)) {
				build();
			}
		}

		private void build() {
			try {
				value = builder.build();
			} catch (Throwable t) {
				value = null;   // the loader decodes it itself and reports its own failure
			} finally {
				state.set(2);
				done.countDown();
			}
		}

		/** Loader: the result (null = build it yourself). Builds it here if nobody started yet. */
		T take() {
			if (state.compareAndSet(0, 1)) {
				build();
			} else {
				try {
					done.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
			return value;
		}

		/** Like take, for shared results that stay in the map. */
		T peek() {
			return take();
		}
	}
}
