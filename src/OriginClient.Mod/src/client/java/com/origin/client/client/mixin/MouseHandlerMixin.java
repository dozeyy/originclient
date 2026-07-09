package com.origin.client.client.mixin;

import com.origin.client.client.OriginFreelookState;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

	// Feeds the CPS counter + Keystrokes overlay: raw button press/release
	// events, recorded before any GUI/game routing so held-state is exact.
	@Inject(method = "onPress", at = @At("HEAD"))
	private void originclient$trackClicks(long windowPointer, int button, int action, int modifiers, CallbackInfo ci) {
		com.origin.client.client.mods.ClickStats.onButton(button, action == org.lwjgl.glfw.GLFW.GLFW_PRESS);
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void originclient$freelookTurn(CallbackInfo ci) {
		if (!OriginFreelookState.active) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (this.isMouseGrabbed() && mc.isWindowActive()) {
			// vanilla's exact sensitivity curve (non-smooth path)
			double d = mc.options.sensitivity().get() * 0.6 + 0.2;
			double f = d * d * d * 8.0;
			double dx = this.accumulatedDX * f;
			double dy = this.accumulatedDY * f;
			int inv = mc.options.invertYMouse().get() ? -1 : 1;
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
