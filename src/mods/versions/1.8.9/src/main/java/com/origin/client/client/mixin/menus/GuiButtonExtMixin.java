package com.origin.client.client.mixin.menus;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The Origin skin for Forge's own buttons: GuiButtonExt (Forge's config GUIs,
// the Mods list's buttons) and its subclass GuiSlider. GuiButtonExt overrides
// drawButton WITHOUT calling super (it draws a stretched widgets.png box via
// GuiUtils), so GuiButtonMixin never sees these. Same takeover: vanilla's hover
// test, then OriginButtonRenderer runs the button's own mouseDragged (Forge's
// GuiSlider applies its drag there) at the right moment.
//
// 1.8.9: Forge classes are not obfuscated, but this method OVERRIDES a vanilla
// one, so in production (SRG names) it is called func_146112_a, not drawButton.
// remap = false + both names binds it in dev (MCP) and production (SRG) without
// relying on the annotation processor to map an inherited member.
// SRG NEEDED: confirm GuiButton.drawButton = func_146112_a (1.8.9 NAME UNVERIFIED);
// a wrong name only means Forge's buttons keep their vanilla look in production.
// The mixin extends GuiButton so `hovered` / `mouseDragged` are reachable as
// inherited members (no @Shadow across the hierarchy).
// priority 2000: Origin's widget restyle wins over other UI mods.
@Mixin(value = GuiButtonExt.class, priority = 2000, remap = false)
public abstract class GuiButtonExtMixin extends GuiButton {

	private GuiButtonExtMixin() {
		super(0, 0, 0, "");
	}

	@Inject(method = {"drawButton", "func_146112_a"}, at = @At("HEAD"), cancellable = true, remap = false)
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
			// Forge draws the button
		}
	}
}
