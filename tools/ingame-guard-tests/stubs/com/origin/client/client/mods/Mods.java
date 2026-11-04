package com.origin.client.client.mods;

// Test stub: ModsConfig.applyProfile calls Mods.keepOldDefaultKeys() for a profile saved before
// the default-key move (BMOD-6). Counts the calls; the real step lives in each module's Mods.
public final class Mods {
	public static int keepOldDefaultKeysCalls = 0;

	static boolean keepOldDefaultKeys() {
		keepOldDefaultKeysCalls++;
		return false;
	}
}
