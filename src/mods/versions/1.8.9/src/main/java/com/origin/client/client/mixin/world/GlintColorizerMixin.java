package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.GlintColorizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.model.IBakedModel;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Glint Colorizer, item glint: RenderItem.renderEffect(IBakedModel) is the whole
// item glint pass (items in hand, dropped, in the GUI). OptiFine replaces
// RenderItem, so this is a HEAD cancel (robust there) that replays vanilla
// 1.8.9's pass with Origin's recoloured texture and a white vertex colour:
// depth EQUAL + no depth write, lighting off, the texture-matrix scroll
// (scale 8, +t/3000 with -50 deg, then -t/4873 with +10 deg), and back.
// Show Glint off = cancel with nothing drawn. Mod off / not overriding items /
// OptiFine shaders on = vanilla's (or OptiFine's) own pass runs untouched.
@Mixin(RenderItem.class)
public abstract class GlintColorizerMixin {

	@Inject(method = "renderEffect", at = @At("HEAD"), cancellable = true)
	private void originclient$recolorGlint(IBakedModel model, CallbackInfo ci) {
		try {
			if (GlintColorizer.hidden()) {
				ci.cancel();
				return;
			}
			Object self = this;
			if (!GlintColorizer.overriding(false) || !(self instanceof RenderItemInvoker)) {
				return;
			}
			RenderItemInvoker inv = (RenderItemInvoker) self;
			ci.cancel();
			GlStateManager.depthMask(false);
			GlStateManager.depthFunc(GL11.GL_EQUAL);
			GlStateManager.disableLighting();
			GlintColorizer.blend();
			GlintColorizer.bind(false);
			GlStateManager.matrixMode(GL11.GL_TEXTURE);
			try {
				GlStateManager.pushMatrix();
				GlStateManager.scale(8.0F, 8.0F, 8.0F);
				float f = (float) (Minecraft.getSystemTime() % 3000L) / 3000.0F / 8.0F;
				GlStateManager.translate(f, 0.0F, 0.0F);
				GlStateManager.rotate(-50.0F, 0.0F, 0.0F, 1.0F);
				inv.origin$renderModel(model, -1);
				GlStateManager.popMatrix();
				GlStateManager.pushMatrix();
				GlStateManager.scale(8.0F, 8.0F, 8.0F);
				float f1 = (float) (Minecraft.getSystemTime() % 4873L) / 4873.0F / 8.0F;
				GlStateManager.translate(-f1, 0.0F, 0.0F);
				GlStateManager.rotate(10.0F, 0.0F, 0.0F, 1.0F);
				inv.origin$renderModel(model, -1);
				GlStateManager.popMatrix();
			} finally {
				GlStateManager.matrixMode(GL11.GL_MODELVIEW);
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
				GlStateManager.enableLighting();
				GlStateManager.depthFunc(GL11.GL_LEQUAL);
				GlStateManager.depthMask(true);
				Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
			}
		} catch (Throwable t) {
			// a glint must never take a frame down
		}
	}
}
