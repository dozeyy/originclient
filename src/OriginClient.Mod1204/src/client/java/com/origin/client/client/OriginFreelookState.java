package com.origin.client.client;

// Shared between MouseHandlerMixin (accumulates camera-only rotation while
// freelook is held) and EntityViewAngleMixin (feeds that rotation into the
// rendered camera instead of the player's real, frozen body rotation).
public final class OriginFreelookState {
	public static volatile boolean active = false;
	public static volatile float cameraYaw;
	public static volatile float cameraPitch;

	private OriginFreelookState() {
	}
}
