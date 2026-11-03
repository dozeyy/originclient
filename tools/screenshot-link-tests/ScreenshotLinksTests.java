import com.origin.client.client.mods.ScreenshotLinks;

import java.io.File;
import java.nio.file.Files;

/**
 * Pure-logic checks for ScreenshotLinks (src/mods/shared/.../mods/ScreenshotLinks.java), the
 * guard on the chat screenshot buttons (bug sweep 2, F-1): only a token Origin minted resolves,
 * a server-made insertion never does, and nothing outside the screenshots folder gets a button.
 * Run by run.py; exit 1 on any failure.
 */
public class ScreenshotLinksTests {
	static int failures = 0;

	static void check(boolean ok, String what) {
		if (!ok) {
			failures++;
			System.out.println("FAIL: " + what);
		} else {
			System.out.println("ok:   " + what);
		}
	}

	public static void main(String[] args) throws Exception {
		File game = Files.createTempDirectory("shotlinks").toFile();
		File shots = new File(game, "screenshots");
		check(shots.mkdirs(), "setup: screenshots folder");
		File png = new File(shots, "2026-11-03_12.00.00.png");
		check(png.createNewFile(), "setup: screenshot file");
		File outside = new File(game, "options.txt");
		check(outside.createNewFile(), "setup: file outside the folder");
		File sibling = new File(game, "screenshots2");
		check(sibling.mkdirs(), "setup: look-alike sibling folder");
		File siblingPng = new File(sibling, "x.png");
		check(siblingPng.createNewFile(), "setup: file in the sibling folder");

		// Origin's own buttons resolve to exactly what they were made for.
		String img = ScreenshotLinks.register(ScreenshotLinks.IMAGE, png, shots);
		String file = ScreenshotLinks.register(ScreenshotLinks.FILE, png, shots);
		String dir = ScreenshotLinks.register(ScreenshotLinks.FOLDER, shots, shots);
		check(img != null && img.startsWith(ScreenshotLinks.PREFIX), "image button gets a prefixed insertion");
		check(img != null && !img.equals(file), "every button gets its own token");
		ScreenshotLinks.Link l = ScreenshotLinks.resolve(img);
		check(l != null && ScreenshotLinks.IMAGE.equals(l.action) && l.target.equals(png.getAbsoluteFile()),
				"image token resolves to the screenshot");
		l = ScreenshotLinks.resolve(file);
		check(l != null && ScreenshotLinks.FILE.equals(l.action), "file token resolves to a file action");
		l = ScreenshotLinks.resolve(dir);
		check(l != null && ScreenshotLinks.FOLDER.equals(l.action) && l.target.equals(shots.getAbsoluteFile()),
				"folder token resolves to the screenshots folder");
		check(img != null && img.length() == ScreenshotLinks.PREFIX.length() + 32, "token is 128 random bits (32 hex)");

		// What a server can send: the old insertion format, made-up tokens, anything else.
		check(ScreenshotLinks.resolve(ScreenshotLinks.PREFIX + "folder") == null, "old-format 'folder' insertion is refused");
		check(ScreenshotLinks.resolve(ScreenshotLinks.PREFIX + "image") == null, "old-format 'image' insertion is refused");
		check(ScreenshotLinks.resolve(ScreenshotLinks.PREFIX + "file") == null, "old-format 'file' insertion is refused");
		check(ScreenshotLinks.resolve(ScreenshotLinks.PREFIX + "00000000000000000000000000000000") == null,
				"made-up token is refused");
		check(ScreenshotLinks.resolve(ScreenshotLinks.PREFIX) == null, "empty token is refused");
		check(ScreenshotLinks.resolve(null) == null, "no insertion is refused");
		check(ScreenshotLinks.resolve("hello") == null, "foreign insertion is refused");
		check(img == null || ScreenshotLinks.resolve(img.toUpperCase()) == null, "token match is exact");

		// Nothing outside the game's screenshots folder ever gets a button.
		check(ScreenshotLinks.register(ScreenshotLinks.IMAGE, outside, shots) == null, "file outside the folder: no button");
		check(ScreenshotLinks.register(ScreenshotLinks.FILE, new File(shots, ".." + File.separator + "options.txt"), shots) == null,
				"'..' out of the folder: no button");
		check(ScreenshotLinks.register(ScreenshotLinks.IMAGE, siblingPng, shots) == null,
				"look-alike sibling folder (screenshots2): no button");
		check(ScreenshotLinks.register(ScreenshotLinks.IMAGE, new File(shots, "missing.png"), shots) == null,
				"missing file: no button");
		check(ScreenshotLinks.register(ScreenshotLinks.IMAGE, shots, shots) == null, "a folder as image: no button");
		check(ScreenshotLinks.register(ScreenshotLinks.FOLDER, game, shots) == null, "Open Folder on the game folder: no button");
		check(ScreenshotLinks.register(ScreenshotLinks.FOLDER, sibling, shots) == null, "Open Folder on a sibling: no button");
		check(ScreenshotLinks.register(ScreenshotLinks.FOLDER, png, shots) == null, "Open Folder on a file: no button");
		check(ScreenshotLinks.register("run", png, shots) == null, "unknown action: no button");
		check(ScreenshotLinks.register(ScreenshotLinks.IMAGE, null, shots) == null, "null file: no button");
		check(ScreenshotLinks.register(ScreenshotLinks.IMAGE, png, null) == null, "null folder: no button");
		check(ScreenshotLinks.isShot(new File(shots, "." + File.separator + png.getName()), shots), "'./name' still counts as inside");

		// The table stays bounded; the newest buttons keep working.
		String newest = null;
		for (int i = 0; i < 1000; i++) {
			newest = ScreenshotLinks.register(ScreenshotLinks.IMAGE, png, shots);
		}
		check(ScreenshotLinks.resolve(newest) != null, "newest token still resolves after 1000 more");
		check(ScreenshotLinks.resolve(img) == null, "oldest tokens fall out of the bounded table");

		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
		System.exit(failures == 0 ? 0 : 1);
	}
}
