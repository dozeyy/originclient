package com.origin.client.client.mods;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.render.LunarPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Glint Colorizer (Lunar parity): recolours the enchantment glint, items and
 * worn armor separately, or hides it.
 *
 * How vanilla 1.21.11 draws the glint (source-verified, RenderTypes/RenderSetup/
 * RenderType): a second pass over the model through one of four glint render
 * types -- armorEntityGlint (worn armour, texture enchanted_glint_armor.png),
 * glint / glintTranslucent / entityGlint (items and item-like entity models,
 * enchanted_glint_item.png) -- all on RenderPipelines.GLINT, which blends
 * additively (SRC_COLOR, ONE). Every one of them is finally drawn by
 * RenderType.draw(MeshData), which binds the pipeline and then the textures
 * RenderSetup.getTextures() hands back -- the single choke point
 * GlintColorizerMixin hooks (RenderType.draw, as 1.21.11):
 *   - Show Glint off: the glint draw is skipped (its mesh closed unused);
 *   - the Sampler0 texture is swapped for a recoloured copy (built here);
 *   - Lunar Blending: the pipeline is swapped for LunarPipelines.GLINT_BLENDED,
 *     vanilla's glint pipeline with a normal alpha blend -- additive light can
 *     only brighten, so dark colours (black, navy) would vanish otherwise.
 * The recoloured textures keep the vanilla glint's shape: each pixel's
 * brightness (its strongest channel) becomes the new colour's strength. They
 * are rebuilt only when a colour or the blending choice changes (sync(), once
 * a frame), from the CURRENT resource pack's glint textures, and drawn with the
 * vanilla glint texture's own sampler (same filtering + wrap as vanilla).
 *
 * PER-VERSION DELTA vs 1.21.1: 1.21.1 hooked RenderStateShard.setupGlintTexturing
 * and swapped GL state; that shard is gone here (state lives in RenderSetup /
 * RenderPipeline). Armour vs item is told apart by the render type itself, and
 * held entity-models share the item texture (1.21.11 entityGlint samples
 * enchanted_glint_item.png), so one item copy serves both.
 */
public final class GlintColorizer {
	private static final String MOD = "glintcolorizer";
	private static final float VANILLA_GAIN = 255f / 204f;

	private static final Identifier ITEM_TEX = Identifier.fromNamespaceAndPath("originclient", "glint/item");
	private static final Identifier ARMOR_TEX = Identifier.fromNamespaceAndPath("originclient", "glint/armor");

	private static DynamicTexture item, armor;
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
			item = paint(item, ITEM_TEX, intensity(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM), itemColor());
			armor = paint(armor, ARMOR_TEX, intensity(ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR), armorColor());
			builtKey = key;
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: Glint Colorizer could not build its textures; glints stay vanilla", t);
		}
	}

	// ---- called from GlintColorizerMixin (render thread) ----

	/** 0 = not a glint type, 1 = an item glint, 2 = the worn-armour glint. */
	private static int kind(Object renderType) {
		if (renderType == RenderTypes.armorEntityGlint()) {
			return 2;
		}
		if (renderType == RenderTypes.glint() || renderType == RenderTypes.glintTranslucent()
				|| renderType == RenderTypes.entityGlint()) {
			return 1;
		}
		return 0;
	}

	private static boolean active() {
		return !broken && Mods.on(MOD);
	}

	private static boolean overridden(int kind) {
		return kind == 2 ? Mods.bool(MOD, "overrideArmor") : Mods.bool(MOD, "overrideItem");
	}

	/** Show Glint off: skip every glint draw. */
	public static boolean hide(Object renderType) {
		return active() && kind(renderType) != 0 && !Mods.bool(MOD, "showGlint");
	}

	/** The pipeline a glint draw should bind (vanilla's unless Lunar Blending applies). */
	public static RenderPipeline pipeline(Object renderType, RenderPipeline vanilla) {
		int k = kind(renderType);
		if (k == 0 || !active() || builtKey == null || !lunar() || !overridden(k)) {
			return vanilla;
		}
		return LunarPipelines.glintBlended() != null ? LunarPipelines.glintBlended() : vanilla;
	}

	/** Swaps Sampler0 of a glint draw's texture map for the recoloured copy. */
	public static void swapTexture(Object renderType, Map<String, RenderSetup.TextureAndSampler> textures) {
		int k = kind(renderType);
		if (k == 0 || !active() || builtKey == null || !overridden(k) || textures == null) {
			return;
		}
		DynamicTexture tex = k == 2 ? armor : item;
		RenderSetup.TextureAndSampler vanilla = textures.get("Sampler0");
		if (tex == null || vanilla == null) {
			return;
		}
		textures.put("Sampler0", new RenderSetup.TextureAndSampler(tex.getTextureView(), vanilla.sampler()));
	}

	// Decoded brightness per glint texture, re-read only when a different resource
	// pack supplies it -- so dragging a colour slider never re-decodes the PNG.
	private static final Map<Identifier, Object[]> INTENSITY_CACHE = new HashMap<>();

	/** Per-pixel glint brightness 0..1 (strongest channel) of the active pack's texture. */
	private static float[][] intensity(Identifier loc) throws Exception {
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
		if (res.isEmpty()) {
			throw new IllegalStateException("missing " + loc);
		}
		String pack = res.get().sourcePackId();
		Object[] cached = INTENSITY_CACHE.get(loc);
		if (cached != null && pack.equals(cached[0])) {
			return (float[][]) cached[1];
		}
		float[][] decoded = decode(res.get());
		INTENSITY_CACHE.put(loc, new Object[]{pack, decoded});
		return decoded;
	}

	private static float[][] decode(Resource res) throws Exception {
		try (InputStream in = res.open(); NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in)) {
			float[][] out = new float[img.getWidth()][img.getHeight()];
			for (int x = 0; x < img.getWidth(); x++) {
				for (int y = 0; y < img.getHeight(); y++) {
					int argb = img.getPixel(x, y);
					int max = Math.max(argb & 0xFF, Math.max((argb >> 8) & 0xFF, (argb >> 16) & 0xFF));
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
	private static DynamicTexture paint(DynamicTexture tex, Identifier id, float[][] inten, int argb) {
		int w = inten.length, h = inten[0].length;
		if (tex == null || tex.getPixels() == null
				|| tex.getPixels().getWidth() != w || tex.getPixels().getHeight() != h) {
			tex = new DynamicTexture(() -> "Origin glint " + id.getPath(), new NativeImage(NativeImage.Format.RGBA, w, h, false));
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
				px.setPixel(x, y, (pa << 24) | (pr << 16) | (pg << 8) | pb);   // ARGB on 1.21.11
			}
		}
		tex.upload();
		return tex;
	}
}
