package com.origin.client.client.mixin.menus;

import com.origin.client.client.gui.OriginDarkWidgets;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Dark vanilla buttons, the slider knobs (OriginDarkWidgets). GuiOptionSlider -- and OptiFine's
// sliders, which copy it -- rebind widgets.png inside mouseDragged before blitting the knob, after
// GuiButtonMixin already pointed the box at the dark page. While a vanilla button is drawing (and
// only then), that rebind is pointed at the dark page too; every other bind passes through after one
// static boolean read.
//
// 1.8.9: target TextureManager.bindTexture(ResourceLocation) = func_110577_a. OptiFine REPLACES
// TextureManager (random mobs, shaders), so this is HEAD-only: a @ModifyVariable on the argument at
// HEAD, no CallbackInfo, nothing inside the body it rewrites. If it stops applying, option-slider
// knobs stay vanilla grey on the dark track; nothing else changes.
@Mixin(TextureManager.class)
public abstract class TextureManagerDarkWidgetsMixin {

	@ModifyVariable(method = "bindTexture(Lnet/minecraft/util/ResourceLocation;)V", at = @At("HEAD"), argsOnly = true)
	private ResourceLocation originclient$darkKnob(ResourceLocation texture) {
		return OriginDarkWidgets.rebind(texture);
	}
}
