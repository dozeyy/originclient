package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import mezz.jei.gui.events.GuiEventHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The RENDER half of the JEI toggle: with JEI off, nothing of JEI is drawn.
 * ({@link JeiClientInputHandlerMixin} is the input half — both are needed, since
 * hiding the overlay does not stop R/U opening a recipe screen.)
 *
 * WHY THIS CLASS. JEI's Fabric wiring (mezz.jei.fabric.startup.EventRegistration)
 * routes every screen event into exactly two objects: GuiEventHandler for drawing
 * and ClientInputHandler for input. So these two methods are the whole visible
 * surface — cancelling here leaves vanilla drawing the screen untouched, which is
 * the point: "off" has to be indistinguishable from JEI not being installed.
 *
 * WHY NOT the obvious lever. JEI already has an "overlay enabled" flag
 * (IClientToggleState.isOverlayEnabled, the ctrl+O state) and forcing it false
 * looks like the clean one-line fix. It is a trap. IngredientListOverlay's real
 * condition is:
 *     (isOverlayEnabled() || toggleOverlayKey.isUnbound()) && hasValidScreen() && hasRoom()
 * That `|| isUnbound()` is JEI's own safety valve so a player who unbinds the key
 * can't lose JEI forever. It also means forcing the flag false does NOT hide the
 * overlay for anyone with that key unbound — the toggle would silently lie. So we
 * gate the draw itself, which no keybind state can defeat. (Verified with javap
 * against jei-1.21.1-fabric-19.27.0.340, not assumed.)
 *
 * remap = false: the targets are another mod's classes, so their names must be
 * taken literally rather than looked up in the Minecraft refmap. Our own handler
 * signatures still get remapped by Loom, which is why they can name Mojang types.
 *
 * Fragility, accepted knowingly: these are JEI internals, not its public API, so
 * a JEI update can rename them. The mixin config is `"required": false` with
 * `defaultRequire: 0`, so a rename makes this silently not apply — Origin keeps
 * booting, but JEI would then ignore its toggle rather than crash. That is the
 * right failure direction for the "never broken" mandate, but it is a real
 * failure: re-verify these three method names on every JEI bump.
 */
@Mixin(value = GuiEventHandler.class, remap = false)
public abstract class JeiGuiEventHandlerMixin {

	/**
	 * The overlay itself: the item list, the bookmark overlay, and the config
	 * button all draw from here (IngredientListOverlay.drawScreen +
	 * BookmarkOverlay.drawScreen). Cancelling is what makes the screen look stock.
	 *
	 * JEI 26/27 RENAME: this was {@code onDrawScreenPost} through JEI 19.x; JEI 26
	 * (MC 1.21.10) / 27 (MC 1.21.11) renamed it to {@code drawForScreen} — same
	 * signature (Screen, GuiGraphics, int, int). Verified with javap against
	 * jei-1.21.11-fabric-27.17.0.50 and jei-1.21.10-fabric-26.3.0.31.
	 */
	@Inject(method = "drawForScreen", at = @At("HEAD"), cancellable = true)
	private void originclient$hideJeiOverlay(Screen screen, GuiGraphics guiGraphics, int mouseX, int mouseY,
			CallbackInfo ci) {
		if (!Mods.on("jei")) {
			ci.cancel();
		}
	}

	/**
	 * The foreground pass over container screens (drawOnForeground on both overlays).
	 * JEI 26/27 RENAME: was {@code onDrawForeground} ≤19.x, now
	 * {@code drawForContainerScreen} — same signature.
	 */
	@Inject(method = "drawForContainerScreen", at = @At("HEAD"), cancellable = true)
	private void originclient$hideJeiForeground(AbstractContainerScreen<?> screen, GuiGraphics guiGraphics,
			int mouseX, int mouseY, CallbackInfo ci) {
		if (!Mods.on("jei")) {
			ci.cancel();
		}
	}

	/**
	 * The subtle one. JEI reaches into VANILLA's inventory layout: its own
	 * EffectRenderingInventoryScreenMixin asks this method whether to force the
	 * potion effects into compact indicators to make room for the overlay. Miss
	 * this and "off" still shows a wrong-looking inventory whenever you have an
	 * effect active — the overlay is gone but vanilla's potion display stays
	 * squashed. Returning false hands the vanilla layout back.
	 */
	@Inject(method = "renderCompactPotionIndicators", at = @At("HEAD"), cancellable = true)
	private void originclient$vanillaPotionLayout(CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}
}
