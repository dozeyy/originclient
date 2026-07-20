package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginModsListScreen;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// Re-skins the main menu: the Origin background (charcoal + rotating rings +
// grain) replaces the panorama, the "ORIGIN" wordmark replaces the vanilla
// "Minecraft" logo, and the splash/version text + the language/accessibility/
// copyright buttons are hidden -- leaving just the real menu buttons + header.
//
// Strategy (all targets confirmed via javap/bytecode against the mapped 1.19.4
// jar -- this era predates GuiGraphics AND TitleScreen.renderPanorama, so the
// suppression shape differs from the 1.20 module):
//  - Draw the Origin background at render() HEAD -- render() is guaranteed to
//    run every frame, so the background is always painted, under the logo/
//    buttons that draw afterward.
//  - 1.19.4's render() calls this.panorama.render(partialTick, alpha) DIRECTLY
//    (no renderPanorama method to cancel), then blits the PANORAMA_OVERLAY
//    vignette texture on top. Both are @Redirect-ed to no-ops so neither can
//    paint over the Origin backdrop; each is the only call of its shape in
//    render() (bytecode-verified: one PanoramaRenderer.render, one blit).
//  - Redirect renderLogo -> Origin wordmark.
//  - No SplashRenderer class in this era: the splash is a String field drawn
//    via the one drawCenteredString call in render() -- redirected to a no-op
//    (its pose push/rotate/pop around the call stays balanced). The version
//    line is the one drawString call, same treatment.
//  - After init(), keep hiding the copyright PlainTextButton, un-hide the
//    language/accessibility icon squares (ImageButton), insert the "Mods" row
//    (opens the read-only OriginModsListScreen), and shift the Options/Quit
//    half-row + icons down one 24px pitch. Button faces come from this module's
//    AbstractButtonMixin restyle, same as every menu. 2026-07-20 redesign from
//    Will's reference shots; settings stay in-game (Right Shift).
// priority 2000 (default 1000): if another mod also modifies TitleScreen (e.g.
// redirects the logo/background), Origin's re-skin wins the conflict. Scoped to
// the UI mixins only — perf/render mixins stay at default so Sodium/Iris
// application ordering is left undisturbed.
@Mixin(value = TitleScreen.class, priority = 2000)
public abstract class TitleScreenMixin extends Screen {

	protected TitleScreenMixin(Component title) {
		super(title);
	}

	// SETTINGS > General > Main Menu Style. "Vanilla" turns the entire re-skin
	// off — every inject below no-ops so the stock title screen shows through.
	private static boolean originclient$origin() {
		return Mods.mode(Mods.GENERAL_ID, "mainMenuStyle").equals("Origin");
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$background(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		Gfx g = new Gfx(poseStack);
		OriginScreenRenderer.renderTitleBackground(g);
		// The website's mouse-follow spotlight: over the rings, under the
		// widgets (this HEAD inject runs before the widget pass). Blooms while
		// any visible button is hovered, like the site's hover targets.
		boolean hoveringClickable = false;
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(g, mouseX, mouseY, hoveringClickable);
		// Account chip (player head + username) in the top-left frame corner.
		OriginScreenRenderer.renderTitleAccountChip(g);
	}

	// Both suppressions are gated on the renderer's health: if the Origin
	// backdrop ever fails (fail-soft contract), vanilla's panorama comes back
	// instead of leaving a black screen.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/PanoramaRenderer;render(FF)V"))
	private void originclient$suppressPanorama(PanoramaRenderer instance, float partialTick, float alpha) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			instance.render(partialTick, alpha);
		}
	}

	// The PANORAMA_OVERLAY vignette blit right after the panorama — the only
	// 10-arg blit in render().
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIFFIIII)V"))
	private void originclient$suppressOverlay(PoseStack poseStack, int x, int y, int w, int h,
											  float u, float v, int uW, int vH, int texW, int texH) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			GuiComponent.blit(poseStack, x, y, w, h, u, v, uW, vH, texW, texH);
		}
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;renderLogo(Lcom/mojang/blaze3d/vertex/PoseStack;IF)V"))
	private void originclient$logo(LogoRenderer instance, PoseStack poseStack, int screenWidth, float alpha) {
		// Fail-soft: if the wordmark can't draw (or the style is Vanilla),
		// restore vanilla's own logo so the title never loses its centerpiece.
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleWordmark(new Gfx(poseStack))) {
			instance.renderLogo(poseStack, screenWidth, alpha);
		}
	}

	// Remove the yellow splash text (Origin style only). Pre-1.20 there is no
	// SplashRenderer — the splash String is drawn by the single
	// drawCenteredString in render().
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;drawCenteredString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void originclient$noSplash(PoseStack poseStack, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			GuiComponent.drawCenteredString(poseStack, font, text, x, y, color);
		}
	}

	// Remove the bottom version line (the only drawString in render()).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void originclient$noVersion(PoseStack poseStack, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			GuiComponent.drawString(poseStack, font, text, x, y, color);
		}
	}

	// The Origin stacked layout (Will's reference shots): keep vanilla's
	// Singleplayer/Multiplayer/Realms rows where they are, insert a "Mods" row
	// beneath them, and push the Options/Quit half-row + the two icon squares
	// down one 24px pitch. Copyright (PlainTextButton) stays hidden. Widgets are
	// matched by type + width (200 = stack row, 98 = half row, ImageButton =
	// icon square), so the match is locale-proof; anchoring on the first stack
	// row's own y tracks the version's layout anchor. Re-runs on every (re)init,
	// and init() rebuilds widgets, so resizes never double the Mods row. An
	// unexpected widget census leaves vanilla's arrangement alone (fail-soft).
	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$originLayout(CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		List<AbstractWidget> stack = new ArrayList<>();
		List<AbstractWidget> halfRow = new ArrayList<>();
		List<AbstractWidget> icons = new ArrayList<>();
		for (GuiEventListener child : this.children()) {
			if (child instanceof PlainTextButton copyright) {
				copyright.visible = false;
				copyright.active = false;
			} else if (child instanceof ImageButton icon) {
				icons.add(icon);
			} else if (child instanceof AbstractWidget widget && widget.getWidth() == 200) {
				stack.add(widget);
			} else if (child instanceof AbstractWidget widget && widget.getWidth() == 98) {
				halfRow.add(widget);
			}
		}
		if (stack.isEmpty()) {
			return;
		}
		int modsY = stack.get(0).getY() + 24 * stack.size();
		int rowY = modsY + 24 + 12; // vanilla's own 12px gap before the half row
		for (AbstractWidget widget : halfRow) {
			widget.setY(rowY);
		}
		for (AbstractWidget widget : icons) {
			widget.setY(rowY);
		}
		Screen self = (Screen) (Object) this;
		addRenderableWidget(Button.builder(Component.translatable("originclient.menu.mods"),
						b -> this.minecraft.setScreen(new OriginModsListScreen(self)))
				.bounds(this.width / 2 - 100, modsY, 200, 20)
				.build());
	}
}
