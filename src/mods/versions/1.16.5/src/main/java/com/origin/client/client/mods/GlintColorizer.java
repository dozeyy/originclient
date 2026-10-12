package com.origin.client.client.mods;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Glint Colorizer (Lunar parity): recolours the enchantment glint, items and
 * worn armor separately, or hides it.
 *
 * How vanilla draws the glint on 1.16.5 (decompiled 1.16.5 RenderType +
 * RenderStateShard): a second pass over the model with one scrolling purple
 * texture (textures/misc/enchanted_item_glint.png -- the separate item/entity
 * glint textures are 1.20.5+), blended additively (SRC_COLOR, ONE), all in
 * FIXED-FUNCTION GL. Every glint RenderType's texturing shard calls
 * RenderStateShard.setupGlintTexturing (the GL texture-matrix scroll), which runs
 * AFTER the texture and transparency shards (CompositeState order).
 * GlintColorizerMixin hooks its tail and calls onGlintSetup, which can then:
 *   - bind a recoloured copy of the glint texture (built here) on unit 0, and
 *   - for Lunar Blending, swap the blend to a normal alpha blend -- additive light
 *     can only brighten, so dark colours (black, navy) would vanish otherwise;
 *     with alpha blending the colour is laid OVER the item, scaled by the glint's
 *     brightness, so any colour shows. The glint pass's own clear state restores
 *     the default blend afterwards, so nothing leaks into later draws.
 * The recoloured textures keep the vanilla glint's shape: each pixel's brightness
 * (its strongest channel) becomes the new colour's strength. They are rebuilt only
 * when a colour or the blending choice changes (sync(), once a frame), from the
 * CURRENT resource pack's glint textures.
 *
 * Armor vs item: there are no shaders on 1.16.5, so the glint RenderType being
 * set up is recorded instead (GlintColorizerMixin, RenderStateShard.
 * setupRenderState HEAD): RenderType.armorGlint() / armorEntityGlint() are used
 * only by worn armor + elytra. One source texture on this version, so items and
 * held entity-models share one recoloured copy.
 */
public final class GlintColorizer {
	private static final String MOD = "glintcolorizer";
	private static final float VANILLA_GAIN = 255f / 204f;

	private static final ResourceLocation ITEM_TEX = new ResourceLocation("originclient", "glint/item");
	private static final ResourceLocation ARMOR_TEX = new ResourceLocation("originclient", "glint/armor");

	private static DynamicTexture item, armor;
	// Set by GlintColorizerMixin as each RenderType's state is set up (render thread):
	// true while one of the two armor glint types is the one being drawn.
	public static boolean armorTypeActive = false;
	private static String builtKey = null;
	private static boolean broken = false;

	private GlintColorizer() {
	}

	private static boolean lunar() {
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
			float[][] glint = intensity(ItemRenderer.ENCHANT_GLINT_LOCATION);
			item = paint(item, ITEM_TEX, glint, itemColor());
			armor = paint(armor, ARMOR_TEX, glint, armorColor());
			builtKey = key;
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: Glint Colorizer could not build its textures; glints stay vanilla", t);
		}
	}

	/** From GlintColorizerMixin, at the end of every glint pass's texturing setup. */
	public static void onGlintSetup(float scale) {
		if (broken || !Mods.on(MOD)) {
			return;
		}
		if (!Mods.bool(MOD, "showGlint")) {
			// Contribute nothing: keep the destination, add zero.
			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE,
					GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
			return;
		}
		boolean armorPass = armorTypeActive;
		if (armorPass ? !Mods.bool(MOD, "overrideArmor") : !Mods.bool(MOD, "overrideItem")) {
			return;
		}
		DynamicTexture tex = armorPass ? armor : item;
		if (tex == null || builtKey == null) {
			return;
		}
		tex.bind();   // texture unit 0, where the glint's texture shard bound vanilla's
		if (lunar()) {
			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
					GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
		}
	}

	// Decoded brightness per glint texture, re-read only when a different resource
	// pack supplies it -- so dragging a colour slider never re-decodes the PNG.
	private static final Map<ResourceLocation, Object[]> INTENSITY_CACHE = new HashMap<>();

	/** Per-pixel glint brightness 0..1 (strongest channel) of the active pack's texture. */
	private static float[][] intensity(ResourceLocation loc) throws Exception {
		// 1.16.5: getResource throws when missing and hands back a Closeable.
		try (Resource res = Minecraft.getInstance().getResourceManager().getResource(loc)) {
			String pack = res.getSourceName();
			Object[] cached = INTENSITY_CACHE.get(loc);
			if (cached != null && pack.equals(cached[0])) {
				return (float[][]) cached[1];
			}
			float[][] decoded = decode(res);
			INTENSITY_CACHE.put(loc, new Object[]{pack, decoded});
			return decoded;
		}
	}

	private static float[][] decode(Resource res) throws Exception {
		try (InputStream in = res.getInputStream(); NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in)) {
			float[][] out = new float[img.getWidth()][img.getHeight()];
			for (int x = 0; x < img.getWidth(); x++) {
				for (int y = 0; y < img.getHeight(); y++) {
					int abgr = img.getPixelRGBA(x, y);
					int max = Math.max(abgr & 0xFF, Math.max((abgr >> 8) & 0xFF, (abgr >> 16) & 0xFF));
					out[x][y] = max / 255f;
				}
			}
			return out;
		}
	}

	/**
	 * Lunar Blending: the colour itself, with alpha = brightness x the colour's
	 * opacity (alpha-blended over the item). Vanilla Blending: brightness x the
	 * colour, fully opaque (added as light, exactly like vanilla's purple).
	 */
	private static DynamicTexture paint(DynamicTexture tex, ResourceLocation id, float[][] inten, int argb) {
		int w = inten.length, h = inten[0].length;
		if (tex == null || tex.getPixels() == null
				|| tex.getPixels().getWidth() != w || tex.getPixels().getHeight() != h) {
			tex = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, w, h, false));
			Minecraft.getInstance().getTextureManager().register(id, tex);
		}
		NativeImage px = tex.getPixels();
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
					// x VANILLA_GAIN: Lunar's default colour (128,64,204) then lands
					// on vanilla's own glint brightness (whose purple peaks at full blue).
					pr = Math.min(255, Math.round(i * r * VANILLA_GAIN));
					pg = Math.min(255, Math.round(i * g * VANILLA_GAIN));
					pb = Math.min(255, Math.round(i * b * VANILLA_GAIN));
					pa = 255;
				}
				px.setPixelRGBA(x, y, (pa << 24) | (pb << 16) | (pg << 8) | pr);
			}
		}
		tex.upload();
		// upload() leaves NEAREST filtering; the glint is drawn LINEAR like vanilla's
		// (its texture shard asks for blur), and must wrap (it scrolls) -- upload's
		// default is already REPEAT.
		tex.setFilter(true, false);
		return tex;
	}
}
