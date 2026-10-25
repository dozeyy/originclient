package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: WidgetTooltipHolder.refreshTooltipForNextRenderPass(GuiGraphicsExtractor, int, int,
// boolean, boolean, ScreenRectangle) at HEAD (26.x signature, javap on 26.1.2/26.2/26.3).
// Why: the title dock's icon squares keep a tooltip for the narrator, but its hover card
// read as a second button (Will, 2026-10-25), so a dock tooltip
// (OriginButtonRenderer.isDockTip) never queues its card. Narration is a separate method
// (updateNarration) and is untouched. Every other tooltip in the game is left alone.
// If this stops applying: the dock's hover cards come back; nothing else breaks.
@Mixin(WidgetTooltipHolder.class)
public class WidgetTooltipHolderMixin {
	@Shadow
	private Tooltip tooltip;

	@Inject(method = "refreshTooltipForNextRenderPass", at = @At("HEAD"), cancellable = true)
	private void originclient$noDockCard(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
			boolean focused, ScreenRectangle rect, CallbackInfo ci) {
		if (OriginButtonRenderer.isDockTip(this.tooltip)) {
			ci.cancel();
		}
	}
}
