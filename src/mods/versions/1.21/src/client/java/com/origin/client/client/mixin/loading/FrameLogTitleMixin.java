package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only (OriginFrameLog, -Dorigin.frameLog=true): times the title screen's
// own render, so the log can say which frames were its first (where textures,
// shaders and glyphs are paid for the first time). Target: TitleScreen.render
// HEAD and RETURN; one static-final check per call when the logger is off.
@Mixin(TitleScreen.class)
public abstract class FrameLogTitleMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$titleBegin(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.titleBegin();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void originclient$titleEnd(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.titleEnd();
	}
}
