package org.lwjgl.opengl;

/** Test stand-in: the game window exists (1.8.9's util/KeyCodes reads the layout only then). */
public final class Display {
	private Display() {
	}

	public static boolean isCreated() {
		return true;
	}
}
