// Standalone proof for code-point-safe text input (U4.6). Pure logic, no Gradle, no Minecraft:
//   javac -d <out> src/mods/versions/1.21.10/src/client/java/com/origin/client/client/gui/OriginTextInput.java tools/text-input-tests/TextInputCheck.java
//   java -cp <out> TextInputCheck
// Run from the repo root. Also asserts the five CharacterEvent modules carry byte-identical
// copies of OriginTextInput.java (1.21.10, 1.21.11, 26.1, 26.2, 26.3).
import com.origin.client.client.gui.OriginTextInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class TextInputCheck {
	static int fails = 0;

	static void eq(String what, Object want, Object got) {
		boolean ok = want == null ? got == null : want.equals(got);
		if (!ok) {
			fails++;
		}
		System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : "  want=" + want + " got=" + got));
	}

	static final int GRIN = 0x1F600;      // U+1F600, a surrogate pair in UTF-16
	static final int FAMILY_ZWJ = 0x200D; // BMP joiner

	public static void main(String[] a) throws Exception {
		// the bug: the old (char) cast loses the high half and leaves private-use junk
		eq("old (char) cast corrupts", false, String.valueOf((char) GRIN).equals(new String(Character.toChars(GRIN))));

		// insertion keeps the pair
		String s = OriginTextInput.append("", GRIN);
		eq("append emoji length (UTF-16 units)", 2, s.length());
		eq("append emoji is one code point", 1, s.codePointCount(0, s.length()));
		eq("append emoji round-trips", GRIN, s.codePointAt(0));
		s = OriginTextInput.append(OriginTextInput.append("a", GRIN), 'b');
		eq("mixed append", "a" + new String(Character.toChars(GRIN)) + "b", s);

		// backspace removes the whole pair, never half
		String t = "a" + new String(Character.toChars(GRIN));
		String t1 = OriginTextInput.dropLast(t);
		eq("backspace removes whole pair", "a", t1);
		eq("backspace leaves no lone surrogate", false, t1.length() > 0 && Character.isSurrogate(t1.charAt(t1.length() - 1)));
		eq("backspace plain char", "ab", OriginTextInput.dropLast("abc"));
		eq("backspace empty", "", OriginTextInput.dropLast(""));
		eq("backspace single emoji -> empty", "", OriginTextInput.dropLast(new String(Character.toChars(GRIN))));
		// two emoji in a row: two backspaces, each takes one whole emoji
		String two = OriginTextInput.append(OriginTextInput.append("", GRIN), 0x1F680);
		eq("two emoji length", 4, two.length());
		eq("first backspace keeps first emoji", new String(Character.toChars(GRIN)), OriginTextInput.dropLast(two));
		eq("second backspace empties", "", OriginTextInput.dropLast(OriginTextInput.dropLast(two)));
		// a pre-existing lone surrogate (corrupt saved value) still deletes one unit, no exception
		eq("lone surrogate drops one unit", "x", OriginTextInput.dropLast("x\uD83D"));
		// ZWJ sequences are several code points: backspace peels one code point at a time (like vanilla)
		String zwj = new String(Character.toChars(0x1F468)) + (char) FAMILY_ZWJ + new String(Character.toChars(0x1F469));
		eq("zwj sequence peels one code point", new String(Character.toChars(0x1F468)) + (char) FAMILY_ZWJ, OriginTextInput.dropLast(zwj));

		// what may be typed
		eq("typable letter", true, OriginTextInput.typable('a'));
		eq("typable space", true, OriginTextInput.typable(' '));
		eq("typable emoji", true, OriginTextInput.typable(GRIN));
		eq("typable ctrl", false, OriginTextInput.typable(10));
		eq("typable DEL", false, OriginTextInput.typable(127));
		eq("typable lone surrogate", false, OriginTextInput.typable(0xD83D));
		eq("typable invalid", false, OriginTextInput.typable(0x110000));
		eq("typable negative", false, OriginTextInput.typable(-1));

		// clip never splits a pair
		String e = new String(Character.toChars(GRIN));
		eq("clip no-op", "abc", OriginTextInput.clip("abc", 5));
		eq("clip plain", "ab", OriginTextInput.clip("abc", 2));
		eq("clip at pair boundary keeps pair", "a" + e, OriginTextInput.clip("a" + e + "b", 3));
		eq("clip mid-pair backs off", "a", OriginTextInput.clip("a" + e + "b", 2));
		eq("clip zero", "", OriginTextInput.clip("abc", 0));
		eq("clip mid-pair at 1", "", OriginTextInput.clip(e, 1));

		// a type-then-delete session on a capped field, the way the screens drive it
		String field = "";
		int cap = 5; // screens check length() < cap before appending, so an emoji may overshoot by one unit; never split
		for (int cp : new int[] {'h', 'i', GRIN, 0x1F680, 'z'}) {
			if (field.length() < cap) {
				field = OriginTextInput.append(field, cp);
			}
		}
		eq("capped field content", "hi" + e + new String(Character.toChars(0x1F680)), field);
		field = OriginTextInput.dropLast(field);
		field = OriginTextInput.dropLast(field);
		eq("two backspaces leave 'hi'", "hi", field);
		// and it all stays valid UTF-16 (what the saved JSON and the font see)
		eq("always valid utf-16", true, java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(field));

		// the five module copies are byte-identical
		String rel = "src/client/java/com/origin/client/client/gui/OriginTextInput.java";
		byte[] base = Files.readAllBytes(Path.of("src/mods/versions/1.21.10", rel));
		for (String m : new String[] {"1.21.11", "26.1", "26.2", "26.3"}) {
			eq("copy identical in " + m, true, Arrays.equals(base, Files.readAllBytes(Path.of("src/mods/versions", m, rel))));
		}

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
