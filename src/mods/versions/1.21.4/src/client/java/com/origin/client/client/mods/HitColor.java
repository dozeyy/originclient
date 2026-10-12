package com.origin.client.client.mods;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mixin.OverlayTextureAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL13;

/**
 * Hit Color (Lunar parity): recolours the damage flash.
 *
 * How vanilla does the flash (1.21.1 source-verified; unchanged in 1.21.4): OverlayTexture is a 16x16
 * texture whose top 8 rows hold one pixel colour -- red, alpha 0xB2 -- and the
 * entity shaders mix it in as  colour = mix(overlay.rgb, colour, overlay.a)  for
 * any entity with hurtTime > 0. So the texture alpha is how much of the ORIGINAL
 * colour survives. Origin repaints those rows: the player's colour, with the
 * picker's opacity meaning "how strong the flash is" (alpha = 255 - opacity).
 * Turning the mod off paints vanilla's pixel back.
 *
 * sync() runs once a frame (GameRendererFrameMixin) and re-uploads only when the
 * wanted colour changed, so a steady colour costs one int compare per frame.
 *
 * Color Armor: vanilla's armor shader has no overlay sampler at all, so worn
 * armor never flashed in 1.21.1. While a hurt entity's armor draws, its vertex
 * colour is pulled toward the hit colour instead (HumanoidArmorLayerMixin +
 * EquipmentLayerTintMixin).
 *
 * PER-VERSION (1.21.2-1.21.4): NativeImage pixels are ARGB (setPixel) rather than
 * 1.21.1's ABGR, and NativeImage.upload lost its blur/clamp/mipmap flags (the
 * texture keeps them) -- the upload otherwise mirrors vanilla 1.21.4's
 * OverlayTexture constructor exactly.
 */
public final class HitColor {
	private static final String MOD = "hitcolor";
	/** Vanilla's pixel (ARGB, as 1.21.4's OverlayTexture writes it): red, alpha 0xB2. */
	private static final int VANILLA_ARGB = 0xB2FF0000;

	private static int applied = VANILLA_ARGB;
	private static boolean broken = false;

	/** Set by HumanoidArmorLayerMixin for the armor piece being drawn. */
	public static boolean armorHurt = false;

	private HitColor() {
	}

	public static void sync() {
		if (broken) {
			return;
		}
		int want = VANILLA_ARGB;
		if (Mods.on(MOD)) {
			int c = OriginColorPicker.liveColor(MOD, "color");
			int strength = (c >>> 24) & 0xFF;
			int r = (c >> 16) & 0xFF, gr = (c >> 8) & 0xFF, b = c & 0xFF;
			want = ((255 - strength) << 24) | (r << 16) | (gr << 8) | b;
		}
		if (want == applied) {
			return;
		}
		try {
			DynamicTexture tex = ((OverlayTextureAccessor) Minecraft.getInstance().gameRenderer.overlayTexture())
					.originclient$texture();
			NativeImage px = tex.getPixels();
			if (px == null) {
				return;
			}
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < 16; x++) {
					px.setPixel(x, y, want);
				}
			}
			// Same upload vanilla's constructor does: texture unit 1 (the overlay
			// sampler's unit), level 0, the whole image, then back to unit 0.
			RenderSystem.activeTexture(GL13.GL_TEXTURE1);
			tex.bind();
			px.upload(0, 0, 0, 0, 0, px.getWidth(), px.getHeight(), false);
			RenderSystem.activeTexture(GL13.GL_TEXTURE0);
			applied = want;
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: Hit Color could not repaint the overlay texture", t);
		}
	}

	/** Color Armor: the armor layer's vertex colour, pulled toward the hit colour
	 *  by its opacity while the wearer is hurt. Untouched otherwise. */
	public static int tintArmor(int argb) {
		if (!armorHurt || !Mods.on(MOD) || !Mods.bool(MOD, "colorArmor")) {
			return argb;
		}
		int c = OriginColorPicker.liveColor(MOD, "color");
		float s = ((c >>> 24) & 0xFF) / 255f;
		int r = Math.round(((argb >> 16) & 0xFF) * (1 - s + s * ((c >> 16) & 0xFF) / 255f));
		int g = Math.round(((argb >> 8) & 0xFF) * (1 - s + s * ((c >> 8) & 0xFF) / 255f));
		int b = Math.round((argb & 0xFF) * (1 - s + s * (c & 0xFF) / 255f));
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
}
