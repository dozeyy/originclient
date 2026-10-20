package com.origin.client.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Target: GuiGraphicsExtractor's private `guiRenderState` (javap-verified on
// 26.1.2, 26.2 and 26.3). Why: the menu backdrop blurs at most once per frame
// (OriginScreenRenderer.blurOnce reads GuiRenderState's blur marker through
// this). If it stops applying: blurOnce falls back to catching vanilla's
// "Can only blur once per frame" throw, so the backdrop still draws.
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
	@Accessor("guiRenderState")
	GuiRenderState originclient$guiRenderState();
}
