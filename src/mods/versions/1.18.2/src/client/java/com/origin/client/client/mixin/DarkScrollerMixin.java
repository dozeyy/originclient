package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.OriginDarkWidgets;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: AbstractSelectionList.render(PoseStack, int, int, float) RETURN, plus the
// protected getScrollbarPosition() / getMaxPosition(), public getScrollAmount() and the
// protected y0 / y1 list box (Mojmap names, unchanged 1.16.5-1.19.4).
//
// Why: 1.21.1 draws every list scroll bar from Origin's dark widget/scroller +
// scroller_background sprites (jar overrides). Before 1.20.2 there are no sprites: the bar is
// three untextured fills inside render (black track, 0x808080 handle, 0xC0C0C0 highlight;
// Tesselator quads on the older versions), so a light-grey bar sat next to Origin's dark
// buttons (Controls, world list, servers, packs). Rather than redirect those draw calls, whose
// shape changes per version, this paints 1.21.1's dark track and handle over the same 6 px
// column once vanilla is done. The track covers the column's full height, so no vanilla pixel
// shows even if a version rounds the handle differently. Geometry is vanilla's (and 1.21.1's):
// handle height (box^2 / content) clamped to [32, box - 8], top = scroll * (box - h) / max.
// Colours are the sprites' own pixels (1 px #0B0B0C rim; track #0E0E0F; handle #5C5C61 lit
// top-left, #4A4A4F body, #36363A shaded bottom-right).
//
// Not gated on Main Menu Style: like the dark buttons it is how Minecraft's own widgets look
// with Origin installed. Fail-soft: a throw latches OriginDarkWidgets (vanilla widgets for the
// session); if this mixin stops applying, lists keep vanilla's grey bar (no crash).
@Mixin(AbstractSelectionList.class)
public abstract class DarkScrollerMixin {
	@Shadow protected int y0;
	@Shadow protected int y1;

	@Shadow
	protected abstract int getScrollbarPosition();

	@Shadow
	protected abstract int getMaxPosition();

	@Shadow
	public abstract double getScrollAmount();

	@Inject(method = "render", at = @At("RETURN"))
	private void originclient$darkScroller(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!OriginDarkWidgets.active(OriginDarkWidgets.SCROLLER)) {
			return;
		}
		try {
			int box = this.y1 - this.y0;
			int content = this.getMaxPosition();
			int max = Math.max(0, content - (box - 4));
			if (max <= 0 || box <= 8) {
				return;   // vanilla draws no bar either
			}
			int x = this.getScrollbarPosition();
			GuiComponent.fill(poseStack, x, this.y0, x + 6, this.y1, 0xFF0B0B0C);
			GuiComponent.fill(poseStack, x + 1, this.y0 + 1, x + 5, this.y1 - 1, 0xFF0E0E0F);
			int h = Math.max(32, Math.min(box - 8, (int) ((float) (box * box) / (float) content)));
			int top = Math.max(this.y0, (int) this.getScrollAmount() * (box - h) / max + this.y0);
			int bottom = top + h;
			GuiComponent.fill(poseStack, x, top, x + 6, bottom, 0xFF0B0B0C);
			GuiComponent.fill(poseStack, x + 1, top + 1, x + 5, bottom - 1, 0xFF36363A);
			GuiComponent.fill(poseStack, x + 1, top + 1, x + 4, bottom - 2, 0xFF5C5C61);
			GuiComponent.fill(poseStack, x + 2, top + 2, x + 4, bottom - 2, 0xFF4A4A4F);
		} catch (Throwable t) {
			OriginDarkWidgets.failed(t);
		}
	}
}
