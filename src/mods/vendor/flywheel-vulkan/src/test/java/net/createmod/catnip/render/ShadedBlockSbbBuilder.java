package net.createmod.catnip.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Test stand-in for catnip's ShadedBlockSbbBuilder (same name, same {@code unwrap(boolean)} shape):
 * records each shade flag it is unwrapped with and hands back its "raw buffer".
 * BulkOnlyConsumersTest drives it; the real one lives in Ponder, never on this module's classpath.
 */
public class ShadedBlockSbbBuilder {
	public final Object raw = new Object();
	public final List<Boolean> shades = new ArrayList<>();
	public boolean failUnwrap;

	public Object unwrap(boolean shade) {
		if (failUnwrap) throw new IllegalStateException("test failure");
		shades.add(shade);
		return raw;
	}

	/** Catnip creates its builders through factories; a subclass must be handled the same way. */
	public static class Ponder extends ShadedBlockSbbBuilder {
	}
}
