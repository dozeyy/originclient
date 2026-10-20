package com.origin.client.client.mixin.menus;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Option / world / server / resource-pack / language lists tile their OWN dirt
// background -- behind the rows and as top/bottom strips over the title/button
// areas. Together those cover the whole screen, painting over the Origin
// backdrop, so the list should sit transparently on it instead (1.16.5's
// AbstractSelectionListMixin flips the list's renderBackground /
// renderTopAndBottom flags for the same reason).
//
// 1.8.9: GuiSlot has no such flags -- drawScreen calls drawContainerBackground
// (the row backdrop; a FORGE-added method, so its name is never obfuscated:
// remap = false) and overlayBackground(top...) / overlayBackground(bottom...)
// (the strips; vanilla, remapped). Both are cancelled at HEAD. Like 1.16.5 this
// runs in-world too: there the screen's own drawDefaultBackground (vanilla's
// darkened world, or Menu Blur) shows through, instead of dirt over the game.
// The list's 4 px edge fades, selection boxes and scrollbar still draw; out of a
// world the row area gets the clean look's CLEAN_PANEL fill instead of dirt.
// If either inject stops applying, that part of the list draws vanilla dirt.
// Fail-soft: gated only on isActive() -- a broken Origin renderer leaves
// vanilla's dirt untouched.
@Mixin(GuiSlot.class)
public abstract class GuiSlotMixin {

	@Inject(method = "drawContainerBackground", at = @At("HEAD"), cancellable = true, remap = false)
	private void originclient$transparentRows(CallbackInfo ci) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
		if (OriginScreenRenderer.isActive() && !OriginStyle.vanillaMenusOn(mc.currentScreen)) {
			// The clean look (1.21.1's ScreenBackgroundMixin, 2026-10-19): out of a
			// world, a Minecraft menu's list body is a translucent dark panel over
			// the panorama; Origin's own windows and in-world lists stay clear.
			if (mc.theWorld == null && mc.currentScreen != null
					&& !com.origin.client.client.gui.OriginWidgetOwnership.isOriginOwnClass(mc.currentScreen)) {
				GuiSlot self = (GuiSlot) (Object) this;
				net.minecraft.client.gui.Gui.drawRect(self.left, self.top, self.right, self.bottom,
						com.origin.client.client.theme.OriginTheme.CLEAN_PANEL);
			}
			ci.cancel();
		}
	}

	@Inject(method = "overlayBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$transparentStrips(int startY, int endY, int startAlpha, int endAlpha, CallbackInfo ci) {
		if (originclient$clear()) {
			ci.cancel();
		}
	}

	// The dirt strips cancelled above are also what MASKED rows scrolled past the
	// list's top and bottom (vanilla draws every row, then paints the strips over
	// the overflow). Without them a long list (Controls) spills its rows over the
	// title and the Done button. So the rows are clipped to the list body instead,
	// as later versions' lists do: a GL scissor around drawSelectionBox (the row
	// pass in GuiSlot.drawScreen; vanilla, remapped) and the list header (below),
	// on whenever the strips are off. If these stop applying, rows overflow as above (cosmetic, no crash).
	// Skipped if a scissor is already on (an enclosing screen clips itself).
	@Unique
	private boolean originclient$scissored;

	@Inject(method = "drawSelectionBox", at = @At("HEAD"))
	private void originclient$clipRowsOn(int insideLeft, int insideTop, int mouseX, int mouseY, CallbackInfo ci) {
		originclient$clipOn();
	}

	@Inject(method = "drawSelectionBox", at = @At("RETURN"))
	private void originclient$clipRowsOff(int insideLeft, int insideTop, int mouseX, int mouseY, CallbackInfo ci) {
		originclient$clipOff();
	}

	// GuiSlot.drawScreen draws the list HEADER (drawListHeader; the resource-pack
	// lists "Available" / "Selected") just before the rows, and it scrolls with
	// them, so it needs the same clip or it spills over the screen title once the
	// strips are off. Wrapped around the call site in drawScreen, not injected
	// into drawListHeader itself: the pack lists OVERRIDE that method, so an
	// inject into GuiSlot's own (empty) body would never run for them. If these
	// stop applying, the header can overflow the title (cosmetic, no crash).
	@Inject(method = "drawScreen", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiSlot;drawListHeader(IILnet/minecraft/client/renderer/Tessellator;)V"))
	private void originclient$clipHeaderOn(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		originclient$clipOn();
	}

	@Inject(method = "drawScreen", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/minecraft/client/gui/GuiSlot;drawListHeader(IILnet/minecraft/client/renderer/Tessellator;)V"))
	private void originclient$clipHeaderOff(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		originclient$clipOff();
	}

	// Backstop: whatever happened mid-draw (an exception in a subclass's row or
	// header code skips the paired "off"), the scissor never outlives the list.
	@Inject(method = "drawScreen", at = @At("RETURN"))
	private void originclient$clipBackstop(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		originclient$clipOff();
	}

	@Unique
	private void originclient$clipOn() {
		originclient$scissored = false;
		if (!originclient$clear() || GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
			return;
		}
		GuiSlot self = (GuiSlot) (Object) this;
		int gs = OriginScreenRenderer.scale();
		int displayH = net.minecraft.client.Minecraft.getMinecraft().displayHeight;
		int h = Math.max(0, self.bottom - self.top) * gs;
		// GL counts y from the bottom of the window.
		GL11.glScissor(self.left * gs, displayH - self.bottom * gs, Math.max(0, self.right - self.left) * gs, h);
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		originclient$scissored = true;
	}

	@Unique
	private void originclient$clipOff() {
		if (originclient$scissored) {
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
			originclient$scissored = false;
		}
	}

	/** The list sits clear on the Origin backdrop (strips off, rows clipped). */
	@Unique
	private static boolean originclient$clear() {
		return OriginScreenRenderer.isActive()
				&& !OriginStyle.vanillaMenusOn(net.minecraft.client.Minecraft.getMinecraft().currentScreen);
	}
}
