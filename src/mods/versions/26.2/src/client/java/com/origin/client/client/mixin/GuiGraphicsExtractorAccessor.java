package com.origin.client.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 26.2 made GuiGraphicsExtractor.guiRenderState private (it was reachable on
// 1.21.x). Color Saturation needs it to submit its full-screen grade quad as a
// GuiElementRenderState, so this accessor exposes the field. Read-only; no
// behaviour change.
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
	@Accessor("guiRenderState")
	GuiRenderState originclient$guiRenderState();

	// Also private on 26.2: OriginSdfFont needs the active scissor rect to compute
	// each text element's bounds (a null bounds is culled as "zero visible area").
	@Accessor("scissorStack")
	GuiGraphicsExtractor.ScissorStack originclient$scissorStack();
}
