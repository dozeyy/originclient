package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * Hit Color (Lunar parity): recolours the damage flash.
 *
 * How vanilla does the flash on 1.8.9 (RendererLivingEntity.setBrightness): for
 * an entity with hurtTime > 0 (or dying) it switches texture unit 0 to a
 * GL_COMBINE / GL_INTERPOLATE texture environment against a CONSTANT colour --
 * (1, 0, 0) with alpha 0.3 -- so the model is pulled 30% toward red. The constant
 * alpha is how strong the flash is.
 *
 * 1.8.9 METHOD: HitColorMixin hooks setBrightness at RETURN; when vanilla just set
 * up the hurt flash, {@link #applyFlash} re-sends GL_TEXTURE_ENV_COLOR with the
 * player's colour, the picker's opacity as the flash strength (same meaning as
 * the newer versions' overlay-texture alpha: 255 - alpha survives). Nothing to
 * upload, nothing to restore -- vanilla's unsetBrightness resets the env.
 * (sync() is kept for the shared per-frame call shape; 1.8.9 has no overlay
 * texture to repaint.)
 *
 * Color Armor: vanilla 1.8.9 armor does NOT flash (LayerArmorBase's
 * shouldCombineTextures() is false, so setBrightness skips it -- the 1.7 red
 * armor is what Lunar's option brings back). With Color Armor on,
 * HitColorMixin answers true there, so worn armor takes the same (recoloured)
 * flash as the body. {@link #armorHurt} / {@link #tintArmor} keep the newer
 * versions' API.
 *
 * With OptiFine shaders on, OptiFine feeds the entity colour to its shader
 * (Shaders.setEntityColor) instead of the texture env; the recolour is skipped
 * there and the pack's own flash shows.
 */
public final class HitColor {
	private static final String MOD = "hitcolor";

	private static final FloatBuffer ENV = BufferUtils.createFloatBuffer(4);

	/** Kept for the newer versions' API (the armor layer's hurt state); unused on 1.8.9. */
	public static boolean armorHurt = false;

	private HitColor() {
	}

	/** Per-frame upkeep on the other versions (overlay texture repaint); nothing to do on 1.8.9. */
	public static void sync() {
	}

	/** Color Armor: whether worn armor should take the hurt flash (LayerArmorBase.shouldCombineTextures). */
	public static boolean armorFlashes() {
		return Mods.on(MOD) && Mods.bool(MOD, "colorArmor") && !WorldHooks.optifineShadersActive();
	}

	/**
	 * From HitColorMixin at RendererLivingEntity.setBrightness RETURN, only when it
	 * returned true (a flash/colour env is set up). Recolours the hurt flash.
	 */
	public static void applyFlash(EntityLivingBase entity) {
		if (entity == null || !(entity.hurtTime > 0 || entity.deathTime > 0) || !Mods.on(MOD)
				|| WorldHooks.optifineShadersActive()) {
			return;
		}
		int c = OriginColorPicker.liveColor(MOD, "color");
		ENV.clear();
		ENV.put(((c >> 16) & 0xFF) / 255f);
		ENV.put(((c >> 8) & 0xFF) / 255f);
		ENV.put((c & 0xFF) / 255f);
		ENV.put(((c >>> 24) & 0xFF) / 255f);   // flash strength = the picker's opacity
		ENV.flip();
		// vanilla left unit 0 (defaultTexUnit) active; its env is what tints the model
		GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
		GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, ENV);
	}

	/** Newer versions' Color Armor tint; on 1.8.9 armor takes the real flash instead (see class doc). */
	public static float tintArmor(float value, int channel) {
		return value;
	}
}
