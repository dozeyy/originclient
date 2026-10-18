package com.origin.client.client.mixin.loading;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The "Preparing Resources..." / "Saving world" screen is GenericDirtMessageScreen
// before 1.20.5 (GenericMessageScreen after). Like 1.21.1, the whole Origin
// loading scene draws here, the screen's title included, so vanilla's own render
// (dirt + centred title, no widgets) is cancelled -- but only for a frame the
// scene actually drew. Fail-soft: if it cannot draw, vanilla shows as before.
@Mixin(GenericDirtMessageScreen.class)
public class GenericDirtMessageScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originBackground(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginScreenRenderer.renderLoadingScene(new Gfx(poseStack), ((Screen) (Object) this).getTitle())) {
			ci.cancel();
		}
	}
}
