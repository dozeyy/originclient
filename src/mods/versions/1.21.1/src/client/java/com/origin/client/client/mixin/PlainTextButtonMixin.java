package com.origin.client.client.mixin;

import com.origin.client.client.gui.MenuText;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: PlainTextButton.renderWidget(GuiGraphics,int,int,float) HEAD (javap,
// mapped 1.21.1: public, draws its OWN private final message/underlinedMessage
// copies, so setMessage() never changes what it shows).
// Why: on the clean title screen the copyright line (the only PlainTextButton
// vanilla puts there) drew in white pixel font, loud and, at GUI scale 4, wide
// enough to run into the icon row (CI shot title_1920x1080_s4, 2026-10-19).
// Here it draws as small muted Inter instead; hover brightens it. Click target
// and the credits screen it opens are vanilla's.
// If this stops applying: the copyright line is vanilla's white pixel text.
@Mixin(PlainTextButton.class)
public abstract class PlainTextButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$cleanCopyright(GuiGraphics g, int mouseX, int mouseY, float partialTick,
											  CallbackInfo ci) {
		try {
			if (!MenuText.inScope() || !(Minecraft.getInstance().screen instanceof TitleScreen)) {
				return;
			}
			PlainTextButton self = (PlainTextButton) (Object) this;
			String text = self.getMessage().getString();
			if (!MenuText.usable(text)) {
				return;
			}
			int color = self.isHoveredOrFocused() ? OriginTheme.CLEAN_TEXT : OriginTheme.CLEAN_TEXT_MUTED;
			MenuText.drawMid(g, text, self.getX(), self.getY() + self.getHeight() / 2.0,
					OriginTheme.CLEAN_EM_HINT, 0f, color);
			ci.cancel();
		} catch (Throwable ignored) {
			// fall through to vanilla's own drawing
		}
	}
}
