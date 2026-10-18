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

// The "Preparing for world creation..." / "Saving world" screen is
// GenericDirtMessageScreen before 1.20.5 (GenericMessageScreen after). 1.21.1
// shows the whole Origin loading scene with the screen's message as its title;
// here the same scene is painted at the HEAD of render() and vanilla's render is
// cancelled, since all it would add is the dirt and the same message as a plain
// centred string (this screen has no widgets). Fail-soft: if the scene cannot
// draw, nothing is cancelled and vanilla shows its dirt and text as before.
@Mixin(GenericDirtMessageScreen.class)
public class GenericDirtMessageScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originBackground(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginScreenRenderer.renderLoadingScene(new Gfx(poseStack), ((Screen) (Object) this).getTitle())) {
			ci.cancel();
		}
	}
}
