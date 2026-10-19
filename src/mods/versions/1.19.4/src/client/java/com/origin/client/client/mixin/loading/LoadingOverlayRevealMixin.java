package com.origin.client.client.mixin.loading;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.StartupTransition;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
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
// and this does both. Every other frame — and any frame where this throws or
// Origin rendering is off — runs vanilla untouched, so vanilla's own 2 s end
// stays the safety net and a hang is impossible.
//
// Its own small mixin (in the isolated required:false loading config) so a
// shadow that doesn't resolve skips only the dissolve, never the loading screen.
// Pre-1.20: render takes a PoseStack (no 1.20 draw context yet) and every draw is
// immediate, so there is no batch to flush between the screen and the fade.
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
	private net.minecraft.server.packs.resources.ReloadInstance reload;

	// StartupTransition.clockMs() just after this overlay's first dissolve frame; 0 = none yet.
	// The dissolve's clock starts here, not at fadeOutStart: vanilla sets that
	// before it builds the title screen, and the first title frame is often slow
	// (seen at 1.4 s on CI's software GL), so counting from fadeOutStart can spend
	// the whole dissolve before a single frame of it reaches the screen.
	@Unique
	private long originclient$revealAt = 0L;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$reveal(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (com.origin.client.client.OriginFrameLog.ENABLED && !this.fadeIn && this.reload.isDone()) {
			com.origin.client.client.OriginFrameLog.reloadSeenDone();   // dev-only: the bar-full frame
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
			// overlay goes first so the screen draws as it will from now on (the
			// title's mark checks for it).
			this.minecraft.setOverlay(null);
			if (screen != null) {
				screen.render(poseStack, mouseX, mouseY, partialTick);
			}
			ci.cancel();
			return;
		}
		if (screen != null) {
			screen.render(poseStack, mouseX, mouseY, partialTick);
		}
		float alpha = (float) (1.0 - StartupTransition.ease(t / (double) StartupTransition.REVEAL_MS));
		// The game's first load landing on the title: the loading mark flies onto
		// the title's logo instead of dissolving (a later resource reload just fades).
		boolean toTitle = !this.fadeIn && screen instanceof net.minecraft.client.gui.screens.TitleScreen;
		boolean drawn = OriginScreenRenderer.renderLoadingFade(new Gfx(poseStack), alpha, toTitle);
		if (this.originclient$revealAt == 0L) {
			// First frame: the loading screen still fully covers the title (which
			// has now rendered once, its slow first frame out of the way). Both the
			// dissolve and the title's entrance count from here.
			this.originclient$revealAt = StartupTransition.clockMs();
			if (!this.fadeIn) {
				StartupTransition.titleRevealed();  // the startup load, not a later resource reload
			}
		}
		if (drawn) {
			ci.cancel();
		}
	}
}
