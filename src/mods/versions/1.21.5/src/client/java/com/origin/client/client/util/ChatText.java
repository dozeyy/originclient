package com.origin.client.client.util;

// Friends DM / party chat text, cleaned EXACTLY like the friends service cleans it
// (services/cosmetics/src/social-shared.js cleanText): runs of line breaks/tabs become one
// space; control characters, Minecraft's section sign and the invisible zero-width /
// direction marks are removed; then JavaScript's trim(). The game shows a "sending" copy of
// a message (and /originpc echoes the line) and later matches the service's stored copy by
// its text: with raw text a pasted zero-width space made the two differ, so the line showed
// twice and the sending copy never cleared (KS-3). Keep this in step with cleanText.
// Java 8 source: the pre-1.20 modules carry byte-identical copies (tools/qol-tests runs both).
public final class ChatText {
	/** The service's TEXT_MAX, in UTF-16 units like Java and JavaScript strings. */
	public static final int MAX = 256;

	private ChatText() {
	}

	/** The text the service will store for this input, cut to MAX; "" when nothing is left. */
	public static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(raw.length());
		boolean inBreak = false;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '\r' || c == '\n' || c == '\t') {
				// the service folds a RUN of these into one space before removing anything,
				// so a zero-width mark between two breaks still leaves two spaces
				if (!inBreak) {
					sb.append(' ');
				}
				inBreak = true;
				continue;
			}
			inBreak = false;
			if (!dropped(c)) {
				sb.append(c);
			}
		}
		String t = trim(sb.toString());
		if (t.length() > MAX) {
			int end = MAX;
			if (Character.isHighSurrogate(t.charAt(end - 1))) {
				end--; // never split an emoji's surrogate pair
			}
			t = trim(t.substring(0, end));
		}
		return t;
	}

	// The service's removal class: [\u0000-\u001f\u007f-\u009f§​-‏‪-‮⁦-⁩﻿]
	static boolean dropped(char c) {
		return c <= 0x1F || (c >= 0x7F && c <= 0x9F) || c == '§'
				|| (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E)
				|| (c >= 0x2066 && c <= 0x2069) || c == 0xFEFF;
	}

	// JavaScript's String.trim(): Unicode space separators and line/paragraph separators
	// (the control characters it also strips are already gone). Java's trim()/strip() differ:
	// trim() keeps U+3000, strip() keeps the no-break spaces.
	private static String trim(String s) {
		int a = 0;
		int b = s.length();
		while (a < b && jsSpace(s.charAt(a))) {
			a++;
		}
		while (b > a && jsSpace(s.charAt(b - 1))) {
			b--;
		}
		return s.substring(a, b);
	}

	private static boolean jsSpace(char c) {
		int type = Character.getType(c);
		return type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR;
	}
}
