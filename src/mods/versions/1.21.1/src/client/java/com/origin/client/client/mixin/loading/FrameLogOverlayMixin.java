package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only (OriginFrameLog, -Dorigin.frameLog=true): times the two parts of the
// loading overlay's completion frame (the frame the reload finishes): onFinish
// (the startup's "game loaded" step, which builds the title screen) and the
// screen's init right after. Target: LoadingOverlay.render, the Consumer.accept
// (ordinal 0, the success path) and Screen.init calls. Off = one static-final check.
//
// B-30 (the overlay's first frames, 931 ms on the first one in CI): the slices of
// vanilla's own draw, listed on the "early frame" lines (OriginFrameLog.part):
// ov:clear (HEAD to the first Mojang-logo blit: the brand-colour clear), ov:logo
// (the two logo blits, to RenderSystem.defaultBlendFunc), ov:bar (drawProgressBar).
// Targets from javap of the 1.21.1 LoadingOverlay.render: GuiGraphics.blit
// (ResourceLocation, 4 ints, 2 floats, 4 ints) ordinal 0, RenderSystem.defaultBlendFunc,
// drawProgressBar. If one moves only that slice goes missing from the dev log.
@Mixin(LoadingOverlay.class)
public abstract class FrameLogOverlayMixin {
	@Unique
	private static long originclient$sliceAt = 0L;

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$sliceHead(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		originclient$sliceAt = OriginFrameLog.partStart();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", ordinal = 0,
			target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIFFIIII)V"))
	private void originclient$sliceLogo(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.part("ov:clear", originclient$sliceAt);
		originclient$sliceAt = OriginFrameLog.partStart();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;defaultBlendFunc()V"))
	private void originclient$sliceLogoEnd(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.part("ov:logo", originclient$sliceAt);
		originclient$sliceAt = 0L;
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphics;IIIIF)V"))
	private void originclient$sliceBar(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		originclient$sliceAt = OriginFrameLog.partStart();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphics;IIIIF)V"))
	private void originclient$sliceBarEnd(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.part("ov:bar", originclient$sliceAt);
		originclient$sliceAt = 0L;
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0))
	private void originclient$finishBegin(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.sectionBegin();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0, shift = At.Shift.AFTER))
	private void originclient$finishEnd(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.sectionEnd("onFinish");
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;init(Lnet/minecraft/client/Minecraft;II)V"))
	private void originclient$initBegin(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.sectionBegin();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;init(Lnet/minecraft/client/Minecraft;II)V", shift = At.Shift.AFTER))
	private void originclient$initEnd(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginFrameLog.sectionEnd("screen.init");
	}
}
