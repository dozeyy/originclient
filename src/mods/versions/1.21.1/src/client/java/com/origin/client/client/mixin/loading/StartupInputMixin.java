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

// The title takes the mouse while the startup loading screen dissolves into it.
// Target: MouseHandler's Minecraft.getOverlay() checks in onPress (clicks),
// onScroll and handleAccumulatedMovement (hover/drag), which drop all mouse input
// while any overlay is up. The startup reveal (LoadingOverlayRevealMixin) keeps
// the overlay for its whole REVEAL_MS dissolve although the title is loaded and
// drawn beneath it, so the menu only answered ~0.6 s after it appeared (boot-time
// CI, 2026-10-19). During that dissolve, and only with the title screen open,
// these checks see no overlay. Keys already reach the screen (keyPress has no
// overlay check). Nothing drawn changes. If the target moves, the menu simply
// answers after the dissolve again (required:false loading config).
@Mixin(MouseHandler.class)
public abstract class StartupInputMixin {
	// require = 3: one call site per method at least, so a moved target shows up as
	// an injection error in the log instead of passing silently.
	@WrapOperation(method = {"onPress", "onScroll", "handleAccumulatedMovement"}, require = 3, at = @At(value = "INVOKE",
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
