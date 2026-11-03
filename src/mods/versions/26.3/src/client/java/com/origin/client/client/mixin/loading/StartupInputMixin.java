package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.StartupTransition;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The title takes the mouse while the startup loading screen dissolves into it
// (ported from 1.21.1). Target: MouseHandler's overlay checks (26.2+: Gui.overlay(),
// where 1.21.x and 26.1 call Minecraft.getOverlay()),
// which drop all mouse input while any overlay is up. The startup reveal
// (LoadingOverlayRevealMixin) keeps the overlay for its whole REVEAL_MS dissolve
// although the title is loaded and drawn beneath it, so the menu only answered
// ~0.6 s after it appeared (1.21.1 boot-time CI, 2026-10-19). During that
// dissolve, and only with the title screen open, these checks see no overlay.
// Keys already reach the screen (no overlay check there). Nothing drawn changes.
// Methods on this version (1.21.9+ input refactor): onButton (clicks; was onPress),
// onScroll, handleAccumulatedMovement (hover and drag).
// build-check fails on a method name the version lacks, so each module lists only its own.
// defaultRequire 0 (loading config, required:false): if a target moves, the menu
// answers after the dissolve again, as before.
@Mixin(MouseHandler.class)
public abstract class StartupInputMixin {
	@WrapOperation(method = {"onButton", "onScroll", "handleAccumulatedMovement"},
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;"))
	private Overlay originclient$titleInputThroughReveal(Gui gui, Operation<Overlay> original) {
		Overlay overlay = original.call(gui);
		if (overlay instanceof LoadingOverlay && gui.screen() instanceof TitleScreen
				&& StartupTransition.startupRevealInProgress()) {
			return null;
		}
		return overlay;
	}
}
