package com.origin.client.client.util;

/** Test stand-in for 1.8.9's util/KeyTaps: no F3 combo is ever held. */
public final class KeyTaps {
	private KeyTaps() {
	}

	public static boolean inF3Combo(int key, boolean down) {
		return false;
	}
}
