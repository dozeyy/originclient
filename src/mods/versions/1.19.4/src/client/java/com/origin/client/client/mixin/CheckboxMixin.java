package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.gui.components.Checkbox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Frost-style checkbox skin: a square box matching the buttons, a bright inner
// square when selected, label to the right. Toggle logic untouched; selected()
// is a public accessor (javap-confirmed for 1.21.1).
// priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = Checkbox.class, priority = 2000)
public class CheckboxMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when Origin actually drew (fail-soft contract).
		if (OriginButtonRenderer.renderCheckbox(new Gfx(poseStack), (Checkbox) (Object) this)) {
			ci.cancel();
		}
	}
}
