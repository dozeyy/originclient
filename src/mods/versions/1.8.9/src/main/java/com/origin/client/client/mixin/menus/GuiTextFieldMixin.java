package com.origin.client.client.mixin.menus;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The Origin field well for every GuiTextField (world name / seed, server
// name / address, sign-less text inputs), in place of vanilla's grey frame +
// black box. Text, caret, selection and input are vanilla's, untouched.
//
// 1.8.9: drawTextBox draws its two background rects inline, then places the
// text at (x + 4, y + (h - 8) / 2) ONLY while enableBackgroundDrawing is on --
// with it off the text sits at (x, y) and getWidth() grows by 8. Rather than
// redirect the inline drawRect calls (their owner in Mojang's bytecode is not
// guaranteed), HEAD draws the Origin well, then turns the background off and
// shifts the box by exactly the inset vanilla would have added, so vanilla's
// own text code lands on the same pixels; RETURN puts every field back. Both
// injections are HEAD/RETURN (GuiTextField is not OptiFine-replaced anyway).
// Gated on enableBackgroundDrawing: fields that draw no background (chat input,
// the book) are untouched.
@Mixin(GuiTextField.class)
public abstract class GuiTextFieldMixin {
	@Shadow
	public int xPosition;
	@Shadow
	public int yPosition;
	@Shadow
	public int width;
	@Shadow
	public int height;
	@Shadow
	private boolean enableBackgroundDrawing;
	@Shadow
	private boolean isFocused;
	@Shadow
	private boolean isEnabled;
	@Shadow
	private boolean visible;

	@Unique
	private boolean originclient$shifted;
	@Unique
	private int originclient$x, originclient$y, originclient$w;

	@Inject(method = "drawTextBox", at = @At("HEAD"))
	private void originclient$originWell(CallbackInfo ci) {
		originclient$shifted = false;
		try {
			if (!visible || !enableBackgroundDrawing) {
				return;
			}
			// Origin's well inside Origin's own windows; everywhere else 1.21.1's dark field
			// (OriginDarkWidgets, 2026-10-26). Neither drew -> vanilla's grey frame.
			if (!OriginButtonRenderer.renderTextField(xPosition - 1, yPosition - 1, width + 2, height + 2,
					isFocused, isEnabled)
					&& !com.origin.client.client.gui.OriginDarkWidgets.textField(xPosition, yPosition, width, height,
					isFocused)) {
				return;
			}
			originclient$x = xPosition;
			originclient$y = yPosition;
			originclient$w = width;
			xPosition += 4;
			yPosition += (height - 8) / 2;
			width -= 8;
			enableBackgroundDrawing = false;
			originclient$shifted = true;
		} catch (Throwable t) {
			// vanilla draws its own background
		}
	}

	@Inject(method = "drawTextBox", at = @At("RETURN"))
	private void originclient$restore(CallbackInfo ci) {
		if (originclient$shifted) {
			originclient$shifted = false;
			xPosition = originclient$x;
			yPosition = originclient$y;
			width = originclient$w;
			enableBackgroundDrawing = true;
		}
	}
}
