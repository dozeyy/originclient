import com.origin.client.client.gui.GlyphCoverage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Run by run.py with the real Inter MSDF atlas' glyph list (one UTF-8 line, one char per glyph). */
public final class GlyphCoverageTests {
	private static int failures = 0;

	private static void check(boolean ok, String what) {
		if (!ok) {
			failures++;
			System.out.println("FAIL " + what);
		}
	}

	public static void main(String[] args) throws Exception {
		final String atlas = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
		GlyphCoverage.Has has = c -> atlas.indexOf(c) >= 0;

		// English UI text stays Inter (no visible change for it).
		check(GlyphCoverage.covers("Singleplayer", has), "plain English is covered");
		check(GlyphCoverage.covers("Minecraft 1.21.1 - Origin (x2) 100%", has), "digits and punctuation are covered");
		check(GlyphCoverage.covers("On — a solid backdrop…", has), "em dash (substituted) + ellipsis are covered");
		check(GlyphCoverage.covers("“Quoted” ‘it’s’", has), "curly quotes (substituted) are covered");
		check(GlyphCoverage.covers("Installed · 2 updates", has), "middle dot (drawn as a bullet) is covered");
		check(GlyphCoverage.covers("★ → ✓ × ✕", has), "atlas symbols are covered");
		check(GlyphCoverage.covers("line\tone\n", has), "control chars never decide it");
		check(GlyphCoverage.covers("", has) && GlyphCoverage.covers(null, has), "empty / null are covered");

		// The LOC-1 cases: any of these must go to Minecraft's font, never blank gaps.
		check(!GlyphCoverage.covers("Grüße", has), "German umlaut/eszett is NOT covered");
		check(!GlyphCoverage.covers("ça va", has), "French cedilla is NOT covered");
		check(!GlyphCoverage.covers("Não distribuir", has), "Portuguese tilde is NOT covered");
		check(!GlyphCoverage.covers("Левый Shift", has), "Russian key name is NOT covered");
		check(!GlyphCoverage.covers("模组", has), "Chinese is NOT covered");
		check(!GlyphCoverage.covers("Mody Łódź", has), "Polish is NOT covered");
		check(!GlyphCoverage.covers("hi 😀", has), "an emoji (surrogate pair) is NOT covered");

		// normalize: substitutes, and hands back the same object when nothing changes.
		String plain = "Hello world";
		check(GlyphCoverage.normalize(plain) == plain, "normalize returns the same string when nothing changes");
		check("a - b 'x' \"y\" + × • c".equals(
						GlyphCoverage.normalize("a — b ‘x’ “y” ＋ ✕ · c")),
				"normalize maps every typographic look-alike");

		// Every substitution lands on a glyph the atlas really has.
		for (int c = 0; c <= 0xFFFF; c++) {
			char s = GlyphCoverage.substitute((char) c);
			if (s != (char) c) {
				check(atlas.indexOf(s) >= 0, "substitute(U+" + Integer.toHexString(c) + ") is in the atlas");
			}
		}

		System.out.println(failures == 0 ? "glyph coverage: all checks passed" : failures + " glyph coverage check(s) failed");
		System.exit(failures == 0 ? 0 : 1);
	}
}
