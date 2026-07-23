package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MSDF text renderer — the scalable replacement for the bitmap font in the
 * Origin menus. Glyph quads are drawn with {@link OriginShaders#MSDF}, which
 * reconstructs the true edge distance from the atlas and anti-aliases in SCREEN
 * space, so text stays razor-sharp at any GUI scale (vector-like), not a scaled
 * bitmap. Fails soft: if the atlas, metrics, or pipeline don't load, {@link #ready()}
 * is false and callers use the vanilla-font path. Two weights: regular
 * (Inter-400) and bold (Inter-600).
 *
 * PER-VERSION DELTA (1.21.11): 1.21.1/1.21.4 build a BufferBuilder, bind
 * {@code OriginShaders.MSDF} via {@code RenderSystem.setShader}, and issue one
 * immediate {@code BufferUploader.drawWithShader} call, then manually restore GL
 * state. 1.21.11's GuiGraphics has no immediate-draw path at all — it queues a
 * {@link GuiElementRenderState} into {@code g.guiRenderState}, and the deferred
 * renderer draws (and restores state) later. So {@link #emit} builds one
 * {@link MsdfTextRenderState} for the whole string and submits it — no manual
 * RenderSystem calls, no restoreState() needed.
 *
 * Only used by the menus — in-world/HUD text stays vanilla Minecraft font.
 */
public final class OriginSdfFont {
	// Rendered em size in GUI px. metrics.size (42) is the atlas bake size; this
	// scales a glyph's advance/box down to roughly match vanilla ~9px text.
	public static final float EM_PX = 9.0f;
	// Vertical nudge so the SDF text sits where callers' box offsets expect.
	public static final float MATCH_DY = -2.0f;

	private static final Gson GSON = new Gson();

	// Typographic characters the bundled Inter atlas does NOT carry, mapped to
	// glyphs it DOES — otherwise each renders as a blank space-width gap.
	private static final Map<Character, Character> SUBS = new HashMap<>();
	static {
		SUBS.put('—', '-');
		SUBS.put('–', '-');
		SUBS.put('‘', '\'');
		SUBS.put('’', '\'');
		SUBS.put('“', '"');
		SUBS.put('”', '"');
		SUBS.put('＋', '+');
		SUBS.put('✕', '×');
		SUBS.put(' ', ' ');
	}

	private static String normalize(String s) {
		boolean needs = false;
		for (int i = 0; i < s.length(); i++) {
			if (SUBS.containsKey(s.charAt(i))) {
				needs = true;
				break;
			}
		}
		if (!needs) {
			return s;
		}
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			Character r = SUBS.get(s.charAt(i));
			b.append(r == null ? s.charAt(i) : r);
		}
		return b.toString();
	}

	private record Glyph(float x, float y, float w, float h, float ox, float oy, float adv) {
	}

	// One weight: its atlas texture, glyph table, and bake metrics.
	private static final class Face {
		final Identifier atlas;
		final Map<Character, Glyph> glyphs = new HashMap<>();
		float size = 42, atlasW = 512, atlasH = 256, spaceAdv = 9;
		boolean ok = false;

		Face(String texName) {
			this.atlas = Identifier.fromNamespaceAndPath("originclient", texName);
		}
	}

	private static final Face REGULAR = new Face("origin_msdf_atlas");
	private static final Face BOLD = new Face("origin_msdf_atlas_bold");

	private static volatile boolean loaded = false;

	private OriginSdfFont() {
	}

	/** True when at least the regular face + the pipeline are live. */
	public static boolean ready() {
		ensureLoaded();
		return REGULAR.ok && OriginShaders.MSDF != null;
	}

	/** Whether menus should route text through this renderer right now. */
	public static boolean active() {
		return OriginShaders.enabled() && ready();
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		loadFace(REGULAR, "inter_msdf");
		loadFace(BOLD, "inter_msdf_bold");
	}

	private static void loadFace(Face f, String base) {
		try {
			JsonObject j;
			try (InputStream in = OriginSdfFont.class.getResourceAsStream(
					"/assets/originclient/textures/font/" + base + ".json")) {
				j = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			}
			f.size = j.get("size").getAsFloat();
			f.atlasW = j.get("atlasW").getAsFloat();
			f.atlasH = j.get("atlasH").getAsFloat();
			JsonObject g = j.getAsJsonObject("glyphs");
			for (String k : g.keySet()) {
				if (k.isEmpty()) {
					continue;
				}
				JsonObject o = g.getAsJsonObject(k);
				f.glyphs.put(k.charAt(0), new Glyph(o.get("x").getAsFloat(), o.get("y").getAsFloat(),
						o.get("w").getAsFloat(), o.get("h").getAsFloat(),
						o.get("ox").getAsFloat(), o.get("oy").getAsFloat(), o.get("adv").getAsFloat()));
			}
			if (f.glyphs.containsKey(' ')) {
				f.spaceAdv = f.glyphs.get(' ').adv;
			}
			// MSDF requires linear filtering. On 1.21.11 filtering is a sampler
			// concern, not a texture-object concern (DynamicTexture.setFilter was
			// removed) — the texture registers plain, and every draw explicitly
			// requests a linear-filtered sampler via TextureSetup (see emit()).
			NativeImage img;
			try (InputStream in = OriginSdfFont.class.getResourceAsStream(
					"/assets/originclient/textures/font/" + base + ".png")) {
				img = NativeImage.read(in);
			}
			DynamicTexture tex = new DynamicTexture(() -> base, img);
			Minecraft.getInstance().getTextureManager().register(f.atlas, tex);
			f.ok = true;
			// DIAGNOSTIC (2026-07-23): invisible-text bug — confirm the atlas
			// actually loaded with real glyph data before blaming the shader.
			com.origin.client.OriginClient.LOGGER.info(
					"Origin MSDF DEBUG: face '{}' loaded ok, {} glyphs, atlas {}x{}, size={}, atlasId={}",
					base, f.glyphs.size(), f.atlasW, f.atlasH, f.size, f.atlas);
		} catch (Throwable t) {
			f.ok = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin MSDF face '" + base + "' failed to load; using vanilla text", t);
		}
	}

	private static Face face(boolean bold) {
		return bold && BOLD.ok ? BOLD : REGULAR;
	}

	/** Advance width of `text` in GUI px, matching what {@link #draw} lays out. */
	public static int width(String text, boolean bold) {
		ensureLoaded();
		text = normalize(text);
		Face f = face(bold);
		float s = EM_PX / f.size, w = 0;
		for (int i = 0; i < text.length(); i++) {
			Glyph gl = f.glyphs.get(text.charAt(i));
			w += (gl == null ? f.spaceAdv : gl.adv) * s;
		}
		return Math.round(w);
	}

	/** Draw `text` with its top-left at (x,y). `shadow` mimics vanilla's 1px drop. */
	public static void draw(GuiGraphics g, String text, float x, float y, int argb, boolean shadow, boolean bold) {
		if (!ready() || text.isEmpty()) {
			return;
		}
		text = normalize(text);
		Face f = face(bold);
		if (shadow) {
			int sa = (argb >>> 24) & 0xFF;
			int sc = (sa << 24) | ((argb >>> 2) & 0x3F3F3F);
			emit(g, f, text, x + 0.6f, y + 0.6f, sc);
		}
		emit(g, f, text, x, y, argb);
	}

	private record GlyphVertex(float x, float y, float u, float v, int color) {
	}

	// One GuiElementRenderState for the whole string — the deferred renderer
	// batches all our quads under OriginShaders.MSDF into one draw once it's
	// ready to render, same as vanilla's own GlyphRenderState per Font.drawInBatch.
	private record MsdfTextRenderState(
			Matrix3x2fc pose, List<GlyphVertex> quads, GpuTextureView atlasView,
			ScreenRectangle scissor, ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer vertexConsumer) {
			// DIAGNOSTIC (2026-07-23): this runs LATER, inside GuiRenderer's own
			// mesh-building pass — outside any try/catch in OriginSdfFont.emit().
			// If this throws, Minecraft's per-frame render guard could swallow it
			// silently (no draw call ever gets recorded, no GL error, no crash) —
			// exactly the symptom under investigation. Logging here converts a
			// silent failure into a visible one.
			try {
				for (int i = 0; i < quads.size(); i += 4) {
					for (int v = 0; v < 4; v++) {
						GlyphVertex gv = quads.get(i + v);
						vertexConsumer.addVertexWith2DPose(pose, gv.x(), gv.y()).setColor(gv.color()).setUv(gv.u(), gv.v());
					}
				}
				if (!loggedFirstBuildVertices) {
					loggedFirstBuildVertices = true;
					com.origin.client.OriginClient.LOGGER.info(
							"Origin MSDF DEBUG: buildVertices() completed OK, wrote {} quads", quads.size() / 4);
				}
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.error("Origin MSDF DEBUG: buildVertices() THREW", t);
				throw t;
			}
		}

		@Override
		public com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
			return OriginShaders.MSDF;
		}

		@Override
		public TextureSetup textureSetup() {
			try {
				return TextureSetup.singleTexture(atlasView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.error("Origin MSDF DEBUG: textureSetup() THREW", t);
				throw t;
			}
		}

		@Override
		public ScreenRectangle scissorArea() {
			return scissor;
		}

		@Override
		public ScreenRectangle bounds() {
			// ROOT CAUSE (2026-07-23, task 14): this used to return null. Vanilla's
			// GlyphRenderState can get away with that because vanilla text goes
			// through a SEPARATE dedicated submission path (forEachText /
			// submitGlyphToCurrentLayer), not the generic submitGuiElement path.
			// Our custom element uses the SAME generic path as ColoredRectangleRenderState
			// and BlitRenderState — and both of THOSE always compute a real,
			// non-null bounds rectangle. The renderer evidently uses bounds() for
			// visibility culling on that path: returning null was read as "zero
			// visible area", so the element got silently dropped before
			// buildVertices() was ever called — no exception, no GL error, just
			// nothing drawn. Ever.
			return bounds;
		}
	}

	private static void emit(GuiGraphics g, Face f, String text, float x, float y, int argb) {
		float s = EM_PX / f.size;
		int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b = argb & 0xFF;
		int a = (argb >>> 24) & 0xFF;
		if (a == 0) {
			a = 255;
		}
		int color = (a << 24) | (r << 16) | (gg << 8) | b;

		List<GlyphVertex> quads = new ArrayList<>();
		float pen = x;
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < text.length(); i++) {
			Glyph gl = f.glyphs.get(text.charAt(i));
			if (gl == null) {
				pen += f.spaceAdv * s;
				continue;
			}
			if (gl.w > 0 && gl.h > 0) {
				float gx0 = pen + gl.ox * s;
				float gy0 = y + gl.oy * s + MATCH_DY;
				float gx1 = gx0 + gl.w * s;
				float gy1 = gy0 + gl.h * s;
				float u0 = gl.x / f.atlasW, v0 = gl.y / f.atlasH;
				float u1 = (gl.x + gl.w) / f.atlasW, v1 = (gl.y + gl.h) / f.atlasH;
				quads.add(new GlyphVertex(gx0, gy0, u0, v0, color));
				quads.add(new GlyphVertex(gx0, gy1, u0, v1, color));
				quads.add(new GlyphVertex(gx1, gy1, u1, v1, color));
				quads.add(new GlyphVertex(gx1, gy0, u1, v0, color));
				minX = Math.min(minX, gx0);
				minY = Math.min(minY, gy0);
				maxX = Math.max(maxX, gx1);
				maxY = Math.max(maxY, gy1);
			}
			pen += gl.adv * s;
		}
		if (quads.isEmpty()) {
			// DIAGNOSTIC (2026-07-23): invisible-text bug — if EVERY char in the
			// string failed glyph lookup, this is a Java-side data bug, not a
			// shader bug. Log once so a silent early-return isn't mistaken for
			// "nothing to draw".
			if (!warnedEmptyQuads) {
				warnedEmptyQuads = true;
				com.origin.client.OriginClient.LOGGER.warn(
						"Origin MSDF DEBUG: emit('{}') produced ZERO quads — every char missed glyph lookup", text);
			}
			return;
		}
		GpuTextureView atlasView = Minecraft.getInstance().getTextureManager().getTexture(f.atlas).getTextureView();
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		// Real bounds, matching ColoredRectangleRenderState/BlitRenderState's own
		// pattern: local rect -> transformMaxBounds(pose) -> intersect scissor.
		// See bounds() below for why this fixed the invisible-text bug.
		ScreenRectangle localRect = new ScreenRectangle(
				(int) Math.floor(minX), (int) Math.floor(minY),
				(int) Math.ceil(maxX - Math.floor(minX)), (int) Math.ceil(maxY - Math.floor(minY)));
		ScreenRectangle transformed = localRect.transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(transformed) : transformed;
		if (!loggedFirstEmit) {
			loggedFirstEmit = true;
			GlyphVertex v0 = quads.get(0);
			com.origin.client.OriginClient.LOGGER.info(
					"Origin MSDF DEBUG: first emit('{}') -> {} quads, pipeline={}, atlasView={}, v0=({},{} uv {},{}), bounds={}",
					text, quads.size() / 4, OriginShaders.MSDF, atlasView, v0.x(), v0.y(), v0.u(), v0.v(), bounds);
		}
		g.guiRenderState.submitGuiElement(new MsdfTextRenderState(pose, quads, atlasView, scissor, bounds));
	}

	private static volatile boolean warnedEmptyQuads = false;
	private static volatile boolean loggedFirstEmit = false;
	private static volatile boolean loggedFirstBuildVertices = false;
}
