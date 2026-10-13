package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Glint Colorizer (Lunar parity): recolours the enchantment glint, items and
 * worn armor separately, or hides it.
 *
 * How vanilla draws the glint on 1.8.9: a second pass over the model with the
 * scrolling textures/misc/enchanted_item_glint.png, blended additively
 * (blendFunc(SRC_COLOR, ONE)), depth func EQUAL. Items: RenderItem.renderEffect
 * draws the model twice with the vertex colour 0x8040CC; armor:
 * LayerArmorBase.renderGlint draws the armor model twice under
 * glColor(0.76 x (0.5, 0.25, 0.8)). Both multiply the glint texture.
 *
 * 1.8.9 METHOD (best fit for this era): GlintColorizerMixin cancels those two
 * passes at HEAD and replays them -- vanilla's exact texture-matrix scroll and
 * depth state -- with a recoloured copy of the glint texture (built here) and a
 * WHITE vertex colour / glColor, so the texture alone carries the colour:
 *   - Vanilla Blending: texel = brightness x colour, added as light exactly like
 *     vanilla's purple. Lunar's default colour (128,64,204) IS vanilla 1.8.9's
 *     0x8040CC, so the default looks like vanilla.
 *   - Lunar Blending: texel = the colour with alpha = brightness x the colour's
 *     opacity, alpha-blended OVER the item -- additive light can only brighten,
 *     so dark colours (black, navy) would vanish otherwise.
 * Each pixel's brightness is its strongest channel in the current resource
 * pack's glint texture. Rebuilt only when a colour or the blending choice changes
 * (sync(), once a frame).
 *
 * With OptiFine shaders on, OptiFine routes the glint through its own shader
 * program; the replay is skipped there (vanilla/OptiFine glint, or none when
 * Show Glint is off), because a fixed-function replay under a shader pack would
 * not match the pack.
 */
public final class GlintColorizer {
	private static final String MOD = "glintcolorizer";
	/** vanilla 1.8.9 armor glint's glColor scale (LayerArmorBase.renderGlint's 0.76). */
	public static final float ARMOR_DIM = 0.76f;

	public static final ResourceLocation VANILLA_GLINT = new ResourceLocation("textures/misc/enchanted_item_glint.png");
	private static final ResourceLocation ITEM_TEX = new ResourceLocation("originclient", "glint/item");
	private static final ResourceLocation ARMOR_TEX = new ResourceLocation("originclient", "glint/armor");

	private static DynamicTexture item, armor;
	private static String builtKey = null;
	private static boolean broken = false;

	private GlintColorizer() {
	}

	public static boolean lunar() {
		return Mods.bool(MOD, "lunarBlending");
	}

	private static int itemColor() {
		return OriginColorPicker.liveColor(MOD, lunar() ? "itemColorLunar" : "itemColorVanilla");
	}

	private static int armorColor() {
		return OriginColorPicker.liveColor(MOD, lunar() ? "armorColorLunar" : "armorColorVanilla");
	}

	/** Once a frame: (re)builds the recoloured textures when the look changed. */
	public static void sync() {
		if (broken || !Mods.on(MOD)) {
			return;
		}
		String key = lunar() + ":" + itemColor() + ":" + armorColor();
		if (key.equals(builtKey)) {
			return;
		}
		try {
			float[][] glint = intensity(VANILLA_GLINT);
			item = paint(item, ITEM_TEX, glint, itemColor());
			armor = paint(armor, ARMOR_TEX, glint, armorColor());
			builtKey = key;
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: Glint Colorizer could not build its textures; glints stay vanilla", t);
		}
	}

	/** Show Glint off: the glint pass is skipped entirely (items and armor). */
	public static boolean hidden() {
		return !broken && Mods.on(MOD) && !Mods.bool(MOD, "showGlint");
	}

	/** Whether the item (or armor) glint pass is replayed with Origin's texture. */
	public static boolean overriding(boolean armorPass) {
		if (broken || !Mods.on(MOD) || builtKey == null || WorldHooks.optifineShadersActive()) {
			return false;
		}
		return armorPass ? Mods.bool(MOD, "overrideArmor") : Mods.bool(MOD, "overrideItem");
	}

	/** Binds the recoloured glint for the pass (texture unit 0, where vanilla binds its own). */
	public static void bind(boolean armorPass) {
		Minecraft.getMinecraft().getTextureManager().bindTexture(armorPass ? ARMOR_TEX : ITEM_TEX);
	}

	/** The pass's blend: vanilla's additive SRC_COLOR/ONE, or a normal alpha blend for Lunar Blending. */
	public static void blend() {
		if (lunar()) {
			GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
		} else {
			GlStateManager.blendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
		}
	}

	// Decoded brightness per glint texture, re-read only when a different resource
	// pack supplies it -- so dragging a colour slider never re-decodes the PNG.
	private static final Map<ResourceLocation, Object[]> INTENSITY_CACHE = new HashMap<>();

	/** Per-pixel glint brightness 0..1 (strongest channel) of the active pack's texture. */
	private static float[][] intensity(ResourceLocation loc) throws Exception {
		// 1.8.9: getResource throws when missing; IResource is not Closeable here.
		IResource res = Minecraft.getMinecraft().getResourceManager().getResource(loc);
		String pack = res.getResourcePackName();   // 1.8.9 NAME UNVERIFIED
		Object[] cached = INTENSITY_CACHE.get(loc);
		if (cached != null && pack != null && pack.equals(cached[0])) {
			try {
				res.getInputStream().close();
			} catch (Throwable ignored) {
				// nothing to release
			}
			return (float[][]) cached[1];
		}
		float[][] decoded;
		try (InputStream in = res.getInputStream()) {
			BufferedImage img = TextureUtil.readBufferedImage(in);
			decoded = new float[img.getWidth()][img.getHeight()];
			for (int x = 0; x < img.getWidth(); x++) {
				for (int y = 0; y < img.getHeight(); y++) {
					int argb = img.getRGB(x, y);
					int max = Math.max(argb & 0xFF, Math.max((argb >> 8) & 0xFF, (argb >> 16) & 0xFF));
					decoded[x][y] = max / 255f;
				}
			}
		}
		INTENSITY_CACHE.put(loc, new Object[]{pack, decoded});
		return decoded;
	}

	/**
	 * Lunar Blending: the colour itself, with alpha = brightness x the colour's
	 * opacity (alpha-blended over the item). Vanilla Blending: brightness x the
	 * colour, fully opaque (added as light, exactly like vanilla's purple).
	 */
	private static DynamicTexture paint(DynamicTexture tex, ResourceLocation id, float[][] inten, int argb) {
		int w = inten.length, h = inten[0].length;
		if (tex == null || tex.getTextureData().length != w * h) {
			tex = new DynamicTexture(w, h);
			Minecraft.getMinecraft().getTextureManager().loadTexture(id, tex);
		}
		int[] px = tex.getTextureData();
		boolean lunar = lunar();
		int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				float i = inten[x][y];
				int pr, pg, pb, pa;
				if (lunar) {
					pr = r;
					pg = g;
					pb = b;
					pa = Math.round(i * a);
				} else {
					pr = Math.min(255, Math.round(i * r));
					pg = Math.min(255, Math.round(i * g));
					pb = Math.min(255, Math.round(i * b));
					pa = 255;
				}
				// DynamicTexture data is ARGB, row-major
				px[y * w + x] = (pa << 24) | (pr << 16) | (pg << 8) | pb;
			}
		}
		tex.updateDynamicTexture();
		// updateDynamicTexture uploads NEAREST + REPEAT; the glint is drawn LINEAR like
		// vanilla's (the vanilla glint texture asks for blur) and must wrap (it scrolls).
		GlStateManager.bindTexture(tex.getGlTextureId());
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		return tex;
	}
}
