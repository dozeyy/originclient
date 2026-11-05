package org.lwjgl.input;

/** Test stand-in for LWJGL 2's Mouse: nothing is held. */
public final class Mouse {
	private Mouse() {
	}

	public static int getButtonCount() {
		return 8;
	}

	public static boolean isButtonDown(int button) {
		return false;
	}
}
