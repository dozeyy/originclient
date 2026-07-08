package com.origin.client.client.mixin;

import com.origin.client.client.OriginFreelookState;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// While freelook is held, intercepts the single rotation call turnPlayer()
// makes on the player entity and redirects it into a camera-only accumulator
// instead — the entity's real yaw/pitch stay frozen (used for movement/
// hitbox), only the rendered view rotates.
//
// Confirmed against the real 1.21.1 bytecode: turnPlayer doesn't call
// Entity.setYRot(float)/setXRot(float) with an absolute new angle the way
// this mixin originally assumed. It calls turn(double yRotDelta, double
// xRotDelta) exactly once — deltas to add, not new absolute angles.
//
// That method is declared on Entity, but the real invoke instruction inside
// turnPlayer references it through Minecraft.player's own static type,
// LocalPlayer — not Entity. Targeting Entity;turn(DD)V here compiled and
// remapped cleanly (no build-time error) but still failed to match anything
// at runtime ("(0/1) succeeded. Scanned 0 target(s)") because Mixin's INVOKE
// matcher checks the owner as it actually appears in the target bytecode,
// not the method's semantic declaring class. Retargeting through
// LocalPlayer — the exact owner the real invokevirtual instruction uses —
// is what actually matches.
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

	// Feeds the CPS counter + Keystrokes overlay: raw button press/release
	// events, recorded before any GUI/game routing so held-state is exact.
	@org.spongepowered.asm.mixin.injection.Inject(method = "onPress", at = @At("HEAD"))
	private void originclient$trackClicks(long windowPointer, int button, int action, int modifiers,
										  org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		com.origin.client.client.mods.ClickStats.onButton(button, action == org.lwjgl.glfw.GLFW.GLFW_PRESS);
	}

	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void originclient$redirectTurn(LocalPlayer player, double yRotDelta, double xRotDelta) {
		if (OriginFreelookState.active) {
			OriginFreelookState.cameraYaw += (float) yRotDelta;
			OriginFreelookState.cameraPitch += (float) xRotDelta;
		} else {
			player.turn(yRotDelta, xRotDelta);
		}
	}
}
