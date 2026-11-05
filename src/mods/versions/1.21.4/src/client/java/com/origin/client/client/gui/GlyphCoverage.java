package com.origin.client.client.gui;

/**
 * Which strings Origin's own Inter text can draw (LOC-1).
 *
 * The Inter faces Origin draws its menus with do not have every letter: the MSDF atlas is ASCII
 * plus a few symbols, and the TTF face (1.16.5, 1.8.9) has no Chinese/Japanese/Korean. A letter
 * the face lacks used to be drawn as an empty gap, so a German "Grüße", a Russian key name or a
 * Chinese item name came out blank. Callers ask {@link #covers} first and draw any string it
 * says no to with Minecraft's own font, which has every script.
 *
 * Pure Java, no Minecraft types: tools/glyph-coverage-tests checks it against the real atlas.
 * One copy in shared/; the pre-1.20 modules (outside shared sync) carry it byte-identical.
 */
public final class GlyphCoverage {
	private GlyphCoverage() {
	}

	/** Whether a face has a drawable glyph for one UTF-16 char. */
	public interface Has {
		boolean has(char c);
	}

	/**
	 * Typographic characters the atlas lacks, drawn as a look-alike it has (so "On — a backdrop"
	 * keeps its dash instead of a gap); any other char comes back unchanged.
	 */
	public static char substitute(char c) {
		switch (c) {
			case '—':   // em dash
			case '–':   // en dash
				return '-';
			case '‘':   // left single quote
			case '’':   // right single quote
				return '\'';
			case '“':   // left double quote
			case '”':   // right double quote
				return '"';
			case '＋':   // fullwidth plus
				return '+';
			case '✕':   // heavy multiply -> multiply sign (in the atlas)
				return '×';
			case '·':   // middle dot (the pack hub's "Installed · 2 updates") -> bullet (in the atlas)
				return '•';
			case ' ':   // non-breaking space
				return ' ';
			default:
				return c;
		}
	}

	/** {@code s} with every {@link #substitute} applied; the same object when nothing changes. */
	public static String normalize(String s) {
		int i = 0;
		while (i < s.length() && substitute(s.charAt(i)) == s.charAt(i)) {
			i++;
		}
		if (i == s.length()) {
			return s;
		}
		StringBuilder b = new StringBuilder(s.length());
		b.append(s, 0, i);
		for (; i < s.length(); i++) {
			b.append(substitute(s.charAt(i)));
		}
		return b.toString();
	}

	/**
	 * True when every char of {@code s} (after {@link #substitute}) has a glyph. Control chars
	 * (below U+0020) draw nothing in either font, so they never decide it. Null/empty = true.
	 */
	public static boolean covers(String s, Has has) {
		if (s == null) {
			return true;
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= 0x20 && !has.has(substitute(c))) {
				return false;
			}
		}
		return true;
	}
}
