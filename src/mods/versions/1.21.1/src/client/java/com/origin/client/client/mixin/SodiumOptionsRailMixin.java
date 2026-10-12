package com.origin.client.client.mixin;

import com.origin.client.client.gui.SodiumModRail;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Video Settings MOD RAIL (1.21.1 / Sodium 0.6.13 only -- see SodiumModRail for
// what it shows and why this version needs a GUI mixin instead of Sodium's
// Config API). Three hooks into Sodium's video settings screen, every target
// checked against Sodium's source at tag mc1.21.1-0.6.13
// (common/.../client/gui/SodiumOptionsGUI.java):
//
//   rebuildGUI()        = clearWidgets, rebuildGUIPages, rebuildGUIOptions, then
//                         the Undo/Apply/Done + Donate buttons. Runs on init(),
//                         resize and every setPage().
//   rebuildGUIPages()   = Sodium's top row of page tabs. HEAD: if the rail can
//                         be built, add it and skip the tabs; otherwise the tabs
//                         run untouched.
//   rebuildGUIOptions() = one Control.createElement(new Dim2i(6, y, 240, 18)) per
//                         option. ModifyArg slides that Dim2i right of the rail,
//                         ONLY while the rail is up.
//   rebuildGUI() TAIL   = self-check: the rail is up but no option was moved
//                         (the shift injector didn't bind) -> the rail would
//                         cover the options, so turn it off and rebuild with
//                         Sodium's own tabs.
//
// Fail-soft: @Pseudo + string target (no Sodium -> nothing applies), remap =
// false (Sodium isn't Minecraft-mapped), require = 0 on every injector. Any
// mismatch leaves Sodium's own, fully working screen.
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI", remap = false)
public abstract class SodiumOptionsRailMixin extends Screen {
	// Whether THIS rebuild replaced the tabs with the rail, and whether the
	// option column was actually shifted for it. Reset on every rebuild.
	@Unique
	private boolean originclient$railUp;
	@Unique
	private boolean originclient$shifted;

	protected SodiumOptionsRailMixin(Component title) {
		super(title);
	}

	@Inject(method = "rebuildGUIPages()V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void originclient$replaceTabsWithRail(CallbackInfo ci) {
		originclient$railUp = false;
		originclient$shifted = false;
		try {
			SodiumModRail rail = SodiumModRail.tryCreate(this);
			if (rail != null) {
				this.addRenderableWidget(rail);
				originclient$railUp = true;
				ci.cancel();
			}
		} catch (Throwable ignored) {
			// rail off for this rebuild; Sodium's own tabs run
		}
	}

	@ModifyArg(method = "rebuildGUIOptions()V",
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/client/gui/options/control/Control;createElement(Lnet/caffeinemc/mods/sodium/client/util/Dim2i;)Lnet/caffeinemc/mods/sodium/client/gui/options/control/ControlElement;",
					remap = false),
			index = 0, remap = false, require = 0)
	private Dim2i originclient$shiftOptionsRight(Dim2i dim) {
		if (!originclient$railUp) {
			return dim;
		}
		originclient$shifted = true;
		return new Dim2i(dim.x() + SodiumModRail.contentShift(this.width), dim.y(), dim.width(), dim.height());
	}

	@Inject(method = "rebuildGUI()V", at = @At("TAIL"), remap = false, require = 0)
	private void originclient$checkRailFits(CallbackInfo ci) {
		if (originclient$railUp && !originclient$shifted && SodiumModRail.currentPageHasOptions(this)) {
			originclient$railUp = false;
			SodiumModRail.fallBackToSodiumTabs(this);
		}
	}
}
