package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginDarkWidgets;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Target: AbstractSliderButton.renderBg, the widgets.png bind before the handle's split blit
// (rows 66 normal / 86 hovered, u 0..4 + 196..200).
// Why: Origin's dark slider handle (dark_slider.png has it at those UVs; OriginDarkWidgets).
// If it stops applying: the handle draws vanilla's light grey on a dark track (no crash).
@Mixin(AbstractSliderButton.class)
public abstract class DarkSliderHandleMixin {
	@ModifyArg(method = "renderBg", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"), index = 1)
	private ResourceLocation originclient$darkHandle(ResourceLocation vanilla) {
		return OriginDarkWidgets.texture(vanilla, OriginDarkWidgets.SLIDER, OriginDarkWidgets.SLIDER_HANDLE);
	}
}
