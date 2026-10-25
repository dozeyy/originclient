package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Title dock only: its tooltips sit beside the dock row, centred on the hovered tile, instead of vanilla's
// mouse-anchored box that flipped up against the Mods button (judge 2026-10-25). Target (javap 1.21.1):
// WidgetTooltipHolder.createTooltipPositioner(ScreenRectangle, boolean, boolean) -> ClientTooltipPositioner,
// private, HEAD. OriginButtonRenderer answers null for every tooltip that is not a registered dock tooltip, for
// the vanilla Main Menu Style and after a latched failure; then vanilla places it. If this stops applying, the
// dock tooltips go back to vanilla's placement (nothing breaks).
@Mixin(WidgetTooltipHolder.class)
public abstract class WidgetTooltipHolderMixin {
	@Shadow
	private Tooltip tooltip;

	@Inject(method = "createTooltipPositioner", at = @At("HEAD"), cancellable = true)
	private void originclient$dockTip(ScreenRectangle rect, boolean hovered, boolean focused,
									  CallbackInfoReturnable<ClientTooltipPositioner> cir) {
		ClientTooltipPositioner p = OriginButtonRenderer.dockTipPositioner(this.tooltip, rect);
		if (p != null) {
			cir.setReturnValue(p);
		}
	}
}
