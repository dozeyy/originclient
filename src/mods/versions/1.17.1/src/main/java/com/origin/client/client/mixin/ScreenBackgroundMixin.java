package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The backdrop behind every out-of-world menu (options, world select, server
// list, create world, ...), from Will's clean mockup (2026-10-19):
//  - Minecraft's OWN screens: THIS version's vanilla panorama, blurred, dimmed
//    and vignetted (OriginScreenRenderer.renderMenuBackdrop).
//  - Origin's own screens (Right Shift mod menu, HUD editor, ...): unchanged,
//    the charcoal + grain backdrop.
// Gated on Minecraft.level == null: in-game screens (pause, in-game options)
// keep vanilla's darkened-world backdrop.
//
// Pre-1.20 the backdrop itself is drawn ONCE per menu by the beforeRender screen
// event (OriginClientMod) -- before any screen content, so it clears the frame
// even for list screens (SelectWorld, multiplayer) that never call
// renderBackground. These hooks only CANCEL vanilla's dirt so it can't paint
// over that backdrop. Targets (javap-confirmed against the mapped 1.17.1
// Screen): renderBackground(PoseStack) HEAD -- 1.17.1 also has a 2-arg
// renderBackground(PoseStack,int) overload, so the descriptor is spelled out;
// vanilla screens call the 1-arg, which delegates -- and renderDirtBackground(int)
// HEAD (no PoseStack this era; the int is the texture-scroll offset).
// Fail-soft: the cancel asks the renderer's health (menuBackdropActive for
// Minecraft's screens, isActive for Origin's), so a failed backdrop gives the
// menus their vanilla dirt back. If these stop applying, vanilla's dirt draws
// over the panorama (the menus still work, just with the stock background).
// priority 2000: Origin's shared-screen background wins over other UI mods.
@Mixin(value = Screen.class, priority = 2000)
public class ScreenBackgroundMixin {

	@Inject(method = "renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanillaBackground(PoseStack poseStack, CallbackInfo ci) {
		if (originclient$originBackdrop()) {
			ci.cancel();
		}
	}

	// Some screens (GenericDirtMessageScreen -- the "Preparing..." transition on
	// world create/load, and any screen forcing a solid dirt backdrop) call
	// renderDirtBackground directly instead of through renderBackground, so the
	// hook above never fires for them. Suppress that dirt too; the beforeRender
	// event (or the loading scene) has already drawn the backdrop underneath.
	@Inject(method = "renderDirtBackground(I)V", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanillaDirt(int vOffset, CallbackInfo ci) {
		if (originclient$originBackdrop()) {
			ci.cancel();
		}
	}

	/** Out of world, Origin look on, and the backdrop this screen gets is healthy. */
	private boolean originclient$originBackdrop() {
		if (Minecraft.getInstance().level != null || OriginStyle.vanillaMenusOn(this)) {
			return false;
		}
		return OriginWidgetOwnership.isOriginOwnClass(this)
				? OriginScreenRenderer.isActive() : OriginScreenRenderer.menuBackdropActive();
	}

	// In-world Origin screens: vanilla's darkened-world backdrop stays, the
	// spotlight draws on top of it (currently a retired no-op, kept for the
	// pairing). Minecraft's own screens get no spotlight. Out of world this never
	// runs after a cancelled HEAD.
	@Inject(method = "renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("TAIL"))
	private void originclient$inWorldGlow(PoseStack poseStack, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginWidgetOwnership.isOriginOwnClass(this)) {
			return;
		}
		OriginScreenRenderer.renderTitleCursorGlow(new Gfx(poseStack), originclient$mouseX(), originclient$mouseY(), originclient$hoveringClickable());
	}

	// GUI-space cursor position (this era's renderBackground doesn't pass it in).
	private static int originclient$mouseX() {
		Minecraft mc = Minecraft.getInstance();
		return (int) (mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth());
	}

	private static int originclient$mouseY() {
		Minecraft mc = Minecraft.getInstance();
		return (int) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight());
	}

	// Bloom the spotlight while any visible widget is hovered.
	private boolean originclient$hoveringClickable() {
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible
					&& widget.isMouseOver(originclient$mouseX(), originclient$mouseY())) {
				return true;
			}
		}
		return false;
	}
}
