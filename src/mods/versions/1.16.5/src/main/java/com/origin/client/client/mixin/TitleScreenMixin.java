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
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
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
// Strategy (all targets confirmed via javap/bytecode against the mapped 1.16.5
// jar -- this era predates GuiGraphics AND TitleScreen.renderPanorama, so the
// suppression shape differs from the 1.20 module):
//  - Draw the Origin background at render() HEAD -- render() is guaranteed to
//    run every frame, so the background is always painted, under the logo/
//    buttons that draw afterward. 1.16.5 additionally opens render() with a
//    full-screen WHITE fill (see originclient$suppressWhiteFlash) which MUST be
//    suppressed or it paints straight over that backdrop.
//  - 1.16.5's render() calls this.panorama.render(partialTick, alpha) DIRECTLY
//    (no renderPanorama method to cancel), then blits the PANORAMA_OVERLAY
//    vignette texture on top. Both are @Redirect-ed to no-ops so neither can
//    paint over the Origin backdrop; each is the only call of its shape in
//    render() (bytecode-verified: one PanoramaRenderer.render, one 10-arg blit).
//  - No LogoRenderer class in this era (it's 1.19.4+): the "Minecraft" wordmark
//    is TWO blitOutlineBlack(int,int,BiConsumer) calls -- one per branch of the
//    "Minceraft" easter-egg check, exactly one executing per frame -- plus the
//    8-arg "Java Edition" badge blit right after. One @Redirect wraps both
//    blitOutlineBlack invocations (same shape) and draws the Origin wordmark
//    instead; a second suppresses the edition badge (the only 8-arg blit in
//    render()).
//  - No SplashRenderer class in this era: the splash is a String field drawn
//    via the one drawCenteredString call in render() -- redirected to a no-op
//    (its pose push/rotate/pop around the call stays balanced). The version
//    line is the one drawString call, same treatment.
//  - After init(), un-hide the language/accessibility icon squares
//    (ImageButton), insert the "Mods" row (opens the read-only
//    OriginModsListScreen), and shift the Options/Quit half-row + icons down
//    one 24px pitch. The copyright line is a drawString, suppressed separately.
//    Button faces come from this module's button restyle. 2026-07-20 redesign
//    from Will's reference shots; settings stay in-game (Right Shift).
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
			if (child instanceof AbstractWidget && ((AbstractWidget) child).visible && ((AbstractWidget) child).isMouseOver(mouseX, mouseY)) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(g, mouseX, mouseY, hoveringClickable);
		// Account chip (player head + username) in the top-left frame corner.
		OriginScreenRenderer.renderTitleAccountChip(g);
	}

	// 1.16.5 ONLY: render() OPENS with fill(poseStack, 0, 0, width, height, -1) --
	// a full-screen WHITE fill (-1 == 0xFFFFFFFF) that vanilla immediately covers
	// with the panorama. Origin suppresses the panorama, so that white would
	// survive and bury the backdrop drawn at HEAD -- a white title screen with
	// only the button text on it. ordinal = 0 pins the background fill; the other
	// fill in render() is the copyright hover highlight (bytecode-verified against
	// the mapped 1.16.5 jar: offset 61 iconst_m1 -> fill, offset 741 the
	// copyrightX/copyrightWidth highlight). Later versions dropped this fill,
	// which is why only this module needs the redirect.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V",
			ordinal = 0))
	private void originclient$suppressWhiteFlash(PoseStack poseStack, int x1, int y1, int x2, int y2, int color) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			GuiComponent.fill(poseStack, x1, y1, x2, y2, color);
		}
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

	// The vanilla logo: both blitOutlineBlack calls (normal + "Minceraft"
	// easter-egg branch) share one shape, so this redirect wraps both; only one
	// runs per frame, so the wordmark draws exactly once. The enclosing render()
	// args are appended to the handler (Mixin arg capture) to reach the frame's
	// PoseStack — blitOutlineBlack itself doesn't carry one.
	// Fail-soft: if the wordmark can't draw (or the style is Vanilla), restore
	// vanilla's own logo so the title never loses its centerpiece.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blitOutlineBlack(IILjava/util/function/BiConsumer;)V"))
	private void originclient$logo(TitleScreen instance, int x, int y, java.util.function.BiConsumer<Integer, Integer> draw,
								   PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleWordmark(new Gfx(poseStack))) {
			instance.blitOutlineBlack(x, y, draw);
		}
	}

	// The "Java Edition" badge blit straight after the logo — the only 8-arg
	// blit in render(). Gated like the logo so it returns with vanilla's logo
	// whenever the wordmark path is off or unhealthy.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIFFIIII)V"))
	private void originclient$suppressEdition(PoseStack poseStack, int x, int y, float u, float v,
											  int w, int h, int texW, int texH) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			GuiComponent.blit(poseStack, x, y, u, v, w, h, texW, texH);
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
	// down one 24px pitch. Widgets matched by type + width (200 = stack row,
	// 98 = half row, ImageButton = icon square), locale-proof; anchored on the
	// first stack row's own y. Java 8: no pattern-matching instanceof, public
	// x/y fields, addButton (not addRenderableWidget). Re-runs on every
	// (re)init; init() rebuilds widgets so resizes never double the Mods row.
	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$originLayout(CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		List<AbstractWidget> stack = new ArrayList<AbstractWidget>();
		List<AbstractWidget> halfRow = new ArrayList<AbstractWidget>();
		List<AbstractWidget> icons = new ArrayList<AbstractWidget>();
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof ImageButton) {
				icons.add((AbstractWidget) child);
			} else if (child instanceof AbstractWidget) {
				AbstractWidget widget = (AbstractWidget) child;
				if (widget.getWidth() == 200) {
					stack.add(widget);
				} else if (widget.getWidth() == 98) {
					halfRow.add(widget);
				}
			}
		}
		if (stack.isEmpty()) {
			return;
		}
		int modsY = stack.get(0).y + 24 * stack.size();
		int rowY = modsY + 24 + 12; // vanilla's own 12px gap before the half row
		for (AbstractWidget widget : halfRow) {
			widget.y = rowY;
		}
		for (AbstractWidget widget : icons) {
			widget.y = rowY;
		}
		this.addButton(new Button(this.width / 2 - 100, modsY, 200, 20,
				new TranslatableComponent("originclient.menu.mods"),
				b -> this.minecraft.setScreen(new OriginModsListScreen(self))));
	}
}
