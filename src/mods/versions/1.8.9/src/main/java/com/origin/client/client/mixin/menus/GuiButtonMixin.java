package com.origin.client.client.mixin.menus;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Restyles every button in the Origin look, on every screen: cancels the vanilla
// button drawing and draws the Origin style instead. Restyling happens in place
// (no widgets added/removed) -- buttons keep their positions, actions and clicks.
//
// 1.8.9: GuiButton.drawButton(mc, mx, my) is the one draw path for plain buttons
// AND sliders (GuiOptionSlider, OptiFine's GuiOptionSliderOF, the Sounds screen's
// sliders, vanilla GuiSlider): it sets `hovered`, draws the box, then calls
// mouseDragged(mc, mx, my) -- where a slider APPLIES the drag and draws its knob
// -- then the label. HEAD-cancelling it would freeze every slider, so this hook
// does vanilla's bookkeeping itself (the `hovered` test, verbatim) and hands
// OriginButtonRenderer a callback that runs the button's own mouseDragged at the
// right moment (OriginButtonRenderer.beginVanillaButton / finishVanillaButton). Subclasses that override drawButton
// without calling super (GuiButtonLanguage, GuiLockIconButton, book page arrows,
// merchant/beacon buttons, other mods' art buttons) never reach this and keep
// their own look; Forge's GuiButtonExt (config GUIs, Forge's GuiSlider) has its
// own hook, GuiButtonExtMixin. GuiButton is not an OptiFine-replaced class.
// priority 2000: Origin's widget restyle wins over other UI mods.
@Mixin(value = GuiButton.class, priority = 2000)
public abstract class GuiButtonMixin {

	@Shadow
	protected boolean hovered;

	@Shadow
	protected abstract void mouseDragged(Minecraft mc, int mouseX, int mouseY);

	@Inject(method = "drawButton", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(Minecraft mc, int mouseX, int mouseY, CallbackInfo ci) {
		try {
			GuiButton self = (GuiButton) (Object) this;
			if (!self.visible || !OriginButtonRenderer.skins(self)) {
				return;
			}
			// Vanilla's hover test, so isMouseOver() and the hover ease see this frame.
			this.hovered = mouseX >= self.xPosition && mouseY >= self.yPosition
					&& mouseX < self.xPosition + self.width && mouseY < self.yPosition + self.height;
			int mode = OriginButtonRenderer.beginVanillaButton(self);
			if (mode == OriginButtonRenderer.SKIP) {
				return;
			}
			boolean masked = mode == OriginButtonRenderer.SLIDER;
			if (masked) {
				GlStateManager.colorMask(false, false, false, false);
			}
			try {
				this.mouseDragged(mc, mouseX, mouseY);
			} finally {
				if (masked) {
					GlStateManager.colorMask(true, true, true, true);
				}
			}
			// Only cancel vanilla when Origin actually drew (fail-soft contract).
			if (OriginButtonRenderer.finishVanillaButton(self, mode)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// vanilla draws the button
		}
	}
}
