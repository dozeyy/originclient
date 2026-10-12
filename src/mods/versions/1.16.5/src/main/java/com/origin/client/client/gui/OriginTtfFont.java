package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Smooth Inter text for 1.16.5 -- this version's stand-in for OriginSdfFont's
 * sized API (capHeight / widthEm / drawEm), which OriginDeck (the 2026-10 menu
 * redesign) draws every label through.
 *
 * WHY THIS METHOD (1.16.5 is fixed-function GL, so no MSDF shader):
 *   * Minecraft's own `ttf` font provider (assets/originclient/font/inter*.json,
 *     drawn through Font with a Style naming that font) was the first candidate,
 *     but it rasterises every glyph ONCE at a fixed GUI size, and 1.16.5's text
 *     RenderType samples font pages with NEAREST filtering under the 0.1 alpha
 *     test. The redesign draws at continuous physical sizes (13 design px x a
 *     window-derived scale), so those glyphs would be resampled by a non-integer
 *     factor with nearest sampling: crunchy, uneven strokes, faint edges cut off.
 *   * This class uses the SAME rasteriser that provider uses -- stb_truetype,
 *     which 1.16.5 already ships through LWJGL for exactly that purpose -- but
 *     rasterises each glyph at the EXACT physical pixel size it is drawn at
 *     (plus a quarter-pixel horizontal phase), into our own atlas pages, and
 *     draws them 1:1 on whole physical pixels. Every glyph is anti-aliased,
 *     unresampled, and needs no shader: the closest fixed-function equivalent of
 *     the MSDF text 1.21.1 uses. Same Inter TTFs the ttf provider would load.
 *   * Fails soft (mandate 4): any failure leaves {@link #ready()} false and
 *     OriginDeck draws vanilla glyphs instead.
 *
 * Coordinates are the CALLER's units, which must be physical pixels (OriginDeck
 * draws under a 1/guiScale pose): that is what makes "exact size" exact.
 * Render thread only (every caller is a draw path).
 */
public final class OriginTtfFont {
	// Atlas pages are all one size, so an evicted size's pages are recycled
	// instead of freed (no texture churn, a hard memory cap: PAGE_CAP pages).
	private static final int PAGE = 256, PAGE_CAP = 40;
	// Horizontal sub-pixel phases per glyph: pen positions keep their fraction,
	// so spacing stays even, while every quad still lands on whole pixels.
	private static final int PHASES = 4;

	private static final class Face {
		final String file;
		STBTTFontinfo info;
		ByteBuffer data;          // stb keeps a pointer into this: must stay referenced
		float emScale;            // font units -> px at an em of 1 px
		float capEm;              // cap height as a fraction of the em
		int spaceAdv;             // font units
		final Map<Integer, int[]> glyphs = new HashMap<>(); // codepoint -> {glyph index, advance}
		boolean ok;

		Face(String file) {
			this.file = file;
		}
	}

	/** One rasterised glyph: where it sits in which page, and its bitmap offset. */
	private static final class Glyph {
		Page page;
		int u, v, w, h, xoff, yoff;
	}

	private static final class Page {
		final ResourceLocation id;
		final DynamicTexture tex;
		int cx = 1, cy = 1, rowH = 0;
		boolean dirty;

		Page(ResourceLocation id, DynamicTexture tex) {
			this.id = id;
			this.tex = tex;
		}
	}

	/** One face at one pixel size: its glyphs and the pages they live on. */
	private static final class Strike {
		final Face face;
		final float scale;
		final Map<Integer, Glyph> glyphs = new HashMap<>();   // glyphIndex * PHASES + phase
		final List<Page> pages = new ArrayList<>();

		Strike(Face face, float em) {
			this.face = face;
			this.scale = face.emScale * em;
		}
	}

	private static final Face REGULAR = new Face("inter_regular.ttf");
	private static final Face BOLD = new Face("inter_semibold.ttf");

	// access-ordered: the eldest entry is the least recently drawn size
	private static final LinkedHashMap<String, Strike> STRIKES = new LinkedHashMap<>(16, 0.75f, true);
	private static final List<Page> FREE = new ArrayList<>();
	private static int pagesMade = 0;
	private static boolean loaded = false;

	private static final IntBuffer I0 = BufferUtils.createIntBuffer(1), I1 = BufferUtils.createIntBuffer(1);
	private static final IntBuffer I2 = BufferUtils.createIntBuffer(1), I3 = BufferUtils.createIntBuffer(1);

	private OriginTtfFont() {
	}

	/** True when at least the regular face loaded. */
	public static boolean ready() {
		ensureLoaded();
		return REGULAR.ok;
	}

	private static Face face(boolean bold) {
		return bold && BOLD.ok ? BOLD : REGULAR;
	}

	/** Cap height at em size `em` (what optical centring uses). */
	public static float capHeight(float em, boolean bold) {
		ensureLoaded();
		return face(bold).capEm * em;
	}

	/** Advance width at em size `em`, with `tracking` added between glyphs. */
	public static float widthEm(String text, float em, float tracking, boolean bold) {
		ensureLoaded();
		Face f = face(bold);
		if (!f.ok || text.isEmpty()) {
			return 0;
		}
		float k = f.emScale * em, w = 0;
		for (int i = 0; i < text.length(); i++) {
			w += glyphOf(f, text.charAt(i))[1] * k;
		}
		return w + tracking * (text.length() - 1);
	}

	/** Draws `text` with its baseline at `baseline` (caller units = physical px). */
	public static void drawEm(Gfx g, String text, float x, float baseline, float em, float tracking,
							  int argb, boolean bold) {
		int a = (argb >>> 24) & 0xFF;
		if (!ready() || text.isEmpty() || a == 0 || em <= 0) {
			return;
		}
		Face f = face(bold);
		// Sizes are rasterised in quarter-pixel steps (a window resize must not
		// mint a new strike per frame); the quarter is invisible.
		float emQ = Math.max(1f, Math.round(em * 4f) / 4f);
		Strike st = strike(f, emQ, bold);
		float k = f.emScale * em;     // advances follow the requested size exactly
		int by = Math.round(baseline);

		// Pass 1: rasterise anything new and lay the quads out.
		int n = text.length();
		Glyph[] gl = new Glyph[n];
		int[] qx = new int[n];
		float pen = x;
		for (int i = 0; i < n; i++) {
			int[] gi = glyphOf(f, text.charAt(i));
			float floor = (float) Math.floor(pen);
			int phase = Math.min(PHASES - 1, (int) ((pen - floor) * PHASES));
			if (gi[0] != 0) {
				Glyph glyph = glyph(st, gi[0], phase);
				if (glyph != null) {
					gl[i] = glyph;
					qx[i] = (int) floor + glyph.xoff;
				}
			}
			pen += gi[1] * k + tracking;
		}

		// Pass 2: one textured quad batch per atlas page (normally just one).
		Matrix4f m = g.pose().last().pose();
		RenderSystem.enableTexture();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		// Gfx's soft alpha: keep every faint anti-aliased edge pixel (vanilla's
		// 0.1 cut-off would eat the outer ramp of each glyph).
		RenderSystem.alphaFunc(GL11.GL_GREATER, 0.0F);
		RenderSystem.color4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, a / 255f);
		for (Page p : st.pages) {
			boolean any = false;
			for (int i = 0; i < n && !any; i++) {
				any = gl[i] != null && gl[i].page == p;
			}
			if (!any) {
				continue;
			}
			if (p.dirty) {
				// upload() leaves NEAREST filtering, which is exactly right here:
				// every quad maps its texels 1:1 onto whole physical pixels.
				p.tex.upload();
				p.dirty = false;
			}
			Minecraft.getInstance().getTextureManager().bind(p.id);
			BufferBuilder bb = Tesselator.getInstance().getBuilder();
			// 1.16.5 BufferBuilder.begin takes an int GL mode (no VertexFormat.Mode enum).
			bb.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX);
			for (int i = 0; i < n; i++) {
				Glyph q = gl[i];
				if (q == null || q.page != p) {
					continue;
				}
				float x0 = qx[i], y0 = by + q.yoff, x1 = x0 + q.w, y1 = y0 + q.h;
				float u0 = q.u / (float) PAGE, v0 = q.v / (float) PAGE;
				float u1 = (q.u + q.w) / (float) PAGE, v1 = (q.v + q.h) / (float) PAGE;
				bb.vertex(m, x0, y0, 0f).uv(u0, v0).endVertex();
				bb.vertex(m, x0, y1, 0f).uv(u0, v1).endVertex();
				bb.vertex(m, x1, y1, 0f).uv(u1, v1).endVertex();
				bb.vertex(m, x1, y0, 0f).uv(u1, v0).endVertex();
			}
			// Tesselator.end() uploads and draws with the current fixed-function state.
			Tesselator.getInstance().end();
		}
		RenderSystem.defaultAlphaFunc();
		RenderSystem.color4f(1f, 1f, 1f, 1f);
	}

	// ---- glyph cache ----

	/** {glyph index (0 = missing), advance in font units} for a character. */
	private static int[] glyphOf(Face f, char c) {
		int[] gi = f.glyphs.get((int) c);
		if (gi == null) {
			int idx = STBTruetype.stbtt_FindGlyphIndex(f.info, c);
			int adv = f.spaceAdv;
			if (idx != 0) {
				STBTruetype.stbtt_GetGlyphHMetrics(f.info, idx, I0, I1);
				adv = I0.get(0);
			}
			gi = new int[]{idx, adv};
			f.glyphs.put((int) c, gi);
		}
		return gi;
	}

	private static Strike strike(Face f, float em, boolean bold) {
		String key = (f == BOLD ? "b" : "r") + em;
		Strike st = STRIKES.get(key);
		if (st == null) {
			st = new Strike(f, em);
			STRIKES.put(key, st);
		}
		return st;
	}

	/** The glyph at this strike + phase, rasterised on first use; null = blank. */
	private static Glyph glyph(Strike st, int index, int phase) {
		int key = index * PHASES + phase;
		if (st.glyphs.containsKey(key)) {
			return st.glyphs.get(key);
		}
		Glyph out = null;
		try {
			float shift = phase / (float) PHASES;
			STBTruetype.stbtt_GetGlyphBitmapBoxSubpixel(st.face.info, index, st.scale, st.scale, shift, 0f, I0, I1, I2, I3);
			int w = I2.get(0) - I0.get(0), h = I3.get(0) - I1.get(0);
			if (w > 0 && h > 0 && w < PAGE - 2 && h < PAGE - 2) {
				ByteBuffer bmp = BufferUtils.createByteBuffer(w * h);
				STBTruetype.stbtt_MakeGlyphBitmapSubpixel(st.face.info, bmp, w, h, w, st.scale, st.scale, shift, 0f, index);
				out = new Glyph();
				out.w = w;
				out.h = h;
				out.xoff = I0.get(0);
				out.yoff = I1.get(0);
				place(st, out);
				NativeImage img = out.page.tex.getPixels();
				for (int yy = 0; yy < h; yy++) {
					for (int xx = 0; xx < w; xx++) {
						int cov = bmp.get(yy * w + xx) & 0xFF;
						// ABGR: white, alpha = coverage (tinted by color4f at draw time)
						img.setPixelRGBA(out.u + xx, out.v + yy, (cov << 24) | 0xFFFFFF);
					}
				}
				out.page.dirty = true;
			}
		} catch (Throwable t) {
			out = null;
		}
		st.glyphs.put(key, out);
		return out;
	}

	/** Shelf-packs a glyph (1px gutter) into the strike's pages. */
	private static void place(Strike st, Glyph gl) {
		Page p = st.pages.isEmpty() ? null : st.pages.get(st.pages.size() - 1);
		if (p != null && p.cx + gl.w + 1 > PAGE) {
			p.cx = 1;
			p.cy += p.rowH + 1;
			p.rowH = 0;
		}
		if (p == null || p.cy + gl.h + 1 > PAGE) {
			p = newPage(st);
			st.pages.add(p);
		}
		gl.page = p;
		gl.u = p.cx;
		gl.v = p.cy;
		p.cx += gl.w + 1;
		p.rowH = Math.max(p.rowH, gl.h);
	}

	private static Page newPage(Strike owner) {
		if (FREE.isEmpty() && pagesMade >= PAGE_CAP) {
			// Out of pages: recycle the least recently drawn sizes (never the one
			// being drawn) until a page frees up.
			Iterator<Map.Entry<String, Strike>> it = STRIKES.entrySet().iterator();
			while (FREE.isEmpty() && it.hasNext()) {
				Strike old = it.next().getValue();
				if (old == owner) {
					continue;
				}
				FREE.addAll(old.pages);
				it.remove();
			}
		}
		if (!FREE.isEmpty()) {
			Page p = FREE.remove(FREE.size() - 1);
			NativeImage img = p.tex.getPixels();
			for (int yy = 0; yy < PAGE; yy++) {
				for (int xx = 0; xx < PAGE; xx++) {
					img.setPixelRGBA(xx, yy, 0);
				}
			}
			p.cx = 1;
			p.cy = 1;
			p.rowH = 0;
			p.dirty = true;
			return p;
		}
		ResourceLocation id = new ResourceLocation("originclient", "deck_text_" + (pagesMade++));
		// calloc'd (zeroed) so unused texels are transparent
		DynamicTexture tex = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, PAGE, PAGE, true));
		Minecraft.getInstance().getTextureManager().register(id, tex);
		return new Page(id, tex);
	}

	// ---- loading ----

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		loadFace(REGULAR);
		loadFace(BOLD);
	}

	private static void loadFace(Face f) {
		try {
			byte[] bytes;
			try (InputStream in = OriginTtfFont.class.getResourceAsStream("/assets/originclient/font/" + f.file)) {
				bytes = com.origin.client.client.IoCompat.readAllBytes(in);
			}
			ByteBuffer data = BufferUtils.createByteBuffer(bytes.length);
			data.put(bytes);
			data.flip();
			STBTTFontinfo info = STBTTFontinfo.malloc();   // lives for the session
			if (!STBTruetype.stbtt_InitFont(info, data)) {
				throw new IllegalStateException("not a TrueType font");
			}
			f.data = data;
			f.info = info;
			f.emScale = STBTruetype.stbtt_ScaleForMappingEmToPixels(info, 1f);
			int space = STBTruetype.stbtt_FindGlyphIndex(info, ' ');
			STBTruetype.stbtt_GetGlyphHMetrics(info, space, I0, I1);
			f.spaceAdv = I0.get(0);
			int h = STBTruetype.stbtt_FindGlyphIndex(info, 'H');
			f.capEm = 0.727f;    // Inter's cap height, if the box read fails
			if (h != 0) {
				I3.put(0, 0);
				STBTruetype.stbtt_GetGlyphBox(info, h, I0, I1, I2, I3);
				if (I3.get(0) > 0) {
					f.capEm = I3.get(0) * f.emScale;   // the H's top, in font units (y up)
				}
			}
			f.ok = true;
		} catch (Throwable t) {
			f.ok = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin Inter face '" + f.file + "' failed to load; using vanilla text", t);
		}
	}
}
