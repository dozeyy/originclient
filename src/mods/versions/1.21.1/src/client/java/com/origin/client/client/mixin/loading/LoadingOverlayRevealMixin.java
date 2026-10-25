package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import com.origin.client.client.OriginStyle;
import com.origin.client.client.StartupTransition;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Loading -> title: once the resource load is done, the screen behind the
// loading overlay first SETTLES, then the Origin loading screen dissolves into
// it over StartupTransition.REVEAL_MS and the overlay is removed. Without this,
// LoadingOverlayMixin's opaque TAIL draw holds the loading screen through
// vanilla's whole 2 s fade-out and then hard-cuts to the title (input is blocked
// that whole time too).
//
// The settle hold (Will 2026-10-18: the picture froze "right before the main
// menu", then the dissolve played jerkily, worse with mods). A screen's first
// frames are its slowest: every texture, driver shader and glyph it uses is paid
// for the first time, and a GC may land there too. So the dissolve no longer
// starts the moment the load reports done. Instead the real screen renders for
// real, every frame, UNDER the finished loading picture (bar full, mark at rest,
// so a slow frame here shows nothing stopping), until SETTLE_FRAMES frames in a
// row came in under the settle limit — or HOLD_CAP_MS, so it can never hang.
// Only then does the dissolve start, on frames that are already paid for.
// The settle limit is SETTLE_MS (20 ms, a 60 fps frame with slack), or 1.5x the
// fastest hold frame on a machine whose steady frame is slower than that, so a
// slow PC settles on "no more spikes" instead of always waiting out the cap.
// The hold runs for the startup load only (!fadeIn). A later reload (F3+T, the
// pack screen) dissolves at once: there is no new title to pay for, and with a
// shader pack in a world the frames never settle, so it would only add the cap.
// Porting note (1.21.2-1.21.4, the 3eb35971 trap: touching the ShaderManager
// mid-reload drops the loading screen and blacks out every screen after): the
// hold itself is safe, it only begins after the reload is done (fadeOutStart is
// set only then). But OriginAssets.uploadSlice, called from the loading screen
// while the load is still RUNNING, reads OriginShaders for its icon step: on
// those versions gate uploadSlice on reload.isDone() (or drop the icon step).
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
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayRevealMixin {
	/** Frames in a row under the settle limit before the dissolve may start. */
	@Unique
	private static final int SETTLE_FRAMES = 5;
	/** The settle limit on a machine that runs at 60 fps or better (ms). */
	@Unique
	private static final double SETTLE_MS = 20.0;
	/** On a slower machine: this much over its own fastest hold frame. */
	@Unique
	private static final double SETTLE_SPREAD = 1.5;
	/** The hold never lasts longer than this (ms of real time). */
	@Unique
	private static final long HOLD_CAP_MS = 1500;

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

	// StartupTransition.clockMs() when the dissolve started; 0 = still holding.
	@Unique
	private long originclient$revealAt = 0L;
	// The settle hold: its first frame (nanoTime, 0 = not yet), the previous hold
	// frame, the fastest hold frame so far, and the last SETTLE_FRAMES frame times.
	@Unique
	private long originclient$holdAt = 0L, originclient$lastHold = 0L;
	@Unique
	private double originclient$fastest = Double.MAX_VALUE;
	@Unique
	private final double[] originclient$recent = new double[SETTLE_FRAMES];
	@Unique
	private int originclient$holdFrames = 0;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$reveal(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginFrameLog.ENABLED && !this.fadeIn && this.reload.isDone()) {
			OriginFrameLog.reloadSeenDone();   // dev-only: the bar-full frame
		}
		if (this.fadeOutStart <= -1L || !OriginScreenRenderer.isActive() || OriginStyle.vanillaMenus()) {
			return;
		}
		long now = Util.getMillis();
		if (now < this.fadeOutStart) {
			return;
		}
		Screen screen = this.minecraft.screen;
		if (this.originclient$revealAt == 0L) {
			if (!this.fadeIn) {
				originclient$hold(guiGraphics, mouseX, mouseY, partialTick, screen, ci);
				return;
			}
			// A later reload (F3+T, the pack screen): no fresh title to pay for, and
			// in-world frames with a shader pack jitter too much to ever "settle",
			// so a hold would only add up to HOLD_CAP_MS of blocked input. Dissolve now.
			this.originclient$revealAt = StartupTransition.clockMs();
		}
		// The hand-off's one clock (StartupTransition.clockMs), the same the mark's
		// flight and the title's entrance count on, so a slow frame moves all three alike.
		long t = StartupTransition.clockMs() - this.originclient$revealAt;
		if (t >= 2000L) {
			return;
		}
		if (t >= StartupTransition.REVEAL_MS) {
			// Dissolve finished: this frame is the screen alone, and from the
			// next one the game renders and routes input to it normally. The
			// overlay goes first so the screen draws as it will from now on (the
			// title's mark checks for it).
			this.minecraft.setOverlay(null);
			if (screen != null) {
				screen.render(guiGraphics, mouseX, mouseY, partialTick);
			}
			ci.cancel();
			return;
		}
		// The game's first load landing on an Origin title (DESIGN-SYSTEM 15.3): the
		// loading logo moves onto the title's mark instead of dissolving, so the title
		// leaves its own mark out under it. Anything else (a later resource reload,
		// the vanilla Main Menu Style, a broken title backdrop) keeps the plain fade.
		boolean fly = !this.fadeIn && screen instanceof net.minecraft.client.gui.screens.TitleScreen
				&& OriginScreenRenderer.logoFlightReady();
		if (screen != null) {
			StartupTransition.setTitleMarkInFlight(fly);
			try {
				screen.render(guiGraphics, mouseX, mouseY, partialTick);
			} finally {
				StartupTransition.setTitleMarkInFlight(false);
			}
		}
		guiGraphics.flush();
		float alpha = (float) (1.0 - StartupTransition.ease(t / (double) StartupTransition.REVEAL_MS));
		if (OriginScreenRenderer.renderLoadingFade(guiGraphics, alpha, fly)) {
			ci.cancel();
		}
	}

	/** One settle-hold frame: the screen for real, the finished loading picture over it; ends the hold when settled. */
	@Unique
	private void originclient$hold(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, Screen screen, CallbackInfo ci) {
		long ns = System.nanoTime();
		if (this.originclient$holdAt == 0L) {
			this.originclient$holdAt = ns;
			OriginFrameLog.mark("hold-start");
		} else {
			double frame = (ns - this.originclient$lastHold) / 1e6;
			this.originclient$recent[this.originclient$holdFrames % SETTLE_FRAMES] = frame;
			this.originclient$holdFrames++;
			this.originclient$fastest = Math.min(this.originclient$fastest, frame);
		}
		this.originclient$lastHold = ns;

		if (screen != null) {
			screen.render(guiGraphics, mouseX, mouseY, partialTick);
		}
		guiGraphics.flush();
		boolean drawn = OriginScreenRenderer.renderLoadingHold(guiGraphics);

		boolean settled = originclient$settled();
		boolean capped = (ns - this.originclient$holdAt) / 1_000_000L >= HOLD_CAP_MS;
		if (!drawn || settled || capped) {
			// The dissolve and the title's entrance count from here; this frame
			// already showed the full loading picture, the next one starts fading it.
			this.originclient$revealAt = StartupTransition.clockMs();
			if (!this.fadeIn) {
				StartupTransition.titleRevealed();  // the startup load, not a later resource reload
			}
			if (OriginFrameLog.ENABLED) {
				OriginFrameLog.mark((settled ? "settled" : !drawn ? "hold-draw-failed" : "hold-cap") + " after "
						+ this.originclient$holdFrames + " frames, limit "
						+ String.format(java.util.Locale.ROOT, "%.1f", originclient$limit()) + " ms");
			}
		}
		if (drawn) {
			ci.cancel();
		}
	}

	/** The last SETTLE_FRAMES hold frames were all under the settle limit. */
	@Unique
	private boolean originclient$settled() {
		if (this.originclient$holdFrames < SETTLE_FRAMES) {
			return false;
		}
		double limit = originclient$limit();
		for (double f : this.originclient$recent) {
			if (f > limit) {
				return false;
			}
		}
		return true;
	}

	@Unique
	private double originclient$limit() {
		double f = this.originclient$fastest == Double.MAX_VALUE ? 0.0 : this.originclient$fastest;
		return Math.max(SETTLE_MS, f * SETTLE_SPREAD);
	}
}
