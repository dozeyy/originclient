package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * MSDF text renderer — the scalable replacement for the bitmap font in the
 * Origin menus. Glyph quads are drawn with {@link OriginShaders#MSDF}, which
 * reconstructs the true edge distance from the atlas and anti-aliases in SCREEN
 * space, so text stays razor-sharp at any GUI scale or pose zoom (vector-like),
 * not a scaled bitmap.
 *
 * This is NOT one of the three deleted hand-rolled renderers: those blitted a
 * plain alpha bitmap (which is what pixelated). This samples a real multi-channel
 * distance field through a purpose-built shader. Fails soft: if the atlas,
 * metrics, or shader don't load, {@link #ready()} is false and callers use the
 * vanilla-font path. Two weights: regular (Inter-400) and bold (Inter-600).
 *
 * Only used by the menus — in-world/HUD text stays vanilla Minecraft font.
 */
public final class OriginSdfFont {
	// Rendered em size in GUI px. metrics.size (42) is the atlas bake size; this
	// scales a glyph's advance/box down to roughly match vanilla ~9px text.
	// TUNE THIS live if the menu text reads a touch large/small next to layouts.
	public static final float EM_PX = 9.0f;
	// Vertical nudge so the SDF text sits where callers' box offsets expect. The
	// atlas' optical centre lands ~5.6px below the draw-top; layout offsets assume
	// ~4px (vanilla-like), which made every centred label render ~1.5px LOW. Pulling
	// the baseline up here re-centres ALL menu text at once (Will: "not vertically
	// centered"). TUNE THIS if labels read a hair high/low after a rebuild.
	public static final float MATCH_DY = -2.0f;

	private static final Gson GSON = new Gson();

	// Typographic characters the bundled Inter atlas does NOT carry, mapped to
	// glyphs it DOES — otherwise each renders as a blank space-width GAP (the "on
	// [wide gap] a" the em-dash caused in "On — a solid backdrop…"). One place, so
	// every label/tooltip/description is spaced correctly by construction.
	private static final Map<Character, Character> SUBS = new HashMap<>();
	static {
		SUBS.put('—', '-');   // — em dash  → hyphen
		SUBS.put('–', '-');   // – en dash  → hyphen
		SUBS.put('‘', '\'');  // ' left single quote  → apostrophe
		SUBS.put('’', '\'');  // ' right single quote → apostrophe
		SUBS.put('“', '"');   // " left double quote  → straight quote
		SUBS.put('”', '"');   // " right double quote → straight quote
		SUBS.put('＋', '+');   // ＋ fullwidth plus → plus
		SUBS.put('✕', '×'); // ✕ heavy multiply → × (which IS in the atlas)
		SUBS.put(' ', ' ');   // non-breaking space → space
	}

	/** Swap any atlas-missing typographic char for a present equivalent so it
	 *  never renders as a blank gap. Cheap: allocates only when a sub is needed. */
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
		final ResourceLocation atlas;
		final Map<Character, Glyph> glyphs = new HashMap<>();
		float size = 42, atlasW = 512, atlasH = 256, spaceAdv = 9;
		boolean ok = false;

		Face(String texName) {
			this.atlas = ResourceLocation.fromNamespaceAndPath("originclient", texName);
		}
	}

	private static final Face REGULAR = new Face("origin_msdf_atlas");
	private static final Face BOLD = new Face("origin_msdf_atlas_bold");

	private static volatile boolean loaded = false;

	private OriginSdfFont() {
	}

	/** True when at least the regular face + the shader are live. */
	public static boolean ready() {
		ensureLoaded();
		return REGULAR.ok && OriginShaders.MSDF != null;
	}

	/** Whether menus should route text through this renderer right now. */
	public static boolean active() {
		boolean en = OriginShaders.enabled();
		boolean rdy = ready();
		if (en && !rdy) {
			OriginShaders.noteTextFallback();
		}
		return en && rdy;
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
			// MSDF REQUIRES linear filtering — register our own DynamicTexture with
			// setFilter(true, false) rather than a nearest-sampled SimpleTexture.
			NativeImage img;
			try (InputStream in = OriginSdfFont.class.getResourceAsStream(
					"/assets/originclient/textures/font/" + base + ".png")) {
				img = NativeImage.read(in);
			}
			DynamicTexture tex = new DynamicTexture(img);
			tex.setFilter(true, false);
			Minecraft.getInstance().getTextureManager().register(f.atlas, tex);
			f.ok = true;
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
			int sc = (sa << 24) | ((argb >>> 2) & 0x3F3F3F); // ~25% brightness, same alpha
			emit(g, f, text, x + 0.6f, y + 0.6f, sc);
		}
		emit(g, f, text, x, y, argb);
	}

	private static void emit(GuiGraphics g, Face f, String text, float x, float y, int argb) {
		float s = EM_PX / f.size;
		int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b = argb & 0xFF;
		int a = (argb >>> 24) & 0xFF;
		if (a == 0) {
			a = 255;
		}
		Matrix4f m = g.pose().last().pose();
		BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		float pen = x;
		boolean any = false;
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
				bb.addVertex(m, gx0, gy0, 0).setColor(r, gg, b, a).setUv(u0, v0);
				bb.addVertex(m, gx0, gy1, 0).setColor(r, gg, b, a).setUv(u0, v1);
				bb.addVertex(m, gx1, gy1, 0).setColor(r, gg, b, a).setUv(u1, v1);
				bb.addVertex(m, gx1, gy0, 0).setColor(r, gg, b, a).setUv(u1, v0);
				any = true;
			}
			pen += gl.adv * s;
		}
		MeshData mesh = bb.build();
		if (mesh == null) {
			return;
		}
		if (!any) {
			mesh.close();
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(() -> OriginShaders.MSDF);
		RenderSystem.setShaderTexture(0, f.atlas);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		BufferUploader.drawWithShader(mesh);
		// CRITICAL: restore state so this immediate-mode draw can't leak into the
		// next GUI element or, once the menu closes, into world/sky rendering.
		OriginShaders.restoreState();
	}
}
