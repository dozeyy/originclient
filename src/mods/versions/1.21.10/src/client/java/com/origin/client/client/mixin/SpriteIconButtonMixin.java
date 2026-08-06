package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.SpriteIconButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin skin for the sprite-icon buttons — the Accessibility (person) and
// Language (globe) buttons the 1.21.1 baseline keeps on the title screen.
//
// Era facts for THIS module, read off its own jar (an assumption cost a pass
// here: the renderContents split does NOT happen at 1.21.6, it happens later):
//   * SpriteIconButton.CenteredIcon overrides renderWidget, so that is the target
//   * the `sprite` field is a WidgetSprites (resolve with .get(active, hovered))
//   * blitSprite takes a RenderPipeline as its first argument
// priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = {SpriteIconButton.CenteredIcon.class, SpriteIconButton.TextAndIcon.class}, priority = 2000)
public abstract class SpriteIconButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originIcon(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		SpriteIconButton self = (SpriteIconButton) (Object) this;
		SpriteIconButtonAccessor acc = (SpriteIconButtonAccessor) (Object) this;
		int iw = acc.originclient$spriteWidth();
		int ih = acc.originclient$spriteHeight();
		int ix = self.getX() + self.getWidth() / 2 - iw / 2;
		int iy = self.getY() + self.getHeight() / 2 - ih / 2;
		// Only cancel vanilla when Origin actually drew (fail-soft contract).
		if (OriginButtonRenderer.renderIconButton(guiGraphics, self, iw, ih,
				() -> guiGraphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
						acc.originclient$sprite().get(self.active, self.isHoveredOrFocused()), ix, iy, iw, ih))) {
			ci.cancel();
		}
	}
}
