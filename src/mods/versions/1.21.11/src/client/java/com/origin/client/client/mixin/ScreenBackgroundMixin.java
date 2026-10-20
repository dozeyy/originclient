package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The backdrop behind every out-of-world menu (options, world select, server
// list, create world, ...), from Will's clean mockup (2026-10-19):
//  - Minecraft's OWN screens: THIS version's vanilla panorama (whatever the
//    game jar ships -- 1.21.1 shows the 1.21 Tricky Trials one, every ported
//    module automatically shows its own), vanilla's blur, a dim and a soft
//    vignette (OriginScreenRenderer.renderMenuBackdrop).
//  - Origin's own screens (Right Shift mod menu, HUD editor, ...): unchanged,
//    the charcoal + rings backdrop with the cursor spotlight.
//
// Gated on Minecraft.level == null: in-game screens (pause, in-game options)
// keep vanilla's blurred-world backdrop -- replacing that would hide the world
// the player is standing in.
//
// No renderPanorama hook here: on 1.21.6+ renderPanorama lives on Screen and the
// title screen keeps vanilla's own panorama (TitleScreenMixin draws Origin's blur,
// dim and vignette right after it). The old title-only suppression is gone.
//
// Two hooks (both javap-confirmed against the mapped 1.21.11 Screen):
//  - renderBackground(GuiGraphics,int,int,float): the screen-level backdrop
//    (panorama + blur + menu texture). Cancelled and replaced only when the
//    Origin draw succeeded; a failed draw leaves vanilla's (fail-soft).
//    TitleScreen overrides this method, so its own mixin path is unaffected.
//    If this stops applying, menus show vanilla's stock backdrop.
//  - renderMenuBackgroundTexture(...): the static helper option/selection
//    LISTS use to tile their darker strip behind rows. Replaced by a flat
//    translucent panel so lists read as one calm surface over the panorama.
//    If this stops applying, lists show vanilla's dark strip.
// priority 2000: Origin's shared-screen background wins over other UI mods.
@Mixin(value = Screen.class, priority = 2000)
public class ScreenBackgroundMixin {

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$originBackdrop(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Vanilla mode (Button Style Minecraft + Main Menu Style Vanilla): stock backdrop.
		if (OriginStyle.vanillaMenusOn(this) || Minecraft.getInstance().level != null) {
			return;
		}
		Screen self = (Screen) (Object) this;
		// 1.21.6+ deferred GUI: renderBackground runs BEFORE render (Screen.
		// renderWithTooltipAndSubtitles), not inside it, so ProgressScreenMixin's
		// render-HEAD takeover no longer skips it. That takeover draws the whole
		// loading scene (panorama, blur, dim, vignette); drawing this backdrop too
		// would stack two dims and two vignettes (and vanilla allows one blur per
		// frame). Skip it while the takeover is live; when Origin rendering has
		// failed (isActive false) vanilla's backdrop comes back with vanilla's draw.
		if (self instanceof net.minecraft.client.gui.screens.ProgressScreen && OriginScreenRenderer.isActive()) {
			ci.cancel();
			return;
		}
		if (OriginWidgetOwnership.isOriginOwnClass(self)) {
			if (OriginScreenRenderer.renderTitleBackground(guiGraphics)) {
				OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, originclient$hoveringClickable());
				ci.cancel();
			}
			return;
		}
		if (OriginScreenRenderer.renderMenuBackdrop(self, guiGraphics, partialTick)) {
			ci.cancel();
		}
	}

	// In-world Origin screens: vanilla's blurred-world backdrop stays, the
	// spotlight draws on top of it. Minecraft's own screens get no spotlight.
	@Inject(method = "renderBackground", at = @At("TAIL"))
	private void originclient$inWorldGlow(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginWidgetOwnership.isOriginOwnClass(this)) {
			return;
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, originclient$hoveringClickable());
	}

	@Inject(method = "renderMenuBackgroundTexture", at = @At("HEAD"), cancellable = true)
	private static void originclient$noListTexture(GuiGraphics guiGraphics, Identifier texture, int x, int y, float uOffset, float vOffset, int width, int height, CallbackInfo ci) {
		// Gated on renderer health: if the Origin backdrop is broken, lists
		// get their vanilla strip back along with the vanilla background.
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null && !OriginStyle.vanillaMenusOn(mc.screen) && OriginScreenRenderer.isActive()) {
			if (mc.screen != null && !OriginWidgetOwnership.isOriginOwnClass(mc.screen)) {
				guiGraphics.fill(x, y, x + width, y + height, OriginTheme.CLEAN_PANEL);
			}
			ci.cancel();
		}
	}

	// Bloom the spotlight while any visible widget is hovered.
	private boolean originclient$hoveringClickable() {
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				return true;
			}
		}
		return false;
	}
}
