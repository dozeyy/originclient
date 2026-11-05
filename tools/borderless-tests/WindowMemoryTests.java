import com.origin.client.client.WindowMemory;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** WindowMemory (JG-10): the game window comes back at its size and maximised state. Run by run.py. */
public final class WindowMemoryTests {
	private static int failures;

	static void eq(Object got, Object want, String what) {
		boolean same = want instanceof int[] && got instanceof int[] ? Arrays.equals((int[]) want, (int[]) got)
				: want == null ? got == null : want.equals(got);
		if (!same) {
			System.out.println("FAIL " + what + ": want " + str(want) + ", got " + str(got));
			failures++;
		}
	}

	static String str(Object o) {
		return o instanceof int[] ? Arrays.toString((int[]) o) : String.valueOf(o);
	}

	public static void main(String[] args) throws Exception {
		File dir = Files.createTempDirectory("windowmem").toFile();
		File file = new File(dir, WindowMemory.FILE);

		// first launch: nothing to restore; the launch size alone is not written
		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1280, 720), null, "first launch: nothing to restore");
		WindowMemory.seen(1280, 720, false);
		eq(file.exists(), false, "launch size alone writes nothing");
		// the player resizes, then maximises
		WindowMemory.seen(1600, 900, false);
		eq(file.exists(), true, "resize remembered");
		WindowMemory.seen(2560, 1400, true);   // maximised: the screen's size is not the window's own

		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1280, 720), new int[] {1600, 900, 1}, "next launch: 1600x900, maximised");
		WindowMemory.seen(1600, 900, false);   // un-maximised
		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1280, 720), new int[] {1600, 900, 0}, "un-maximise remembered");

		// maximised again: the normal size it returns to stays remembered
		WindowMemory.seen(1920, 1040, true);
		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1280, 720), new int[] {1600, 900, 1}, "maximise keeps the normal size");

		// the launcher's Resolution setting changed: it wins, and memory starts over from there
		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1920, 1080), null, "new launcher resolution wins");
		WindowMemory.seen(1000, 700, false);
		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1920, 1080), new int[] {1000, 700, 0}, "remembered under the new resolution");

		// minimised / tiny sizes are never remembered; a damaged file restores nothing
		WindowMemory.seen(0, 0, false);
		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1920, 1080), new int[] {1000, 700, 0}, "minimised size ignored");
		Files.write(file.toPath(), "width=abc\n".getBytes("UTF-8"));
		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1920, 1080), null, "damaged file: nothing restored");
		eq(WindowMemory.seen(800, 600, false), true, "and it is rewritten on the next change");
		WindowMemory.reset();
		eq(WindowMemory.start(dir, 1920, 1080), new int[] {800, 600, 0}, "rewritten file reads back");
		WindowMemory.reset();
		eq(WindowMemory.seen(800, 600, false), false, "seen before start: nothing");

		// placement: centred on its monitor, never bigger than it, title bar on screen
		eq(WindowMemory.placeOn(new int[] {0, 0, 1920, 1080}, 1600, 900), new int[] {160, 90, 1600, 900}, "centred");
		eq(WindowMemory.placeOn(new int[] {1920, 0, 1280, 1024}, 1600, 1200), new int[] {1920, 32, 1280, 992},
				"clamped to a smaller second monitor");

		System.out.println(failures == 0 ? "WindowMemoryTests: OK" : "WindowMemoryTests: " + failures + " failure(s)");
		System.exit(failures == 0 ? 0 : 1);
	}
}
