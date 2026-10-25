package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Title dock only: its glyphs' tooltips are never drawn (a hover card beside a dock glyph read as a second
// button, Will 2026-10-25), but they stay on the widgets so narration still says what each glyph does
// (AbstractWidget.updateNarration reads the tooltip field, untouched). 1.21.1 hooks WidgetTooltipHolder
// (1.20.5+) and 1.20/1.20.2 hook AbstractWidget.updateTooltip; 1.20.3/1.20.4 have neither: the hover/delay
// logic moved INTO Tooltip itself. Target (javap 1.20.4, Mojmap): Tooltip.refreshTooltipForNextRenderPass
// (ZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V, public, called from AbstractWidget.render --
// the one place a widget hands its tooltip to the screen. OriginButtonRenderer.isDockTip answers false for
// every tooltip that is not a registered dock tooltip, so every other widget is vanilla.
// If this stops applying, the dock tooltips show again in vanilla's placement (nothing breaks).
@Mixin(Tooltip.class)
public abstract class TooltipRefreshMixin {
	@Inject(method = "refreshTooltipForNextRenderPass", at = @At("HEAD"), cancellable = true)
	private void originclient$hideDockTip(boolean hovered, boolean focused, ScreenRectangle rect, CallbackInfo ci) {
		if (OriginButtonRenderer.isDockTip(this)) {
			ci.cancel();
		}
	}
}
