package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.LockIconButton;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.inventory.PageButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Restyles every button in the Origin look, on every screen: cancels the
// vanilla button drawing and draws the Origin style instead. Restyling
// happens in-place (no widgets added/removed) -- buttons keep their
// positions, actions, and clicks; only renderWidget is replaced.
//
// PER-VERSION DELTA (1.21.11): on 1.21.1 coverage scoped ITSELF by the class
// hierarchy -- subclasses with their own renderWidget override (ImageButton,
// SpriteIconButton, Checkbox, sliders) simply bypassed this mixin. That is no
// longer true here: 1.21.11 made AbstractButton.renderWidget `protected final`
// and moved subclass drawing into the abstract renderContents(...), so there is
// exactly ONE renderWidget in the whole hierarchy and this inject pre-empts
// every button type, including ones that draw their own art.
//
// So the scoping has to be explicit now, and it is a WHITELIST (originclient$
// ownsLook below): Origin only claims the button types it actually has a look
// for. Anything else -- sprite buttons, checkboxes, screen-specific buttons --
// reaches vanilla (or its own Origin mixin). A blacklist was not enough: every
// missed type renders as an empty Origin box, which is exactly what the
// inventory showed (recipe-book button, recipe-book tab, and the craftable
// filter as three blank squares, the filter's hidden "Showing All" label
// sprawling out of it).
//
// Plain Button and text CycleButtons (the "Something: Value" toggles all over
// the Options menus) are covered here; a CycleButton counts as text exactly
// when vanilla would draw only its label (originclient$isTextCycle). If that
// test breaks, Options toggles silently go vanilla grey again (the 2026-10-13
// Controls-screen report) -- nothing crashes. Dynamic labels with no baked
// Inter texture fall back to vanilla font inside the Origin shell, per the
// settled font decision. Disabled buttons (active=false, e.g. Telemetry Data)
// render the dimmed Origin style and skip hover.
// priority 2000: Origin's widget restyle wins over other UI mods.
@Mixin(value = AbstractButton.class, priority = 2000)
public class AbstractButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Not ours to restyle -- leave renderWidget alone so it reaches the
		// widget's own renderContents (vanilla art, or a dedicated Origin mixin
		// such as SpriteIconButtonMixin / CheckboxMixin).
		if (!originclient$ownsLook(this)) {
			return;
		}
		// Only cancel vanilla when Origin actually drew -- if the styled draw
		// ever fails (e.g. on a different game version), vanilla buttons return.
		if (OriginButtonRenderer.render(guiGraphics, (AbstractButton) (Object) this)) {
			ci.cancel();
		}
	}

	// The whitelist: which AbstractButtons wear the Origin shell.
	private static boolean originclient$ownsLook(Object widget) {
		// Sprite-drawn buttons keep vanilla art. Their picture lives in
		// renderContents, so an Origin shell here would erase it: ImageButton
		// covers the recipe-book open button and RecipeBookTabButton, PageButton
		// the book arrows, LockIconButton the world-difficulty lock.
		if (widget instanceof ImageButton || widget instanceof SpriteIconButton
				|| widget instanceof PageButton || widget instanceof LockIconButton) {
			return false;
		}
		// Link-style text with no shell at all (credits, "Open in browser").
		if (widget instanceof PlainTextButton) {
			return false;
		}
		if (widget instanceof CycleButton<?> cycle) {
			// Text cycle buttons = Origin look; sprite ones (recipe filter) and
			// label-less ones (DisplayState.HIDE) = vanilla.
			return originclient$isTextCycle(cycle);
		}
		// Plain vanilla Buttons and Origin's own Button subclasses. Everything
		// that is an AbstractButton but not a Button -- Checkbox (CheckboxMixin
		// draws it), the beacon screen's custom buttons -- stays vanilla.
		return widget instanceof Button;
	}

	// True when vanilla's CycleButton.renderContents would draw a text label and
	// nothing else, i.e. the button is a plain toggle Origin can restyle. It asks
	// the same two questions vanilla asks (javap, renderContents):
	//   offsets 0-18:  spriteSupplier.apply(this, getValue()) -- non-null = blit
	//                  that sprite (recipe book filter) -> vanilla.
	//   offsets 54-61: displayState == HIDE -> no label at all -> vanilla.
	// Testing the supplier FIELD for null is wrong on 1.21.11 and 26.x: every
	// CycleButton.Builder installs a default supplier that returns null
	// (Builder.<init> 14-19), so the field is never null and every Options
	// toggle (Sneak: Hold, Auto-Jump: OFF, Chat: Shown...) fell through to
	// vanilla grey.
	private static <T> boolean originclient$isTextCycle(CycleButton<T> cycle) {
		CycleButtonSpriteAccessor accessor = (CycleButtonSpriteAccessor) cycle;
		if (accessor.originclient$displayState() == CycleButton.DisplayState.HIDE) {
			return false;
		}
		try {
			@SuppressWarnings("unchecked")
			CycleButton.SpriteSupplier<T> supplier = (CycleButton.SpriteSupplier<T>) accessor.originclient$spriteSupplier();
			return supplier == null || supplier.apply(cycle, cycle.getValue()) == null;
		} catch (Throwable t) {
			// A (third-party) supplier threw. This runs before OriginButtonRenderer's
			// fail-soft latch, so never let it escape: leave the button to vanilla.
			return false;
		}
	}
}
