package com.origin.client.client.mods;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only way a click in chat can make Origin act on a file (bug sweep 2, F-1, security).
 *
 * The screenshot buttons ([Copy Image] [Copy File] [Open Folder], ScreenshotChat) used to put
 * the action in the chat insertion and the file path in the click event, and the click handler
 * trusted any chat text carrying that insertion. A server or another player can send exactly
 * that (insertion and click events are legal in server chat JSON), so a click could open any
 * program or path the server chose, or copy any file to the clipboard.
 *
 * Now each button's insertion is PREFIX + a random token minted here when Origin itself builds
 * the line from the game's own "Saved screenshot" result. The file and the action live only in
 * this in-memory table: a click acts only when its token is in it, and nothing a server sends
 * can add one or point one somewhere else. A token copied out of chat (shift-click inserts it)
 * still names only that one screenshot. register() also refuses anything outside the game's
 * own screenshots folder: a picture must sit inside it, Open Folder must be that folder.
 *
 * Pure Java on purpose, Java 8 level (1.8.9 and 1.16.5 compile it): no Minecraft types, so
 * tools/screenshot-link-tests/ tests it directly. Every live module carries a byte-identical
 * copy (shared/ syncs 1.20+; the pre-1.20 and 1.8.9 copies are kept identical by that test).
 */
public final class ScreenshotLinks {
	private ScreenshotLinks() {
	}

	/** Every Origin screenshot insertion starts with this; the rest is the token. */
	public static final String PREFIX = "originclient:screenshot:";

	public static final String IMAGE = "image";
	public static final String FILE = "file";
	public static final String FOLDER = "folder";

	/** One button Origin made: what it does, to which file. */
	public static final class Link {
		public final String action;
		public final File target;

		Link(String action, File target) {
			this.action = action;
			this.target = target;
		}
	}

	// Chat keeps 100 lines and each screenshot line carries up to 3 buttons: 300 covers every
	// button still on screen. Older tokens fall out (their click does nothing, like a gone file).
	static final int MAX_LINKS = 300;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<String, Link> LINKS = new LinkedHashMap<String, Link>(64, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Link> eldest) {
			return size() > MAX_LINKS;
		}
	};

	/**
	 * A new button's insertion, or null when the target is not allowed (then the caller shows
	 * no button). IMAGE / FILE: an existing file inside screenshotsDir. FOLDER: screenshotsDir
	 * itself. Called on whichever thread the game hands the screenshot result to.
	 */
	public static String register(String action, File target, File screenshotsDir) {
		boolean ok;
		if (FOLDER.equals(action)) {
			ok = isFolder(target, screenshotsDir);
		} else if (IMAGE.equals(action) || FILE.equals(action)) {
			ok = isShot(target, screenshotsDir);
		} else {
			ok = false;
		}
		if (!ok) {
			return null;
		}
		byte[] raw = new byte[16];
		RANDOM.nextBytes(raw);
		StringBuilder token = new StringBuilder(32);
		for (byte b : raw) {
			token.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		String id = token.toString();
		synchronized (LINKS) {
			LINKS.put(id, new Link(action, target.getAbsoluteFile()));
		}
		return PREFIX + id;
	}

	/** The button a clicked insertion names, or null for anything this session didn't mint. */
	public static Link resolve(String insertion) {
		if (insertion == null || !insertion.startsWith(PREFIX)) {
			return null;
		}
		synchronized (LINKS) {
			return LINKS.get(insertion.substring(PREFIX.length()));
		}
	}

	/** True when f is an existing file inside dir (any depth), after resolving "..", links and case. */
	public static boolean isShot(File f, File dir) {
		if (f == null || dir == null || !f.isFile()) {
			return false;
		}
		String d = canonical(dir);
		String p = canonical(f);
		return d != null && p != null && p.startsWith(d.endsWith(File.separator) ? d : d + File.separator);
	}

	/** True when f is dir itself (an existing directory). */
	public static boolean isFolder(File f, File dir) {
		if (f == null || dir == null || !f.isDirectory()) {
			return false;
		}
		String d = canonical(dir);
		return d != null && d.equals(canonical(f));
	}

	private static String canonical(File f) {
		try {
			return f.getCanonicalPath();
		} catch (IOException | SecurityException e) {
			return null;
		}
	}
}
