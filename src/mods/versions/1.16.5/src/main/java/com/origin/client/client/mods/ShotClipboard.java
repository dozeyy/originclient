package com.origin.client.client.mods;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// The Screenshot pop-up's clipboard work, done by the operating system's own
// tools because Minecraft runs Java's AWT headless (Main sets
// java.awt.headless=true, 1.21.1 source-verified), so Java's clipboard can't
// hold an image there. Windows -- what Origin ships for -- uses the built-in
// Windows PowerShell + .NET clipboard; macOS osascript; Linux wl-copy / xclip.
//
// Nothing leaves the PC: these only put the local file on the local clipboard.
// The path reaches PowerShell through an environment variable, never spliced
// into the script, so no file name can change what runs. Every call blocks
// (run it off the render thread) and returns whether it worked.
public final class ShotClipboard {
	private ShotClipboard() {
	}

	private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

	// The image as a bitmap (what Discord / Paint / Word paste) plus the original
	// PNG bytes (what browsers and Chromium apps prefer). copy=true keeps it on
	// the clipboard after PowerShell exits.
	private static final String PS_COPY_IMAGE = String.join("; ",
			"$ErrorActionPreference = 'Stop'",
			"Add-Type -AssemblyName System.Windows.Forms, System.Drawing",
			"$p = $env:ORIGIN_SHOT",
			"$img = [System.Drawing.Image]::FromFile($p)",
			"$data = New-Object System.Windows.Forms.DataObject",
			"$data.SetImage($img)",
			"$data.SetData('PNG', (New-Object System.IO.MemoryStream(,[System.IO.File]::ReadAllBytes($p))))",
			"[System.Windows.Forms.Clipboard]::SetDataObject($data, $true)",
			"$img.Dispose()");

	// The file itself, exactly like Ctrl+C on it in File Explorer.
	private static final String PS_COPY_FILE = String.join("; ",
			"$ErrorActionPreference = 'Stop'",
			"Add-Type -AssemblyName System.Windows.Forms",
			"$files = New-Object System.Collections.Specialized.StringCollection",
			"[void]$files.Add($env:ORIGIN_SHOT)",
			"[System.Windows.Forms.Clipboard]::SetFileDropList($files)");

	public static boolean copyImage(File f) {
		if (OS.contains("win")) {
			return powershell(PS_COPY_IMAGE, f);
		}
		if (OS.contains("mac")) {
			return run(java.util.Arrays.asList("osascript", "-e", "on run argv",
					"-e", "set the clipboard to (read (POSIX file (item 1 of argv)) as «class PNGf»)",
					"-e", "end run", f.getAbsolutePath()), null);
		}
		return run(java.util.Arrays.asList("wl-copy", "--type", "image/png"), f)
				|| run(java.util.Arrays.asList("xclip", "-selection", "clipboard", "-t", "image/png", "-i", f.getAbsolutePath()), null);
	}

	public static boolean copyFile(File f) {
		if (OS.contains("win")) {
			return powershell(PS_COPY_FILE, f);
		}
		if (OS.contains("mac")) {
			return run(java.util.Arrays.asList("osascript", "-e", "on run argv",
					"-e", "set the clipboard to (POSIX file (item 1 of argv))",
					"-e", "end run", f.getAbsolutePath()), null);
		}
		return false;   // no common Linux file-clipboard tool; the caller copies the path instead
	}

	private static boolean powershell(String script, File f) {
		List<String> cmd = new ArrayList<>(java.util.Arrays.asList("powershell.exe", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-STA", "-Command", script));
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.environment().put("ORIGIN_SHOT", f.getAbsolutePath());
		return finish(pb, null);
	}

	private static boolean run(List<String> cmd, File stdin) {
		return finish(new ProcessBuilder(cmd), stdin);
	}

	private static boolean finish(ProcessBuilder pb, File stdin) {
		try {
			pb.redirectErrorStream(true);
			// Java 8 has no Redirect.DISCARD (added in 9); this is what it does internally.
			pb.redirectOutput(ProcessBuilder.Redirect.to(new File(
					System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null")));
			if (stdin != null) {
				pb.redirectInput(stdin);
			}
			Process p = pb.start();
			if (!p.waitFor(15, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return false;
			}
			return p.exitValue() == 0;
		} catch (Exception e) {
			return false;   // tool missing, blocked, or interrupted
		}
	}
}
