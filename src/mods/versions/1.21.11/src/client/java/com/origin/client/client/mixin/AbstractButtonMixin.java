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
// Plain Button and CycleButton (the "Something: Value" toggles all over the
// Options menus) are covered here. Dynamic labels with no baked Inter texture
// fall back to vanilla font inside the Origin shell, per the settled font
// decision. Disabled buttons (active=false, e.g. Telemetry Data) render the
// dimmed Origin style and skip hover.
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
		if (widget instanceof CycleButton<?>) {
			// Text cycle buttons = Origin look; sprite ones (recipe filter) = vanilla.
			return ((CycleButtonSpriteAccessor) widget).originclient$spriteSupplier() == null;
		}
		// Plain vanilla Buttons and Origin's own Button subclasses. Everything
		// that is an AbstractButton but not a Button -- Checkbox (CheckboxMixin
		// draws it), the beacon screen's custom buttons -- stays vanilla.
		return widget instanceof Button;
	}
}
