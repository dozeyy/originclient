package com.origin.client.client.mixin;

import com.origin.client.client.ext.ChatTextState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Chat mod — "Text Shadow in Chat" on the render-state era.
 *
 * Every piece of GUI text ends up as a GuiTextRenderState built here, with its
 * dropShadow argument hard-coded true. There is no per-call shadow flag on the
 * chat path any more (see ChatTextState), so we flip that one argument while
 * ChatComponent.render is on the stack and leave all other text alone.
 *
 * index 7 = the dropShadow parameter of
 * GuiTextRenderState(Font, FormattedCharSequence, Matrix3x2fc, int x, int y,
 *                    int color, int backgroundColor, boolean dropShadow,
 *                    boolean includeEmpty, ScreenRectangle scissor).
 */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphics$RenderingTextCollector")
public class ChatTextShadowMixin {

	@ModifyArg(method = "accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;"
			+ "Lnet/minecraft/util/FormattedCharSequence;)V",
			// @At("NEW") points at the NEW opcode, which @ModifyArg cannot bind to
			// ("targetting a non-method insn" — it applied to nothing and the option
			// silently did nothing). Target the constructor INVOKE instead.
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/state/GuiTextRenderState;<init>("
					+ "Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;"
					+ "Lorg/joml/Matrix3x2fc;IIIIZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V"),
			index = 7, require = 1)
	private boolean originclient$chatShadow(boolean dropShadow) {
		return dropShadow && !ChatTextState.suppressShadow;
	}
}
