package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import net.minecraft.server.packs.resources.ReloadInstance;
import com.origin.client.client.StartupTransition;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Loading -> title: once the resource load is done, the Origin loading screen
// dissolves into the screen behind it over StartupTransition.REVEAL_MS, then
// the overlay is removed. Without this, LoadingOverlayMixin's opaque TAIL draw
// holds the loading screen through vanilla's whole 2 s fade-out and then hard-
// cuts to the title (input is blocked that whole time too).
//
// Why a HEAD cancel is safe HERE when it isn't in LoadingOverlayMixin: it only
// cancels once fadeOutStart is set, i.e. after vanilla has already run the
// completion step (checkExceptions -> onFinish -> screen init). What's left of
// vanilla's render after that point is the fade drawing and setOverlay(null),
// and this does both. Every other frame — and any frame where Origin rendering
// is off — runs vanilla untouched, so vanilla's own 2 s end stays the safety
// net and a hang is impossible.
//
// PER-VERSION (1.21.6+, deferred GUI): Screen.render no longer draws the
// screen's backdrop — vanilla submits renderBackground as its own stratum
// first — so the screen beneath is drawn as renderBackground, nextStratum,
// render (the title's rings come from TitleScreenMixin's render-HEAD draw, so
// for it the background call is covered and harmless). There is no flush:
// renderLoadingFade opens a fresh stratum over everything submitted here. Once
// the screen is submitted this frame always cancels, so vanilla's own render
// can't submit it (and its blur) a second time.
//
// Its own small mixin (in the isolated required:false loading config) so a
// shadow that doesn't resolve skips only the dissolve, never the loading screen.
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayRevealMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private boolean fadeIn;
	@Shadow
	private long fadeOutStart;
	@Shadow
	@Final
	private ReloadInstance reload;

	// StartupTransition.clockMs() just after this overlay's first dissolve frame; 0 = none yet.
	// The dissolve's clock starts here, not at fadeOutStart: vanilla sets that
	// before it builds the title screen, and the first title frame is often slow
	// (seen at 1.4 s on CI's software GL), so counting from fadeOutStart can spend
	// the whole dissolve before a single frame of it reaches the screen.
	@Unique
	private long originclient$revealAt = 0L;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$reveal(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginFrameLog.ENABLED && !this.fadeIn && this.reload.isDone()) {
			OriginFrameLog.reloadSeenDone();   // dev-only: the bar-full frame
		}
		if (this.fadeOutStart <= -1L || !OriginScreenRenderer.isActive()) {
			return;
		}
		long now = Util.getMillis();
		// The hand-off's one clock (StartupTransition.clockMs), the same the mark's
		// flight and the title's entrance count on, so a slow frame moves all three alike.
		long t = this.originclient$revealAt == 0L ? 0L : StartupTransition.clockMs() - this.originclient$revealAt;
		if (now < this.fadeOutStart || t >= 2000L) {
			return;
		}
		Screen screen = this.minecraft.screen;
		if (t >= StartupTransition.REVEAL_MS) {
			// Dissolve finished: this frame is the screen alone, and from the
			// next one the game renders and routes input to it normally. The
			// overlay goes first so the title (which hides its mark while a
			// LoadingOverlay is up) draws its mark on this very frame.
			this.minecraft.setOverlay(null);
			originclient$drawScreen(screen, guiGraphics, mouseX, mouseY, partialTick);
			ci.cancel();
			return;
		}
		originclient$drawScreen(screen, guiGraphics, mouseX, mouseY, partialTick);
		float alpha = (float) (1.0 - StartupTransition.ease(t / (double) StartupTransition.REVEAL_MS));
		// The game's first load landing on the title: the loading mark flies onto
		// the title's logo instead of dissolving (a later resource reload just fades).
		boolean toTitle = !this.fadeIn && screen instanceof net.minecraft.client.gui.screens.TitleScreen;
		boolean drawn = OriginScreenRenderer.renderLoadingFade(guiGraphics, alpha, toTitle);
		if (this.originclient$revealAt == 0L) {
			// First frame: the loading screen still fully covers the title (which
			// has now rendered once, its slow first frame out of the way). Both the
			// dissolve and the title's entrance count from here.
			this.originclient$revealAt = StartupTransition.clockMs();
			if (!this.fadeIn) {
				StartupTransition.titleRevealed();  // the startup load, not a later resource reload
			}
		}
		if (!drawn) {
			// Origin rendering just failed: show the screen as it is, no overlay.
			this.minecraft.setOverlay(null);
		}
		ci.cancel();
	}

	/** The screen beneath, the way vanilla 1.21.6+ composes it: backdrop stratum, then the screen. */
	@Unique
	private static void originclient$drawScreen(Screen screen, GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (screen == null) {
			return;
		}
		screen.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.nextStratum();
		screen.render(guiGraphics, mouseX, mouseY, partialTick);
	}
}
