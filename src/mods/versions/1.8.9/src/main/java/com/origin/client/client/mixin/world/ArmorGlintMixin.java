package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.GlintColorizer;
import com.origin.client.client.mods.HitColor;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Worn armor, two Origin mods on LayerArmorBase (HEAD/RETURN only: OptiFine
// patches this class for its custom armor glints):
//  - Glint Colorizer, armor glint: renderGlint is the armor glint pass. HEAD
//    cancel + replay of vanilla 1.8.9's pass (two model renders, texture matrix
//    scale 1/3, rotate 30 - 60i, scroll ticks x (0.001 + 0.003i) x 20) with
//    Origin's recoloured texture under vanilla's 0.76 glColor dim. Show Glint
//    off = nothing drawn.
//  - Hit Color > Color Armor: shouldCombineTextures() is false for armor, which
//    is why 1.8 armor never flashes red; answering true while Color Armor is on
//    lets RendererLivingEntity.setBrightness apply the (recoloured) hurt flash to
//    the armor too -- the 1.7 look Lunar's option restores.
// renderGlint(EntityLivingBase, T model, limbSwing, limbSwingAmount, partialTicks,
// ageInTicks, netHeadYaw, headPitch, scale): T erases to ModelBase; the model is
// rendered with (entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw,
// headPitch, scale), as vanilla does.
@Mixin(LayerArmorBase.class)
public abstract class ArmorGlintMixin {

	@Inject(method = "renderGlint", at = @At("HEAD"), cancellable = true)
	private void originclient$armorGlint(EntityLivingBase entity, ModelBase model, float limbSwing,
										 float limbSwingAmount, float partialTicks, float ageInTicks,
										 float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
		try {
			if (GlintColorizer.hidden()) {
				ci.cancel();
				return;
			}
			if (!GlintColorizer.overriding(true) || model == null) {
				return;
			}
			ci.cancel();
			float f = (float) entity.ticksExisted + partialTicks;
			GlintColorizer.bind(true);
			GlStateManager.enableBlend();
			GlStateManager.depthFunc(GL11.GL_EQUAL);
			GlStateManager.depthMask(false);
			float dim = GlintColorizer.lunar() ? 1f : GlintColorizer.ARMOR_DIM;
			try {
				for (int i = 0; i < 2; ++i) {
					GlStateManager.disableLighting();
					GlintColorizer.blend();
					GlStateManager.color(dim, dim, dim, 1.0F);
					GlStateManager.matrixMode(GL11.GL_TEXTURE);
					GlStateManager.loadIdentity();
					GlStateManager.scale(0.33333334F, 0.33333334F, 0.33333334F);
					GlStateManager.rotate(30.0F - (float) i * 60.0F, 0.0F, 0.0F, 1.0F);
					GlStateManager.translate(0.0F, f * (0.001F + (float) i * 0.003F) * 20.0F, 0.0F);
					GlStateManager.matrixMode(GL11.GL_MODELVIEW);
					model.render(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale);
				}
			} finally {
				GlStateManager.matrixMode(GL11.GL_TEXTURE);
				GlStateManager.loadIdentity();
				GlStateManager.matrixMode(GL11.GL_MODELVIEW);
				GlStateManager.enableLighting();
				GlStateManager.depthMask(true);
				GlStateManager.depthFunc(GL11.GL_LEQUAL);
				GlStateManager.disableBlend();
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
				GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
			}
		} catch (Throwable t) {
			// a glint must never take a frame down
		}
	}

	@Inject(method = "shouldCombineTextures", at = @At("RETURN"), cancellable = true)
	private void originclient$armorFlash(CallbackInfoReturnable<Boolean> cir) {
		try {
			if (!cir.getReturnValueZ() && HitColor.armorFlashes()) {
				cir.setReturnValue(true);
			}
		} catch (Throwable ignored) {
			// armor keeps vanilla's no-flash
		}
	}
}
