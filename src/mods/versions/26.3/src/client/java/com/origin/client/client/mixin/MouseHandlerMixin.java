package com.origin.client.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.OriginClientMod;
import com.origin.client.client.OriginFreelookState;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Freelook input capture. Previous approach was a @Redirect on the single
// player.turn(DD) call inside turnPlayer — with defaultRequire=0 in the mixin
// config a failed owner match dies SILENTLY, and live testing showed freelook
// not working, which is exactly that failure mode. A HEAD @Inject has no
// owner-matching to get wrong: while freelook is held we consume the
// accumulated mouse deltas ourselves (vanilla's own sensitivity curve),
// rotate only the camera accumulator, and cancel — the player entity's real
// rotation is never touched, so releasing the key snaps the view back
// automatically.
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;

	@Shadow public abstract boolean isMouseGrabbed();

	// Freelook's own copy of vanilla's cinematic-camera filter. turnPlayer is cancelled while
	// freelook is held, so vanilla's smoothTurnX/Y never see the freelook deltas: without this
	// pair "Smooth Camera Movement" (which only sets options.smoothCamera) changed nothing.
	// Reset each time freelook starts so a glide never carries over from the last hold.
	@Unique
	private final SmoothDouble originclient$smoothX = new SmoothDouble();
	@Unique
	private final SmoothDouble originclient$smoothY = new SmoothDouble();
	@Unique
	private boolean originclient$freelookWasActive;
	@Unique
	private double originclient$lastTurnTime;

	// Scroll to Zoom: while actively zoomed, the wheel changes zoom depth (a
	// transient multiplier applied in GameRendererMixin) instead of scrolling
	// the hotbar. Only swallows the scroll when zoom is actually active. Never inside a
	// screen: Toggle Zoom stays latched while one is open, and chat, inventories and
	// scroll lists keep their own wheel.
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void originclient$scrollZoom(long windowPointer, double xOffset, double yOffset, CallbackInfo ci) {
		if (Mods.on("zoom") && OriginClientMod.zoomActive && Mods.bool("zoom", "scrollZoom") && yOffset != 0
				&& Minecraft.getInstance().gui.screen() == null) {
			double speed = Math.max(0.1, Mods.num("zoom", "scrollSpeed"));
			double f = OriginClientMod.zoomScrollFactor * Math.pow(1.15, -yOffset * speed);
			OriginClientMod.zoomScrollFactor = Math.max(0.2, Math.min(3.0, f));
			ci.cancel();
			return;
		}
		// Disable Hotbar Scrolling (SETTINGS > General): swallow the wheel while
		// in-game so it never changes the held slot. Only the plain in-world
		// case — screens (their own scroll) and spectator mode are left alone.
		Minecraft mc = Minecraft.getInstance();
		if (Mods.bool(Mods.GENERAL_ID, "disableHotbarScrolling")
				&& mc.gui.screen() == null && mc.player != null && !mc.player.isSpectator()) {
			ci.cancel();
		}
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void originclient$freelookTurn(CallbackInfo ci) {
		if (!OriginFreelookState.active) {
			this.originclient$freelookWasActive = false;
			return;
		}
		// Seconds, like vanilla's movement time (the filter's step size). Our own clock: the
		// turnPlayer argument differs between versions (none before 1.20.5, a double after).
		double now = System.nanoTime() / 1.0e9;
		double movementTime = now - this.originclient$lastTurnTime;
		this.originclient$lastTurnTime = now;
		if (!this.originclient$freelookWasActive) {
			this.originclient$freelookWasActive = true;
			this.originclient$smoothX.reset();
			this.originclient$smoothY.reset();
			movementTime = 0.0;
		}
		Minecraft mc = Minecraft.getInstance();
		if (this.isMouseGrabbed() && mc.isWindowActive()) {
			// vanilla's exact sensitivity curve
			double d = mc.options.sensitivity().get() * 0.6 + 0.2;
			double f = d * d * d * 8.0;
			double dx;
			double dy;
			if (mc.options.smoothCamera) {
				// Smooth Camera Movement (or the player's own Cinematic Camera): vanilla's
				// smooth path, same filter and same arguments as MouseHandler.turnPlayer
				dx = this.originclient$smoothX.getNewDeltaValue(this.accumulatedDX * f, movementTime * f);
				dy = this.originclient$smoothY.getNewDeltaValue(this.accumulatedDY * f, movementTime * f);
			} else {
				dx = this.accumulatedDX * f;
				dy = this.accumulatedDY * f;
			}
			int inv = mc.options.invertMouseY().get() ? -1 : 1;
			double yawSign = Mods.bool("freelook", "invertYaw") ? -1 : 1;
			double pitchSign = Mods.bool("freelook", "invertPitch") ? -1 : 1;
			// 0.15 = vanilla Entity.turn's degrees-per-unit factor. Yaw is
			// unlimited (full 360° orbit); pitch clamps at ±90 like F5.
			OriginFreelookState.cameraYaw += (float) (dx * 0.15 * yawSign);
			OriginFreelookState.cameraPitch = (float) Math.max(-90.0, Math.min(90.0,
					OriginFreelookState.cameraPitch + dy * 0.15 * inv * pitchSign));
		}
		this.accumulatedDX = 0.0;
		this.accumulatedDY = 0.0;
		ci.cancel();
	}
}
