package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginDarkWidgets;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Target: AbstractButton.renderWidget, the texture id vanilla's button frame binds (widgets.png).
// Why: Origin's dark buttons (OriginDarkWidgets). Only the id changes: dark_widgets.png carries
// Origin's frames at widgets.png's button rows, so vanilla's own blits, text, alpha and hover run as is.
// Runs only when vanilla draws (AbstractButtonMixin's HEAD cancel already took Origin's own windows).
// If it stops applying: buttons draw vanilla's light-grey frames again (no crash).
@Mixin(AbstractButton.class)
public abstract class DarkButtonMixin {
	@ModifyArg(method = "renderWidget", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"), index = 1)
	private ResourceLocation originclient$darkButton(ResourceLocation vanilla) {
		return OriginDarkWidgets.texture(vanilla, OriginDarkWidgets.WIDGETS, OriginDarkWidgets.BUTTON);
	}
}
