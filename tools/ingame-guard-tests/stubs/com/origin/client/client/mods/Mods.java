package com.origin.client.client.mods;

// Test stub: ModsConfig.applyProfile calls Mods.keepOldDefaultKeys() for a profile saved before
// the default-key move (BMOD-6). Counts the calls; the real step lives in each module's Mods.
public final class Mods {
	public static int keepOldDefaultKeysCalls = 0;

	// Every profile apply also runs the Key Strokes border fix (N94-2): counted the same way.
	public static int keystrokesBorderCalls = 0;

	static boolean fixInvisibleKeystrokesBorder() {
		keystrokesBorderCalls++;
		return false;
	}

	static boolean keepOldDefaultKeys() {
		keepOldDefaultKeysCalls++;
		return false;
	}
}
