package com.origin.client.client.gui;

/**
 * Code-point-safe text-field editing for the Origin screens.
 *
 * <p>From 1.21.9 the game hands a screen one {@code CharacterEvent} per typed
 * character, carrying a full Unicode code point. An emoji is above U+FFFF, so
 * {@code (char) event.codepoint()} silently drops the high half and leaves a
 * private-use junk character. Every text field therefore appends through
 * {@link #append} and deletes through {@link #dropLast}, which treat a
 * surrogate pair as ONE character (so Backspace removes the whole emoji, never
 * half of it), and clamps through {@link #clip}, which never cuts a pair in two.
 *
 * <p>Before 1.21.9 the game delivers an emoji as TWO consecutive {@code charTyped(char)} calls
 * (high half, then low half) and the Origin fields insert them as plain chars; {@link #room}
 * is the length-cap test for that path: it lets the low half complete a pair even when the
 * field is exactly at its cap, so a cap can never strand a lone high surrogate.
 *
 * <p>Pure Java, no Minecraft imports: tools/text-input-tests/TextInputCheck.java
 * compiles this file directly. The five CharacterEvent modules (1.21.10,
 * 1.21.11, 26.1, 26.2, 26.3) carry identical copies; the check asserts that.
 * Length caps elsewhere still count UTF-16 units (an emoji is 2), unchanged.
 */
public final class OriginTextInput {
	private OriginTextInput() {
	}

	/** True for a code point a text field should accept: not a control char, not DEL, valid, not a lone surrogate. */
	public static boolean typable(int cp) {
		return cp >= 32 && cp != 127 && Character.isValidCodePoint(cp)
				&& (cp < Character.MIN_SURROGATE || cp > Character.MAX_SURROGATE);
	}

	/**
	 * Cap test for char-at-a-time input (pre-1.21.9): true when {@code s} is under {@code max} UTF-16
	 * units, or when {@code c} is the low half that completes a high half already at the end of {@code s}.
	 */
	public static boolean room(String s, char c, int max) {
		return s.length() < max
				|| (Character.isLowSurrogate(c) && !s.isEmpty() && Character.isHighSurrogate(s.charAt(s.length() - 1)));
	}

	/** {@code s} with the code point appended (a surrogate pair for anything above U+FFFF). */
	public static String append(String s, int cp) {
		return s + new String(Character.toChars(cp));
	}

	/** {@code s} without its last code point: a whole surrogate pair goes at once. */
	public static String dropLast(String s) {
		if (s.isEmpty()) {
			return s;
		}
		return s.substring(0, s.offsetByCodePoints(s.length(), -1));
	}

	/** {@code s} cut to at most {@code max} UTF-16 units, backing off one unit rather than splitting a pair. */
	public static String clip(String s, int max) {
		if (s.length() <= max) {
			return s;
		}
		int end = Math.max(0, max);
		if (end > 0 && end < s.length()
				&& Character.isHighSurrogate(s.charAt(end - 1)) && Character.isLowSurrogate(s.charAt(end))) {
			end--;
		}
		return s.substring(0, end);
	}
}
