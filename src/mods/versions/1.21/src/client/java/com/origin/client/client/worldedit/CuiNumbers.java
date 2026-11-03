package com.origin.client.client.worldedit;

/**
 * Every number a server sends over the WorldEdit CUI channel, made safe to draw.
 *
 * Any server can send CUI lines (the channel is open whether or not WorldEdit is
 * there), so nothing here may trust them: a radius of 2e9 or a height range up to
 * Integer.MAX_VALUE once overflowed the renderer's int loops into a loop that never
 * ended -- the game froze on one line from the server. Numbers are clamped to the
 * world (no block WorldEdit can select lies past the 30M world border), NaN drops
 * the line, and the counts of shapes a server can pile up are capped.
 *
 * Pure Java (no Minecraft, Java 8): the same file ships in every module, pre-1.20
 * and 1.8.9 included, and tools/ingame-guard-tests proves the limits.
 */
public final class CuiNumbers {

	/** Vanilla's world border sits at +-29,999,984; nothing selectable lies past this. */
	public static final double LIMIT = 30_000_000;

	/** Multi regions (`+s|<type>|<uuid>`) one server can keep on screen at once. */
	public static final int MAX_MULTI_REGIONS = 64;

	/** Polyhedron faces per region: a convex hull of MAX_ID (4096) vertices has under 2x that many. */
	public static final int MAX_FACES = 8_192;

	private CuiNumbers() {
	}

	/** An int field: parsed through a float like WorldEditCUI does ("12.0" is valid), then clamped. */
	public static int parseInt(String s) {
		float f = Float.parseFloat(s);
		if (Float.isNaN(f)) {
			throw new NumberFormatException("NaN");
		}
		return (int) Math.max(-LIMIT, Math.min(LIMIT, f));
	}

	/** A coordinate, radius or spacing: NaN drops the line, anything else is clamped to the world. */
	public static double parseDouble(String s) {
		double d = Double.parseDouble(s);
		if (Double.isNaN(d)) {
			throw new NumberFormatException("NaN");
		}
		return Math.max(-LIMIT, Math.min(LIMIT, d));
	}

	/**
	 * Step between rings so a shape spanning {@code count} blocks draws at most
	 * {@code maxRings} of them. Takes a long: the span of two ints (or twice a radius)
	 * can pass Integer.MAX_VALUE, and an overflowed negative span used to mean step 1.
	 */
	public static int ringStep(long count, int maxRings) {
		if (count <= 0) {
			return 1;
		}
		return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, (count + maxRings - 1) / maxRings));
	}
}
