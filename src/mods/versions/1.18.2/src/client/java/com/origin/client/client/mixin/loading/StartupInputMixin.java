package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.StartupTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The title takes the mouse while the startup loading screen dissolves into it
// (ported from 1.21.1). Target: MouseHandler's Minecraft.getOverlay() checks,
// which drop all mouse input while any overlay is up. The startup reveal
// (LoadingOverlayRevealMixin) keeps the overlay for its whole REVEAL_MS dissolve
// although the title is loaded and drawn beneath it, so the menu only answered
// ~0.6 s after it appeared (1.21.1 boot-time CI, 2026-10-19). During that
// dissolve, and only with the title screen open, these checks see no overlay.
// Keys already reach the screen (no overlay check there). Nothing drawn changes.
// The method names cover every era this module family has used: onPress (clicks,
// up to 1.21.8) / onButton (clicks, 1.21.9+ input refactor), onScroll, and onMove
// / handleAccumulatedMovement (hover and drag); names absent in this version simply
// match nothing. defaultRequire 0 (loading config, required:false): if the target
// moves, the menu answers after the dissolve again, as before.
@Mixin(MouseHandler.class)
public abstract class StartupInputMixin {
	@WrapOperation(method = {"onPress", "onButton", "onScroll", "onMove", "handleAccumulatedMovement"},
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/Minecraft;getOverlay()Lnet/minecraft/client/gui/screens/Overlay;"))
	private Overlay originclient$titleInputThroughReveal(Minecraft mc, Operation<Overlay> original) {
		Overlay overlay = original.call(mc);
		if (overlay instanceof LoadingOverlay && mc.screen instanceof TitleScreen
				&& StartupTransition.startupRevealInProgress()) {
			return null;
		}
		return overlay;
	}
}
