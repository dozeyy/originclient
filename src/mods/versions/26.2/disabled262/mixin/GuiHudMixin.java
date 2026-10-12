package com.origin.client.client.mixin;

import com.origin.client.client.hud.HudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin's HUD (FPS/coords/keystrokes/etc.) as the TOP-MOST layer, so it draws
// over any third-party mod's HUD rather than fighting for z-order.
//
// This replaces the old HudRenderCallback registration in OriginClientMod:
// Fabric dispatches HudRenderCallback from inside Gui.render, and its firing
// order among mods is load-order-dependent — uncontrollable. Injecting at
// Gui.render RETURN with a high injector `order` puts Origin's draw after the
// whole vanilla HUD *and* after Fabric's callback dispatch (where every other
// mod paints), so Origin lands last = on top. (The LayeredDraw HUD API that
// would let a layer register "after everything" doesn't exist in 1.21.1's
// fabric-api — it's 1.21.4+ — so the mixin is the right lever here.)
//
// order = 2000 (default is 1000) beats other mods' default-order RETURN
// injectors. Honest limit: a mod that itself injects at RETURN with a still
// higher order could draw over us — that's the ceiling of what mixins can
// guarantee.
@Mixin(value = Gui.class, priority = 2000)
public class GuiHudMixin {

	@Inject(method = "render", at = @At("RETURN"), order = 2000)
	private void originclient$topHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		HudElements.renderAll(guiGraphics);
	}
}
