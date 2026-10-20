package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.theme.OriginTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: AbstractSelectionList.render(PoseStack, int, int, float) HEAD (javap:
// the private renderBackground / renderTopAndBottom flags and the protected
// x0/y0/x1/y1 list box exist in 1.19.2-1.19.4), plus its renderList call (javap
// 1.19.2/1.19.3: renderList(PoseStack, int, int, float) invoked from render).
//
// Pre-1.20 option/world/server lists tile their OWN dirt -- behind the rows
// (`renderBackground`) and as top/bottom strips over the title/button areas
// (`renderTopAndBottom`). Together those cover the whole screen, painting over
// the panorama backdrop. So both flags go off while Origin's backdrop is up,
// and out of world, on Minecraft's screens, the rows get one flat translucent
// panel instead (OriginTheme.CLEAN_PANEL -- 1.21.1 swaps the list's darker
// menu-background strip for the same panel), so lists read as one calm surface
// over the panorama, with 1.21.1's thin header / footer separator lines where
// vanilla's dirt strips were. Origin's own screens keep their transparent lists.
//
// The dirt strips also hid rows scrolled part-way out of the box, and 1.19.2 /
// 1.19.3 (unlike 1.19.4) don't scissor the rows themselves, so with the strips
// off the renderList call is clipped to the list box here, GUI units through
// GuiComponent's scissor stack (scaled to the window), released in a finally.
//
// In-world (not gated on level == null): in-world list screens -- notably
// Iris's ShaderPackScreen over a loaded world -- would draw the dirt strips
// over the game, so the flags go off there too, with no panel (the screen's
// own darkening gradient sits behind the list).
//
// Fail-soft: Main Menu Style = Vanilla or a broken renderer leaves vanilla's
// dirt untouched, and the list's own flags are put back the moment this stops
// applying (a backdrop that latched broken mid-session no longer leaves a list
// with neither dirt nor backdrop). If this stops applying, lists show vanilla
// dirt over the panorama.
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {

	@Shadow private boolean renderBackground;
	@Shadow private boolean renderTopAndBottom;
	@Shadow protected int x0;
	@Shadow protected int y0;
	@Shadow protected int x1;
	@Shadow protected int y1;

	@Shadow
	protected abstract void renderList(PoseStack poseStack, int x, int y, float partialTick);

	private static final int SEPARATOR_LIGHT = 0x33FFFFFF;
	private static final int SEPARATOR_DARK = 0x99000000;

	// The list's own flags as its screen set them, taken before this mixin clears
	// them, and whether they are cleared right now (so the early returns can put
	// them back).
	@Unique
	private boolean originclient$flagsCleared;
	@Unique
	private boolean originclient$savedBackground;
	@Unique
	private boolean originclient$savedTopAndBottom;
	// This frame's rows are clipped to the list box (the dirt strips are off).
	@Unique
	private boolean originclient$clipRows;

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$transparentOnOrigin(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		this.originclient$clipRows = false;
		Minecraft mc = Minecraft.getInstance();
		if (!OriginScreenRenderer.isActive() || OriginStyle.vanillaMenusOn(mc.screen)) {
			originclient$restoreFlags();
			return;
		}
		boolean panel = mc.level == null && mc.screen != null && !OriginWidgetOwnership.isOriginOwnClass(mc.screen);
		if (panel && !OriginScreenRenderer.menuBackdropActive()) {
			originclient$restoreFlags();   // the backdrop failed: vanilla dirt stays
			return;
		}
		if (!this.originclient$flagsCleared) {
			this.originclient$savedBackground = this.renderBackground;
			this.originclient$savedTopAndBottom = this.renderTopAndBottom;
		}
		if (panel) {
			GuiComponent.fill(poseStack, this.x0, this.y0, this.x1, this.y1, OriginTheme.CLEAN_PANEL);
			if (this.originclient$savedTopAndBottom) {
				// Where vanilla would have drawn its dirt header/footer strips, the
				// 1.21.1 separator lines instead (header_separator / footer_separator:
				// a faint light row over a dark one; colours sampled from the 1.21.1
				// shots). Fills, not the textures: those don't exist before 1.19.4.
				GuiComponent.fill(poseStack, this.x0, this.y0 - 2, this.x1, this.y0 - 1, SEPARATOR_LIGHT);
				GuiComponent.fill(poseStack, this.x0, this.y0 - 1, this.x1, this.y0, SEPARATOR_DARK);
				GuiComponent.fill(poseStack, this.x0, this.y1, this.x1, this.y1 + 1, SEPARATOR_LIGHT);
				GuiComponent.fill(poseStack, this.x0, this.y1 + 1, this.x1, this.y1 + 2, SEPARATOR_DARK);
			}
		}
		this.renderBackground = false;
		this.renderTopAndBottom = false;
		this.originclient$flagsCleared = true;
		this.originclient$clipRows = this.originclient$savedTopAndBottom;
	}

	// Puts the screen's own flags back after this mixin cleared them.
	@Unique
	private void originclient$restoreFlags() {
		if (this.originclient$flagsCleared) {
			this.renderBackground = this.originclient$savedBackground;
			this.renderTopAndBottom = this.originclient$savedTopAndBottom;
			this.originclient$flagsCleared = false;
		}
	}

	// Target: the renderList call inside AbstractSelectionList.render. Without the
	// dirt strips, rows scrolled part-way out would draw over the header, footer
	// and separator lines; clip them to y0..y1. Scissor off again in a finally, a
	// throwing row can't leave the GL scissor on for the rest of the frame. If
	// this stops applying, the rows overdraw the list edges. Vanilla's dirt-strip
	// case (clip off) is the plain call.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/AbstractSelectionList;renderList(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V"))
	private void originclient$clipRows(AbstractSelectionList<?> self, PoseStack poseStack, int x, int y, float partialTick) {
		boolean scissored = false;
		if (this.originclient$clipRows) {
			try {
				GuiComponent.enableScissor(this.x0, this.y0, this.x1, this.y1);
				scissored = true;
			} catch (Throwable t) {
				// unclipped rows beat no rows
			}
		}
		try {
			this.renderList(poseStack, x, y, partialTick);
		} finally {
			if (scissored) {
				try {
					GuiComponent.disableScissor();
				} catch (Throwable t) {
					// nothing more to do
				}
			}
		}
	}
}
