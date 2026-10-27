package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginDarkWidgets;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Target: AbstractWidget.renderButton, the texture id vanilla's button frame binds (widgets.png).
// Why: Origin's dark buttons (OriginDarkWidgets). Only the id changes: dark_widgets.png carries
// Origin's frames at widgets.png's button rows, so vanilla's own blits, text, alpha and hover run as is.
// Runs only when vanilla draws (AbstractButtonMixin's HEAD cancel already took Origin's own windows).
// If it stops applying: buttons draw vanilla's light-grey frames again (no crash).
@Mixin(AbstractWidget.class)
public abstract class DarkButtonMixin {
	@ModifyArg(method = "renderButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;bind(Lnet/minecraft/resources/ResourceLocation;)V"))
	private ResourceLocation originclient$darkButton(ResourceLocation vanilla) {
		// A slider's track is this same draw (getYImage is 0 for sliders): dark track row instead.
		if ((Object) this instanceof AbstractSliderButton) {
			return OriginDarkWidgets.texture(vanilla, OriginDarkWidgets.SLIDER, OriginDarkWidgets.SLIDER_TRACK);
		}
		return OriginDarkWidgets.texture(vanilla, OriginDarkWidgets.WIDGETS, OriginDarkWidgets.BUTTON);
	}
}
