package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.gui.events.GuiEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The RENDER half of the JEI toggle for the pre-1.20 (PoseStack) JEI line —
 * JEI 10/11/12/13 (MC 1.18.2 / 1.19.2 / 1.19.3 / 1.19.4). Same method NAMES as
 * the GuiGraphics-era mixin (onDrawScreenPost / onDrawForeground /
 * renderCompactPotionIndicators), but JEI draws through a {@code PoseStack}
 * (class_4587) here, not a GuiGraphics — GuiGraphics does not exist before MC
 * 1.20. javap-verified against jei-1.19.4-fabric-13.1.0.19 (and 12.x / 11.x,
 * identical shapes).
 *
 * See {@link JeiClientInputHandlerMixin} for the input half and the remap=false /
 * fail-soft rationale. With JEI off, cancelling these leaves vanilla drawing the
 * screen untouched — "off" is indistinguishable from JEI not being installed.
 */
@Mixin(value = GuiEventHandler.class, remap = false)
public abstract class JeiGuiEventHandlerMixin {

	/** The overlay/bookmark/config-button draw pass. Cancelling makes the screen look stock. */
	@Inject(method = "onDrawScreenPost", at = @At("HEAD"), cancellable = true)
	private void originclient$hideJeiOverlay(Screen screen, PoseStack poseStack, int mouseX, int mouseY,
			CallbackInfo ci) {
		if (!Mods.on("jei")) {
			ci.cancel();
		}
	}

	/** The foreground pass over container screens. */
	@Inject(method = "onDrawForeground", at = @At("HEAD"), cancellable = true)
	private void originclient$hideJeiForeground(AbstractContainerScreen<?> screen, PoseStack poseStack,
			int mouseX, int mouseY, CallbackInfo ci) {
		if (!Mods.on("jei")) {
			ci.cancel();
		}
	}

	/**
	 * JEI reaches into VANILLA's inventory layout: its EffectRenderingInventoryScreen
	 * mixin asks this whether to force potion effects into compact indicators to make
	 * room for the overlay. Returning false hands the vanilla layout back so "off"
	 * doesn't leave a squashed potion display.
	 */
	@Inject(method = "renderCompactPotionIndicators", at = @At("HEAD"), cancellable = true)
	private void originclient$vanillaPotionLayout(CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}
}
