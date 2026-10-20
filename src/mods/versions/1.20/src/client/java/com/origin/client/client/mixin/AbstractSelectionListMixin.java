package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: AbstractSelectionList.render(GuiGraphics, int, int, float) HEAD (javap:
// the private renderBackground / renderTopAndBottom flags and the protected
// x0/y0/x1/y1 list box exist in 1.20 / 1.20.1).
//
// 1.20 option/world/server lists tile their OWN dirt -- behind the rows
// (`renderBackground`) and as top/bottom strips over the title/button areas
// (`renderTopAndBottom`). Together those cover the whole screen, painting over
// the panorama backdrop. So both flags go off while Origin's backdrop is up,
// and out of world, on Minecraft's screens, the rows get one flat translucent
// panel instead (OriginTheme.CLEAN_PANEL -- 1.21.1 swaps the list's darker
// menu-background strip for the same panel), so lists read as one calm surface
// over the panorama, with 1.21.1's thin header / footer separator lines where
// vanilla's dirt strips were. Origin's own screens keep their transparent lists.
//
// (1.20 scissors the rows to the list box itself, enableScissor in render, so
// nothing here has to clip them.)
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
public class AbstractSelectionListMixin {

	@Shadow private boolean renderBackground;
	@Shadow private boolean renderTopAndBottom;
	@Shadow protected int x0;
	@Shadow protected int y0;
	@Shadow protected int x1;
	@Shadow protected int y1;

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

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$transparentOnOrigin(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (!OriginScreenRenderer.isActive() || OriginStyle.vanillaMenusOn(mc.screen)) {
			originclient$restoreFlags();
			return;
		}
		boolean panel = mc.level == null && mc.screen != null && !OriginWidgetOwnership.isOriginOwnClass(mc.screen);
		if (panel && !OriginScreenRenderer.menuBackdropOk()) {
			originclient$restoreFlags();   // the backdrop failed: vanilla dirt stays
			return;
		}
		if (!this.originclient$flagsCleared) {
			this.originclient$savedBackground = this.renderBackground;
			this.originclient$savedTopAndBottom = this.renderTopAndBottom;
		}
		if (panel) {
			guiGraphics.fill(this.x0, this.y0, this.x1, this.y1, OriginTheme.CLEAN_PANEL);
			if (this.originclient$savedTopAndBottom) {
				// Where vanilla would have drawn its dirt header/footer strips, the
				// 1.21.1 separator lines instead (header_separator / footer_separator:
				// a faint light row over a dark one; colours sampled from the 1.21.1
				// shots). Fills, not the textures (match 1.19.4 / 1.18.2).
				guiGraphics.fill(this.x0, this.y0 - 2, this.x1, this.y0 - 1, SEPARATOR_LIGHT);
				guiGraphics.fill(this.x0, this.y0 - 1, this.x1, this.y0, SEPARATOR_DARK);
				guiGraphics.fill(this.x0, this.y1, this.x1, this.y1 + 1, SEPARATOR_LIGHT);
				guiGraphics.fill(this.x0, this.y1 + 1, this.x1, this.y1 + 2, SEPARATOR_DARK);
			}
		}
		this.renderBackground = false;
		this.renderTopAndBottom = false;
		this.originclient$flagsCleared = true;
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
}
