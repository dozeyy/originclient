package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.common.gui.GuiEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The RENDER half of the JEI toggle for JEI 10 (MC 1.18.2). Same method NAMES and
 * PoseStack draw params as the 1.19.x (JEI 11–13) render mixin, but JEI 10 moved
 * GuiEventHandler to the {@code mezz.jei.common.gui} package (it lived in
 * {@code mezz.jei.gui.events} from 11 onward). javap-verified against
 * jei-1.18.2-fabric-10.2.1.1002. See {@link JeiClientInputHandlerMixin} for the
 * input half and the remap=false / fail-soft rationale.
 */
@Mixin(value = GuiEventHandler.class, remap = false)
public abstract class JeiGuiEventHandlerMixin {

	@Inject(method = "onDrawScreenPost", at = @At("HEAD"), cancellable = true)
	private void originclient$hideJeiOverlay(Screen screen, PoseStack poseStack, int mouseX, int mouseY,
			CallbackInfo ci) {
		if (!Mods.on("jei")) {
			ci.cancel();
		}
	}

	@Inject(method = "onDrawForeground", at = @At("HEAD"), cancellable = true)
	private void originclient$hideJeiForeground(AbstractContainerScreen<?> screen, PoseStack poseStack,
			int mouseX, int mouseY, CallbackInfo ci) {
		if (!Mods.on("jei")) {
			ci.cancel();
		}
	}

	@Inject(method = "renderCompactPotionIndicators", at = @At("HEAD"), cancellable = true)
	private void originclient$vanillaPotionLayout(CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}
}
