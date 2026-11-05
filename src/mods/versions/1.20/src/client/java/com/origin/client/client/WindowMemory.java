package com.origin.client.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The game window remembers its size and maximised state (JG-10): resize or maximise the game, and
 * the next launch opens it that way again. Pure Java 8, no Minecraft or window-system types:
 * GeneralSettings in each module reads and moves the real window (GLFW, SDL3 on 26.3, LWJGL 2 on
 * 1.8.9) and asks this class what to do; tools/borderless-tests runs it. shared/ syncs it into the
 * Fabric modules, the pre-1.20 modules and 1.8.9 (.no-shared-sync) carry byte-identical copies.
 *
 * Per version + account: the file lives in the game folder's config/ (each account has its own
 * folder per version). It is deliberately NOT an originclient*.json: Settings Sync copies those
 * between every version and account, and a window size belongs to one game folder.
 *
 * The launcher's own Resolution setting stays the player's control: the window size the game
 * opened at (the launch size) is saved alongside, and a launch at a different size means the player
 * changed that setting, so it wins and the memory starts over. Borderless and F11 fullscreen own
 * the window while on: nothing is restored under them and nothing is remembered from them (the
 * caller only reports a plain window).
 */
public final class WindowMemory {
	private WindowMemory() {
	}

	/** The file, in Origin's config folder (the game folder's config/). */
	public static final String FILE = "originclient-window.txt";
	/** Smallest size remembered or restored (a minimised or half-created window reports less). */
	static final int MIN_W = 320, MIN_H = 240;

	private static File file;
	private static int launchW, launchH;
	// what the file says now (w/h = the normal, un-maximised size)
	private static int savedW, savedH;
	private static boolean savedMax, saved;

	/**
	 * First tick: the window's size right now is the launch size. Returns {w, h, maximised 0/1} to
	 * restore, or null when there is nothing to restore (no file, another launch size, a bad file).
	 */
	public static synchronized int[] start(File configDir, int w, int h) {
		file = new File(configDir, FILE);
		launchW = w;
		launchH = h;
		saved = false;
		Properties p = new Properties();
		try (InputStream in = new FileInputStream(file)) {
			p.load(in);
		} catch (Throwable t) {
			return null;   // no memory yet
		}
		try {
			int lw = Integer.parseInt(p.getProperty("launchWidth", "").trim());
			int lh = Integer.parseInt(p.getProperty("launchHeight", "").trim());
			int sw = Integer.parseInt(p.getProperty("width", "").trim());
			int sh = Integer.parseInt(p.getProperty("height", "").trim());
			boolean max = Boolean.parseBoolean(p.getProperty("maximised", "false").trim());
			if (lw != w || lh != h || sw < MIN_W || sh < MIN_H) {
				return null;   // the launcher's Resolution changed (it wins), or a damaged file
			}
			savedW = sw;
			savedH = sh;
			savedMax = max;
			saved = true;
			return new int[] {sw, sh, max ? 1 : 0};
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * A plain window (not borderless, not fullscreen, not minimised) seen now: w x h is its size,
	 * which while maximised is the screen's and not kept (the size it un-maximises to stays the one
	 * remembered). Writes the file only when something changed. False if start() never ran.
	 */
	public static synchronized boolean seen(int w, int h, boolean maximised) {
		if (file == null) {
			return false;
		}
		int nw, nh;
		if (maximised) {
			nw = saved ? savedW : launchW;
			nh = saved ? savedH : launchH;
		} else {
			if (w < MIN_W || h < MIN_H) {
				return true;
			}
			nw = w;
			nh = h;
		}
		if (saved && nw == savedW && nh == savedH && maximised == savedMax) {
			return true;
		}
		if (!saved && !maximised && nw == launchW && nh == launchH) {
			return true;   // still the launch size: nothing to remember yet
		}
		savedW = nw;
		savedH = nh;
		savedMax = maximised;
		saved = true;
		write();
		return true;
	}

	// Written whole to a temp file, then moved over the old one: a crash mid-write never leaves half a file.
	private static void write() {
		Properties p = new Properties();
		p.setProperty("launchWidth", Integer.toString(launchW));
		p.setProperty("launchHeight", Integer.toString(launchH));
		p.setProperty("width", Integer.toString(savedW));
		p.setProperty("height", Integer.toString(savedH));
		p.setProperty("maximised", Boolean.toString(savedMax));
		try {
			File dir = file.getParentFile();
			if (dir != null) {
				dir.mkdirs();
			}
			File tmp = new File(file.getPath() + ".tmp");
			try (OutputStream out = new FileOutputStream(tmp)) {
				p.store(out, "Origin: the game window's size for this version and account (not synced)");
			}
			try {
				Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Throwable atomicFailed) {
				Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable t) {
			// fail-soft: the window just isn't remembered this time
		}
	}

	/** Where a w x h window goes on a monitor {x, y, w, h}: centred, its title bar on screen. {x, y, w, h}. */
	public static int[] placeOn(int[] monitor, int w, int h) {
		int nw = Math.max(MIN_W, Math.min(w, monitor[2]));
		int nh = Math.max(MIN_H, Math.min(h, monitor[3] - TITLE_ROOM));
		int x = monitor[0] + Math.max(0, (monitor[2] - nw) / 2);
		int y = monitor[1] + Math.max(TITLE_ROOM, (monitor[3] - nh) / 2);
		return new int[] {x, y, nw, nh};
	}

	/** Room left above the window for its title bar (window positions are the client area's). */
	static final int TITLE_ROOM = 32;

	/** Test seam: forget everything. */
	public static synchronized void reset() {
		file = null;
		launchW = launchH = savedW = savedH = 0;
		savedMax = saved = false;
	}
}
