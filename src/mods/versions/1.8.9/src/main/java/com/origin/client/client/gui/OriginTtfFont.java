package com.origin.client.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Smooth Inter text for 1.8.9 -- the stand-in for OriginSdfFont's sized API
 * (capHeight / widthEm / drawEm) that OriginDeck (the 2026-10 menu redesign)
 * draws every label through. Same public API as 1.16.5's OriginTtfFont.
 *
 * WHY THIS METHOD (1.8.9: fixed-function GL, LWJGL 2 -- no shader, no stb):
 *   * 1.16.5 rasterises Inter with stb_truetype at the EXACT physical pixel size
 *     each label is drawn at and blits the glyphs 1:1. LWJGL 2 ships no stb, so
 *     the same job is done with java.awt: Font.createFont on the bundled Inter
 *     TTFs, each glyph's OUTLINE (GlyphVector.getGlyphOutline -- unhinted, like
 *     stb) filled with geometric anti-aliasing into a BufferedImage, its coverage
 *     copied into our own atlas pages (DynamicTexture), drawn 1:1 on whole
 *     physical pixels with a quarter-pixel horizontal phase. Anti-aliased,
 *     unresampled, no shader -- the same look as 1.16.5.
 *   * 1.8.9's FontRenderer cannot do this at all (one fixed bitmap size).
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
	// Metrics are read once at this size and scaled (fractional metrics: linear).
	private static final float REF = 256f;

	private static final FontRenderContext FRC = new FontRenderContext(null,
			RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

	private static final class Face {
		final String file;
		Font base;                // the TTF at size 1 (derived per strike)
		float capEm;              // cap height as a fraction of the em
		float spaceAdvEm;         // advance of ' ' per em
		final Map<Integer, float[]> glyphs = new HashMap<>(); // codepoint -> {glyph present (1/0), advance per em}
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
		final int[] data;         // the texture's own ARGB array (getTextureData)
		int cx = 1, cy = 1, rowH = 0;
		boolean dirty;

		Page(ResourceLocation id, DynamicTexture tex) {
			this.id = id;
			this.tex = tex;
			this.data = tex.getTextureData();
		}
	}

	/** One face at one pixel size: its glyphs and the pages they live on. */
	private static final class Strike {
		final Face face;
		final Font font;          // the face derived to this pixel size
		final Map<Integer, Glyph> glyphs = new HashMap<>();   // codepoint * PHASES + phase
		final List<Page> pages = new ArrayList<>();

		Strike(Face face, float em) {
			this.face = face;
			this.font = face.base.deriveFont(em);
		}
	}

	private static final Face REGULAR = new Face("inter_regular.ttf");
	private static final Face BOLD = new Face("inter_semibold.ttf");

	// access-ordered: the eldest entry is the least recently drawn size
	private static final LinkedHashMap<String, Strike> STRIKES = new LinkedHashMap<>(16, 0.75f, true);
	private static final List<Page> FREE = new ArrayList<>();
	private static int pagesMade = 0;
	private static boolean loaded = false;

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
		float w = 0;
		for (int i = 0; i < text.length(); i++) {
			w += glyphOf(f, text.charAt(i))[1] * em;
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
		Strike st = strike(f, emQ);
		int by = Math.round(baseline);

		// Pass 1: rasterise anything new and lay the quads out.
		int n = text.length();
		Glyph[] gl = new Glyph[n];
		int[] qx = new int[n];
		float pen = x;
		for (int i = 0; i < n; i++) {
			char c = text.charAt(i);
			float[] gi = glyphOf(f, c);
			float floor = (float) Math.floor(pen);
			int phase = Math.min(PHASES - 1, (int) ((pen - floor) * PHASES));
			if (gi[0] != 0 && c != ' ') {
				Glyph glyph = glyph(st, c, phase);
				if (glyph != null) {
					gl[i] = glyph;
					qx[i] = (int) floor + glyph.xoff;
				}
			}
			pen += gi[1] * em + tracking;   // advances follow the requested size exactly
		}

		// Pass 2: one textured quad batch per atlas page (normally just one).
		GlStateManager.enableTexture2D();
		Gfx.defaultBlend();
		// Gfx's soft alpha: keep every faint anti-aliased edge pixel (vanilla's
		// 0.1 cut-off would eat the outer ramp of each glyph).
		Gfx.softAlpha();
		Gfx.color(argb);
		Tessellator tess = Tessellator.getInstance();
		for (Page p : st.pages) {
			boolean any = false;
			for (int i = 0; i < n && !any; i++) {
				any = gl[i] != null && gl[i].page == p;
			}
			if (!any) {
				continue;
			}
			if (p.dirty) {
				// updateDynamicTexture() uploads with NEAREST filtering, which is exactly
				// right here: every quad maps its texels 1:1 onto whole physical pixels.
				p.tex.updateDynamicTexture();
				p.dirty = false;
			}
			Gfx.bind(p.id);
			WorldRenderer wr = tess.getWorldRenderer();
			wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
			for (int i = 0; i < n; i++) {
				Glyph q = gl[i];
				if (q == null || q.page != p) {
					continue;
				}
				float x0 = qx[i], y0 = by + q.yoff, x1 = x0 + q.w, y1 = y0 + q.h;
				float u0 = q.u / (float) PAGE, v0 = q.v / (float) PAGE;
				float u1 = (q.u + q.w) / (float) PAGE, v1 = (q.v + q.h) / (float) PAGE;
				wr.pos(x0, y0, 0).tex(u0, v0).endVertex();
				wr.pos(x0, y1, 0).tex(u0, v1).endVertex();
				wr.pos(x1, y1, 0).tex(u1, v1).endVertex();
				wr.pos(x1, y0, 0).tex(u1, v0).endVertex();
			}
			tess.draw();
		}
		Gfx.defaultAlpha();
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	// ---- glyph cache ----

	/** {1 if the face has the glyph else 0, advance per em} for a character. */
	private static float[] glyphOf(Face f, char c) {
		float[] gi = f.glyphs.get((int) c);
		if (gi == null) {
			float adv = f.spaceAdvEm;
			float has = 0;
			try {
				if (f.base.canDisplay(c)) {
					Font ref = f.base.deriveFont(REF);
					GlyphVector gv = ref.createGlyphVector(FRC, new char[]{c});
					adv = gv.getGlyphMetrics(0).getAdvance() / REF;
					has = 1;
				}
			} catch (Throwable t) {
				has = 0;
			}
			gi = new float[]{has, adv};
			f.glyphs.put((int) c, gi);
		}
		return gi;
	}

	private static Strike strike(Face f, float em) {
		String key = (f == BOLD ? "b" : "r") + em;
		Strike st = STRIKES.get(key);
		if (st == null) {
			st = new Strike(f, em);
			STRIKES.put(key, st);
		}
		return st;
	}

	/** The glyph at this strike + phase, rasterised on first use; null = blank. */
	private static Glyph glyph(Strike st, char c, int phase) {
		int key = c * PHASES + phase;
		if (st.glyphs.containsKey(key)) {
			return st.glyphs.get(key);
		}
		Glyph out = null;
		try {
			float shift = phase / (float) PHASES;
			GlyphVector gv = st.font.createGlyphVector(FRC, new char[]{c});
			// The outline with the baseline at y=0 and the pen at x=shift (y grows down).
			Shape outline = gv.getGlyphOutline(0, shift, 0f);
			Rectangle2D b = outline.getBounds2D();
			// whole-pixel box around the outline, 1px of slack for the AA ramp
			int x0 = (int) Math.floor(b.getMinX()) - 1, y0 = (int) Math.floor(b.getMinY()) - 1;
			int x1 = (int) Math.ceil(b.getMaxX()) + 1, y1 = (int) Math.ceil(b.getMaxY()) + 1;
			int w = x1 - x0, h = y1 - y0;
			if (!b.isEmpty() && w > 0 && h > 0 && w < PAGE - 2 && h < PAGE - 2) {
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g2 = img.createGraphics();
				try {
					// Geometric (unhinted) coverage of the outline: stb_truetype's look.
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
					g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
					g2.setColor(java.awt.Color.WHITE);
					g2.translate(-x0, -y0);
					g2.fill(outline);
				} finally {
					g2.dispose();
				}
				out = new Glyph();
				out.w = w;
				out.h = h;
				out.xoff = x0;
				out.yoff = y0;
				place(st, out);
				int[] px = img.getRGB(0, 0, w, h, null, 0, w);
				int[] dst = out.page.data;
				for (int yy = 0; yy < h; yy++) {
					for (int xx = 0; xx < w; xx++) {
						int cov = (px[yy * w + xx] >>> 24) & 0xFF;
						// ARGB: white, alpha = coverage (tinted by the GL colour at draw time)
						dst[(out.v + yy) * PAGE + out.u + xx] = (cov << 24) | 0xFFFFFF;
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
			Arrays.fill(p.data, 0);
			p.cx = 1;
			p.cy = 1;
			p.rowH = 0;
			p.dirty = true;
			return p;
		}
		ResourceLocation id = new ResourceLocation("originclient", "deck_text_" + (pagesMade++));
		// DynamicTexture(w, h) starts all-zero ARGB: unused texels are transparent.
		DynamicTexture tex = new DynamicTexture(PAGE, PAGE);
		Minecraft.getMinecraft().getTextureManager().loadTexture(id, tex);
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
			Font base;
			try (InputStream in = OriginTtfFont.class.getResourceAsStream("/assets/originclient/font/" + f.file)) {
				if (in == null) {
					throw new IllegalStateException("missing font file");
				}
				base = Font.createFont(Font.TRUETYPE_FONT, in);
			}
			f.base = base;
			Font ref = base.deriveFont(REF);
			f.spaceAdvEm = ref.createGlyphVector(FRC, new char[]{' '}).getGlyphMetrics(0).getAdvance() / REF;
			f.capEm = 0.727f;    // Inter's cap height, if the outline read fails
			Rectangle2D hb = ref.createGlyphVector(FRC, new char[]{'H'}).getGlyphOutline(0).getBounds2D();
			if (!hb.isEmpty() && hb.getMinY() < 0) {
				f.capEm = (float) (-hb.getMinY() / REF);   // the H's top above the baseline
			}
			f.ok = true;
		} catch (Throwable t) {
			f.ok = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin Inter face '" + f.file + "' failed to load; using vanilla text", t);
		}
	}
}
