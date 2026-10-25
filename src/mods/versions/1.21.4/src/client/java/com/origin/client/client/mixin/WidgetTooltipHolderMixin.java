package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Title dock only: its glyphs' tooltips are never drawn (a hover card beside a dock glyph read as a second
// button, Will 2026-10-25), but they stay on the widgets so narration still says what each glyph does
// (WidgetTooltipHolder.updateNarration is untouched). Target (javap 1.21.4, same as 1.21.1, Mojmap):
// WidgetTooltipHolder.refreshTooltipForNextRenderPass(ZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V,
// public, HEAD -- the one place a widget hands its tooltip to the screen. OriginButtonRenderer.isDockTip answers
// false for every tooltip that is not a registered dock tooltip, so every other tooltip is vanilla's. If this
// stops applying, the dock tooltips show again in vanilla's placement (nothing breaks).
@Mixin(WidgetTooltipHolder.class)
public abstract class WidgetTooltipHolderMixin {
	@Shadow
	private Tooltip tooltip;

	@Inject(method = "refreshTooltipForNextRenderPass", at = @At("HEAD"), cancellable = true)
	private void originclient$hideDockTip(boolean hovered, boolean focused, ScreenRectangle rect, CallbackInfo ci) {
		if (OriginButtonRenderer.isDockTip(this.tooltip)) {
			ci.cancel();
		}
	}
}
