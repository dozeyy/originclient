package com.origin.client.client.mixin.loading;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.OriginFrameLog;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only (OriginFrameLog, -Dorigin.frameLog=true): times the two parts of the
// loading overlay's completion frame (the frame the reload finishes): onFinish
// (the startup's "game loaded" step, which builds the title screen) and the
// screen's init right after. Target: LoadingOverlay.render(PoseStack, int, int,
// float) (pre-1.20: no GuiGraphics), its Consumer.accept (ordinal 0, the success
// path) and Screen.init(Minecraft, int, int) calls, javap-verified on this
// module's mapped jar. Off = one static-final check.
@Mixin(LoadingOverlay.class)
public abstract class FrameLogOverlayMixin {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0))
	private void originclient$finishBegin(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.sectionBegin();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0, shift = At.Shift.AFTER))
	private void originclient$finishEnd(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.sectionEnd("onFinish");
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;init(Lnet/minecraft/client/Minecraft;II)V"))
	private void originclient$initBegin(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.sectionBegin();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;init(Lnet/minecraft/client/Minecraft;II)V", shift = At.Shift.AFTER))
	private void originclient$initEnd(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.sectionEnd("screen.init");
	}
}
