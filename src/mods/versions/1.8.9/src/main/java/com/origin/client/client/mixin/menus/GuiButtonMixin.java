package com.origin.client.client.mixin.menus;

import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.gui.OriginDarkWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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
// does vanilla's bookkeeping itself (the `hovered` test, verbatim) and calls the
// button's own mouseDragged between OriginButtonRenderer's two phases
// (beginVanillaButton draws the box, finishVanillaButton the label / slider),
// colour-masked for sliders Origin draws itself. No lambdas or inner classes in
// the mixin body (Mixin 0.7 keeps them out of the target class). Subclasses that override drawButton
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

	// Dark vanilla buttons (Will, 2026-10-26; OriginDarkWidgets): when vanilla draws the button (the
	// HEAD takeover above did not cancel), its bindTexture(widgets.png) is pointed at Origin's dark
	// page, so both box halves land on the dark rows. Knobs drawn in mouseDragged without a rebind
	// (GuiSlider, the Sounds sliders) use the same bind; knobs that rebind widgets.png
	// (GuiOptionSlider, OptiFine's) are caught by TextureManagerDarkWidgetsMixin while the window is
	// open. The label draw (the only call after mouseDragged, javap-verified) closes the window.
	// @ModifyArg: no CallbackInfo per frame, and it chains with other mods' ModifyArgs.
	// If the first stops applying the button stays vanilla grey. The second is the end marker: the
	// dark page is only used once it has run, and a window found still open latches the feature off,
	// so a missing end marker can never turn a later widgets.png bind (the hotbar) dark.
	// 1.8.9: bindTexture = func_110577_a, drawCenteredString = func_73732_a, owner GuiButton in both
	// the MCP and SRG bytecode (javap on the loom named + srg jars, 2026-10-26).
	@ModifyArg(method = "drawButton", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/TextureManager;bindTexture(Lnet/minecraft/util/ResourceLocation;)V"))
	private ResourceLocation originclient$darkPage(ResourceLocation texture) {
		return OriginDarkWidgets.beginButton(texture);
	}

	@ModifyArg(method = "drawButton", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiButton;drawCenteredString(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V"),
			index = 4)
	private int originclient$darkPageEnd(int color) {
		OriginDarkWidgets.endButton();
		return color;
	}
}
