package com.origin.client.client;

// Shared between the hub (OriginClientMod: engages freelook, seeds the camera
// angles) and the world area's mouse + camera hooks (accumulate camera-only
// rotation while freelook is held, and render that rotation instead of the
// player's real, frozen body rotation).
public final class OriginFreelookState {
	public static volatile boolean active = false;
	public static volatile float cameraYaw;
	public static volatile float cameraPitch;

	private OriginFreelookState() {
	}
}
