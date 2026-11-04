package com.origin.client.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// The mouse pointer a vanilla widget asks for (1.21.9+): AbstractWidget.handleCursor
// requests a hand over an active widget, "not allowed" over an inactive one, and the
// slider's override the left-right arrows while it is dragged (javap 26.2 / 26.3:
// protected void handleCursor(GuiGraphicsExtractor), overridden by AbstractSliderButton
// and EditBox). Vanilla calls it from the widget's own extractWidgetRenderState, which
// AbstractButtonMixin, AbstractSliderButtonMixin and TabButtonMixin cancel when Origin
// draws the widget, so they call it back through this invoker (a virtual call: the
// slider's override still wins). If this stops applying, the cast in those mixins
// throws inside their try and the pointer just stays the arrow there.
@Mixin(AbstractWidget.class)
public interface AbstractWidgetCursorInvoker {
	@Invoker("handleCursor")
	void originclient$handleCursor(GuiGraphicsExtractor graphics);
}
