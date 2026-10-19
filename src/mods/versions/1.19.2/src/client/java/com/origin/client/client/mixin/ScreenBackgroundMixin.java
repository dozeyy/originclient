package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
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

// Origin background (charcoal + rings + grain) behind every out-of-world
// menu -- options, world select, server list, create world, etc. -- so the
// whole menu tree reads as one interface with the main menu. The website's
// mouse-follow spotlight is drawn on EVERY menu (Will), right after the
// backdrop so it sits over the background but under the widgets that render
// afterward.
//
// Background replacement is gated on Minecraft.level == null: in-game screens
// (pause, in-game options) keep vanilla's blurred-world backdrop -- replacing
// that would hide the world the player is standing in. The cursor glow is NOT
// gated: out-of-world it draws inside the cancel path, in-world via the TAIL
// hook over vanilla's blur (TAIL never runs when HEAD cancelled, so the two
// paths are exclusive and the glow draws exactly once either way).
//
// One hook (javap-confirmed against the mapped 1.19.2 Screen):
//  - renderBackground(PoseStack): the screen-level backdrop (panorama + blur
//    + menu texture). Cancelled and replaced wholesale. TitleScreen overrides
//    this method, so its own mixin path (which already draws the glow) is
//    unaffected -- no double glow there. 1.19.2 also has a 2-arg
//    renderBackground(PoseStack,int) overload, so every method reference
//    below carries its full descriptor -- a bare "renderBackground" would be
//    ambiguous. (The 1-arg delegates to the 2-arg; vanilla screens call the
//    1-arg, so hooking it covers the tree like it did on 1.19.4.)
// 1.19.2 has no mouse/partialTick params on renderBackground and no static
// renderMenuBackgroundTexture helper (that arrived later), so the cursor
// position is computed from the mouse handler and the list-strip suppression
// is dropped -- option lists sit acceptably on the Origin background.
// priority 2000: Origin's shared-screen background wins over other UI mods.
@Mixin(value = Screen.class, priority = 2000)
public class ScreenBackgroundMixin {

	// The Origin backdrop itself is drawn ONCE per menu by the beforeRender screen
	// event (OriginClientMod) -- before any screen content, so it clears the frame
	// even for list screens (SelectWorld, multiplayer) that never call
	// renderBackground and would otherwise show the previous screen bleeding
	// through. These two hooks only CANCEL vanilla's dirt so it can't paint over
	// that backdrop. Fail-soft: if Origin rendering is unhealthy (isActive false),
	// nothing is cancelled and vanilla's backdrop returns.
	@Inject(method = "renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanillaBackground(PoseStack poseStack, CallbackInfo ci) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()
				&& !OriginStyle.vanillaMenusOn(this)) {
			ci.cancel();
		}
	}

	// Some screens (GenericDirtMessageScreen -- the "Preparing..." transition on
	// world create/load, and any screen forcing a solid dirt backdrop) call
	// renderDirtBackground directly instead of through renderBackground, so the
	// hook above never fires for them. Suppress that dirt too; the beforeRender
	// event has already drawn the Origin backdrop underneath.
	// 1.19.2: renderDirtBackground(int) -- no PoseStack (it tessellates
	// directly); the int is vanilla's texture-scroll offset.
	@Inject(method = "renderDirtBackground(I)V", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanillaDirt(int vOffset, CallbackInfo ci) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()
				&& !OriginStyle.vanillaMenusOn(this)) {
			ci.cancel();
		}
	}

	// In-world menus (pause, in-game options) keep vanilla's blurred-world
	// backdrop -- the beforeRender event skips them (level != null) -- so the
	// Origin cursor spotlight is drawn here to stay consistent with out-of-world
	// menus. Out-of-world this never runs: the HEAD hook cancels renderBackground
	// before TAIL is reached.
	@Inject(method = "renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("TAIL"))
	private void originclient$inWorldGlow(PoseStack poseStack, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock backdrop, no Origin glow
		}
		OriginScreenRenderer.renderTitleCursorGlow(new Gfx(poseStack), originclient$mouseX(), originclient$mouseY(), originclient$hoveringClickable());
	}

	// GUI-space cursor position (1.20's renderBackground doesn't pass it in).
	private static int originclient$mouseX() {
		Minecraft mc = Minecraft.getInstance();
		return (int) (mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth());
	}

	private static int originclient$mouseY() {
		Minecraft mc = Minecraft.getInstance();
		return (int) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight());
	}

	// Same hover test the title screen uses: bloom the spotlight while any
	// visible widget is hovered, matching the website's hover targets.
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
