package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
//    the charcoal backdrop (glow, grain, vignette) with the cursor spotlight.
//
// Gated on Minecraft.level == null: in-game screens (pause, in-game options)
// keep vanilla's blurred-world backdrop -- replacing that would hide the world
// the player is standing in.
//
// Two hooks (javap-confirmed against the mapped 1.20.2 Screen):
//  - renderBackground(GuiGraphics,int,int,float): out of a world it only draws vanilla's dirt, so it
//    is cancelled: the Origin backdrop was already drawn by the beforeRender
//    screen event (OriginClientMod). TitleScreen draws its own backdrop
//    (TitleScreenMixin). If this stops applying, vanilla's dirt paints over the
//    panorama backdrop on every menu.
//  - renderDirtBackground(GuiGraphics): still exists on 1.20.x (removed in
//    1.20.5) and some screens call it directly, so it's suppressed too. If this
//    stops applying, those screens (the "Preparing..." transitions) show dirt.
// 1.20.x has no static renderMenuBackgroundTexture helper (that arrived in
// 1.20.5); the translucent list panel lives in AbstractSelectionListMixin.
// priority 2000: Origin's shared-screen background wins over other UI mods.
@Mixin(value = Screen.class, priority = 2000)
public class ScreenBackgroundMixin {

	// The Origin backdrop itself is drawn ONCE per menu by the beforeRender screen
	// event (OriginClientMod) -- before any screen content, so it clears the frame
	// even for list screens (SelectWorld, multiplayer) that never call
	// renderBackground and would otherwise show the previous screen bleeding
	// through. These two hooks only CANCEL vanilla's dirt so it can't paint over
	// that backdrop. Fail-soft: when the backdrop can't draw, nothing is cancelled
	// and vanilla's backdrop returns.
	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanillaBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (originclient$originBackdropDrawn()) {
			ci.cancel();
		}
	}

	// Some screens (GenericDirtMessageScreen -- the "Preparing..." transition on
	// world create/load) call renderDirtBackground directly instead of through
	// renderBackground, so the hook above never fires for them.
	@Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanillaDirt(GuiGraphics guiGraphics, CallbackInfo ci) {
		if (originclient$originBackdropDrawn()) {
			ci.cancel();
		}
	}

	/** The same test the beforeRender event uses to decide it drew the Origin backdrop. */
	@Unique
	private boolean originclient$originBackdropDrawn() {
		Screen self = (Screen) (Object) this;
		if (Minecraft.getInstance().level != null || OriginStyle.vanillaMenusOn(self)
				|| !OriginScreenRenderer.isActive()) {
			return false;
		}
		return OriginWidgetOwnership.isOriginOwnClass(self) || OriginScreenRenderer.menuBackdropOk();
	}

	// In-world Origin screens: vanilla's darkened-world backdrop stays, the
	// spotlight draws on top of it. Minecraft's own screens get no spotlight.
	// Out of a world this never runs: the HEAD hook cancels renderBackground first.
	@Inject(method = "renderBackground", at = @At("TAIL"))
	private void originclient$inWorldGlow(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginWidgetOwnership.isOriginOwnClass(this)) {
			return;
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, originclient$hoveringClickable());
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
