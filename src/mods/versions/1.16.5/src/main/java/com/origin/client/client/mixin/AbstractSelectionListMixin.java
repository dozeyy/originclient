package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.theme.OriginTheme;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Targets (javap, mapped 1.16.5): AbstractSelectionList.render(PoseStack, int,
// int, float) HEAD, and renderList(PoseStack, int, int, int, int, float) HEAD +
// RETURN; the private renderBackground / renderTopAndBottom flags and the
// protected x0/y0/x1/y1 list box.
//
// Pre-1.20 option/world/server lists tile their OWN dirt -- behind the rows
// (`renderBackground`) and as top/bottom strips over the title/button areas
// (`renderTopAndBottom`). Together those cover the whole screen, painting over
// the backdrop. So both flags go off while Origin rendering is healthy, and out
// of world, on Minecraft's screens, the list gets 1.21.1's look instead (Will's
// clean mockup, 2026-10-19): one flat translucent panel behind the rows
// (OriginTheme.CLEAN_PANEL), a thin header and footer separator line, and the
// rows clipped to the list box (the dirt strips used to hide rows scrolled half
// out of it; 1.21.1 clips them with a scissor). Origin's own screens keep their
// transparent, unclipped lists.
//
// In-world (not gated on level == null): in-world list screens -- notably Iris's
// ShaderPackScreen over a loaded world -- would draw the dirt strips over the
// game, so the flags go off there too, with no panel (the screen's own
// darkening gradient sits behind the list).
//
// Fail-soft: Main Menu Style = Vanilla or a broken renderer leaves vanilla's
// dirt untouched. If this stops applying, lists show vanilla dirt over the
// panorama (the menus still work).
@Mixin(AbstractSelectionList.class)
public class AbstractSelectionListMixin {

	@Shadow private boolean renderBackground;
	@Shadow private boolean renderTopAndBottom;
	@Shadow protected int x0;
	@Shadow protected int y0;
	@Shadow protected int x1;
	@Shadow protected int y1;

	// Set at render HEAD for this frame's draw: the rows are scissored to the box.
	@Unique
	private boolean originclient$clip;

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$transparentOnOrigin(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		originclient$clip = false;
		Minecraft mc = Minecraft.getInstance();
		if (!OriginScreenRenderer.isActive() || OriginStyle.vanillaMenusOn(mc.screen)) {
			return;
		}
		if (mc.level == null && mc.screen != null && !OriginWidgetOwnership.isOriginOwnClass(mc.screen)) {
			if (!OriginScreenRenderer.menuBackdropActive()) {
				return;   // the backdrop failed: vanilla dirt stays
			}
			GuiComponent.fill(poseStack, this.x0, this.y0, this.x1, this.y1, OriginTheme.CLEAN_PANEL);
			// 1.21.1's header / footer separators: a light line over a dark one at
			// the top of the box, the mirror at the bottom.
			GuiComponent.fill(poseStack, this.x0, this.y0 - 2, this.x1, this.y0 - 1, 0x33FFFFFF);
			GuiComponent.fill(poseStack, this.x0, this.y0 - 1, this.x1, this.y0, 0x99000000);
			GuiComponent.fill(poseStack, this.x0, this.y1, this.x1, this.y1 + 1, 0x99000000);
			GuiComponent.fill(poseStack, this.x0, this.y1 + 1, this.x1, this.y1 + 2, 0x33FFFFFF);
			originclient$clip = this.y1 > this.y0 && this.x1 > this.x0;
		}
		this.renderBackground = false;
		this.renderTopAndBottom = false;
	}

	@Inject(method = "renderList", at = @At("HEAD"))
	private void originclient$clipRows(PoseStack poseStack, int x, int y, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (originclient$clip) {
			// GL scissor is window pixels from the bottom-left; the box is GUI px from the top-left.
			com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
			double s = window.getGuiScale();
			RenderSystem.enableScissor((int) (this.x0 * s), (int) (window.getHeight() - this.y1 * s),
					Math.max(0, (int) ((this.x1 - this.x0) * s)), Math.max(0, (int) ((this.y1 - this.y0) * s)));
		}
	}

	@Inject(method = "renderList", at = @At("RETURN"))
	private void originclient$unclipRows(PoseStack poseStack, int x, int y, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (originclient$clip) {
			RenderSystem.disableScissor();
		}
	}
}
