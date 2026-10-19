package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only (OriginFrameLog, -Dorigin.frameLog=true): times the title screen's
// own extract pass, so the log can say which frames were its first. 26.x: the
// screen only EXTRACTS here (the GPU draw is GameRenderer.render, timed as
// "render"). Target: TitleScreen.extractRenderState HEAD and RETURN; one
// static-final check per call when the logger is off.
@Mixin(TitleScreen.class)
public abstract class FrameLogTitleMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void originclient$titleBegin(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.titleBegin();
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void originclient$titleEnd(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.titleEnd();
	}
}
