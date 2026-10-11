package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.components.ImageButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin skin for the title screen's Language (globe) and Accessibility (person)
// buttons. Before 1.20.3 those are ImageButtons (SpriteIconButton does not exist
// yet), and ImageButton draws its own sprite inside renderButton, so the plain
// button mixin never reaches them. Only buttons the title screen has given an
// Origin presentation are restyled; every other ImageButton (Realms, servers,
// other mods) keeps its own art because renderImageButton returns false for them.
// priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = ImageButton.class, priority = 2000)
public class ImageButtonMixin {

	@Inject(method = "renderButton", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when Origin actually drew (fail-soft contract).
		if (OriginButtonRenderer.renderImageButton(new Gfx(poseStack), (ImageButton) (Object) this)) {
			ci.cancel();
		}
	}
}
