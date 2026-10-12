package com.origin.client.client.mods;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Glint Colorizer (Lunar parity): recolours the enchantment glint, items and
 * worn armor separately, or hides it.
 *
 * How vanilla 1.21.1 draws the glint (source-verified, RenderType/RenderStateShard):
 * a second pass over the model with a scrolling purple texture
 * (enchanted_glint_item.png for item models, enchanted_glint_entity.png for armor
 * and entity-models like tridents), blended additively (SRC_COLOR, ONE). Every
 * glint RenderType's texturing shard calls RenderStateShard.setupGlintTexturing,
 * which runs AFTER the texture, shader and blend shards (CompositeState order).
 * GlintColorizerMixin hooks its tail and calls onGlintSetup, which can then:
 *   - swap Sampler0 to a recoloured copy of the glint texture (built here), and
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
 * Armor vs item is told apart by the active shader (rendertype_armor_entity_glint
 * is used only by worn armor + elytra); item models use the 8x-scaled item
 * texturing, everything else (tridents, shields in hand) the entity one.
 *
 * PER-VERSION (1.21.6-1.21.8, verified against the decompiled 1.21.6 and 1.21.8
 * RenderType / RenderStateShard / RenderPipelines / ItemRenderer): the shader and
 * the blend are no longer GL state -- every glint render type draws with the one
 * RenderPipelines.GLINT (blend GLINT = SRC_COLOR, ONE), and CompositeRenderType
 * .draw runs the shards' setup (texture first, texturing last) BEFORE it reads
 * the shader textures and sets the pipeline. So:
 *   - the texture swap is unchanged: the texturing hook's setShaderTexture(0, ...)
 *     is what draw() binds as Sampler0;
 *   - the blend swap becomes a PIPELINE swap: onGlintSetup leaves the wanted
 *     variant in `pending`, and GlintPipelineMixin hands it to that draw's
 *     RenderPass.setPipeline instead of GLINT (only GLINT is ever replaced; the
 *     variants are vanilla's GLINT with just the blend changed);
 *   - the three passes have their own texturing scale here -- 8 items, 0.5 held/
 *     entity models (both sampling enchanted_glint_item), 0.16 worn armour (its
 *     own enchanted_glint_armor) -- so the scale alone tells them apart.
 * NativeImage pixels are ARGB (getPixel/setPixel) instead of 1.21.1's ABGR.
 */
public final class GlintColorizer {
	private static final String MOD = "glintcolorizer";
	private static final float ITEM_GLINT_SCALE = 8.0F;    // RenderStateShard.GLINT_TEXTURING
	private static final float ARMOR_GLINT_SCALE = 0.16F;  // RenderStateShard.ARMOR_ENTITY_GLINT_TEXTURING
	private static final float VANILLA_GAIN = 255f / 204f;

	private static final ResourceLocation ITEM_TEX = ResourceLocation.fromNamespaceAndPath("originclient", "glint/item");
	private static final ResourceLocation HELD_TEX = ResourceLocation.fromNamespaceAndPath("originclient", "glint/held");
	private static final ResourceLocation ARMOR_TEX = ResourceLocation.fromNamespaceAndPath("originclient", "glint/armor");

	// Vanilla's GLINT pipeline (1.21.8 RenderPipelines) with only the blend changed.
	private static RenderPipeline glintVariant(String name, BlendFunction blend) {
		return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET,
						RenderPipelines.FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
				.withLocation(ResourceLocation.fromNamespaceAndPath("originclient", "pipeline/" + name))
				.withVertexShader("core/glint")
				.withFragmentShader("core/glint")
				.withSampler("Sampler0")
				.withDepthWrite(false)
				.withCull(false)
				.withDepthTestFunction(DepthTestFunction.EQUAL_DEPTH_TEST)
				.withBlend(blend)
				.withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
				.build());
	}

	/** Show Glint off: contribute nothing -- keep the destination, add zero. */
	private static final RenderPipeline HIDDEN = glintVariant("glint_hidden",
			new BlendFunction(SourceFactor.ZERO, DestFactor.ONE, SourceFactor.ZERO, DestFactor.ONE));
	/** Lunar Blending: the colour laid over the item by its alpha. */
	private static final RenderPipeline LUNAR = glintVariant("glint_lunar",
			new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ZERO, DestFactor.ONE));

	// The pipeline the glint pass being set up should draw with (null = GLINT).
	// Set by onGlintSetup, consumed by the same draw's setPipeline (render thread).
	private static RenderPipeline pending = null;

	private static DynamicTexture item, held, armor;
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
			float[][] itemI = intensity(ItemRenderer.ENCHANTED_GLINT_ITEM);
			float[][] armorI = intensity(ItemRenderer.ENCHANTED_GLINT_ARMOR);
			item = paint(item, ITEM_TEX, itemI, itemColor());
			held = paint(held, HELD_TEX, itemI, itemColor());   // entity glint samples the item texture here
			armor = paint(armor, ARMOR_TEX, armorI, armorColor());
			builtKey = key;
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: Glint Colorizer could not build its textures; glints stay vanilla", t);
		}
	}

	/** From GlintColorizerMixin, at the end of every glint pass's texturing setup. */
	public static void onGlintSetup(float scale) {
		pending = null;
		if (broken || !Mods.on(MOD)) {
			return;
		}
		if (!Mods.bool(MOD, "showGlint")) {
			pending = HIDDEN;
			return;
		}
		boolean armorPass = scale == ARMOR_GLINT_SCALE;
		if (armorPass ? !Mods.bool(MOD, "overrideArmor") : !Mods.bool(MOD, "overrideItem")) {
			return;
		}
		DynamicTexture tex = armorPass ? armor : scale == ITEM_GLINT_SCALE ? item : held;
		if (tex == null || builtKey == null) {
			return;
		}
		RenderSystem.setShaderTexture(0, tex.getTextureView());
		if (lunar()) {
			pending = LUNAR;
		}
	}

	/** From GlintPipelineMixin: the pipeline a render-type draw actually uses. */
	public static RenderPipeline pipeline(RenderPipeline vanilla) {
		RenderPipeline p = pending;
		pending = null;
		return p != null && vanilla == RenderPipelines.GLINT ? p : vanilla;
	}

	// Decoded brightness per glint texture, re-read only when a different resource
	// pack supplies it -- so dragging a colour slider never re-decodes the PNG.
	private static final Map<ResourceLocation, Object[]> INTENSITY_CACHE = new HashMap<>();

	/** Per-pixel glint brightness 0..1 (strongest channel) of the active pack's texture. */
	private static float[][] intensity(ResourceLocation loc) throws Exception {
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
	private static DynamicTexture paint(DynamicTexture tex, ResourceLocation id, float[][] inten, int argb) {
		int w = inten.length, h = inten[0].length;
		if (tex == null || tex.getPixels() == null
				|| tex.getPixels().getWidth() != w || tex.getPixels().getHeight() != h) {
			tex = new DynamicTexture(() -> "Origin glint", new NativeImage(NativeImage.Format.RGBA, w, h, false));
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
				px.setPixel(x, y, (pa << 24) | (pr << 16) | (pg << 8) | pb);
			}
		}
		tex.upload();
		// The glint is drawn LINEAR like vanilla's and must wrap (it scrolls); both
		// are set on the GPU texture here (1.21.5+), explicitly rather than trusting
		// the defaults.
		tex.setFilter(true, false);
		tex.setClamp(false);
		return tex;
	}
}
