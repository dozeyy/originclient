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
// (AbstractWidget.updateNarration -> Tooltip.updateNarration is untouched). Target (javap 1.19.3 + 1.19.4,
// Mojmap): AbstractWidget.updateTooltip()V, private, HEAD -- called from render(), the one place a widget hands
// its tooltip to the screen (Screen.setTooltipForNextRenderPass). 1.21.1 does the same on WidgetTooltipHolder,
// which does not exist yet on this era. OriginButtonRenderer.isDockTip is false for every other tooltip, so
// those stay vanilla's. If this stops applying (required:false config), the dock tooltips show again in
// vanilla's placement; nothing breaks.
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
