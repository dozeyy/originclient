package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Title dock only: its glyphs' tooltips are never drawn (a hover card beside a dock glyph read as a second
// button, Will 2026-10-25), but they stay on the widgets so narration still says what each glyph does
// (AbstractWidget.updateWidgetNarration reads the tooltip field, untouched). 1.21.1 hooks
// WidgetTooltipHolder, which does not exist before 1.21: here the tooltip lives on AbstractWidget itself.
// Target (javap 1.20, Mojmap): AbstractWidget.updateTooltip()V, private, called from render() -- the one
// place a widget hands its tooltip to the screen (setTooltipForNextRenderPass). OriginButtonRenderer.isDockTip
// answers false for every tooltip that is not a registered dock tooltip, so every other widget is vanilla.
// If this stops applying, the dock tooltips show again in vanilla's placement (nothing breaks).
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetTooltipMixin {
	@Shadow
	private Tooltip tooltip;

	@Inject(method = "updateTooltip", at = @At("HEAD"), cancellable = true)
	private void originclient$hideDockTip(CallbackInfo ci) {
		if (OriginButtonRenderer.isDockTip(this.tooltip)) {
			ci.cancel();
		}
	}
}
