import com.origin.client.client.shaders.IrisScreenHook;
import com.origin.client.client.shaders.ShaderCompat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.irisshaders.iris.gui.element.ShaderPackSelectionList;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// Bug run 11 (shaders). Modes, one JVM each (IrisScreenHook caches its Iris lookups per game):
//   iris    - Iris 1.8+'s screen: its link row opens Origin's browser, one "Download Shaders"
//             on screen at a time, kept through Iris's own re-inits; none under Hide GUI.
//   oldiris - an Iris without that row or flag: the corner button, as before.
//   compat  - 1.8.9's browser warning: known names at once, unknown zips off-thread, per name.
public class ShaderHookTests {
	private static int failures;
	private static int opened;

	public static void main(String[] args) throws Exception {
		String mode = args[0];
		if ("iris".equals(mode)) {
			iris();
		} else if ("oldiris".equals(mode)) {
			oldIris();
		} else {
			compat();
		}
		System.out.println(mode + ": " + (failures == 0 ? "PASS" : failures + " FAILED"));
		System.exit(failures == 0 ? 0 : 1);
	}

	private static final Component LABEL = Component.literal("Download Shaders");

	private static List<Object> attach(Screen screen) {
		final List<Object> widgets = new ArrayList<Object>();
		widgets.add(new Button(Component.literal("Done")));
		Supplier<AbstractWidget> corner = new Supplier<AbstractWidget>() {
			@Override
			public AbstractWidget get() {
				return new Button(LABEL);
			}
		};
		Runnable open = new Runnable() {
			@Override
			public void run() {
				opened++;
			}
		};
		IrisScreenHook.attach(screen, widgets, LABEL, corner, open);
		return widgets;
	}

	private static int corners(List<Object> widgets) {
		int n = 0;
		for (Object w : widgets) {
			if (w instanceof Button && LABEL.equals(((Button) w).getMessage())) {
				n++;
			}
		}
		return n;
	}

	private static void iris() {
		ShaderPackScreen screen = new ShaderPackScreen(true);
		List<Object> widgets = attach(screen);
		check("no packs: Iris's row is showing, so no corner button", corners(widgets) == 0);
		screen.list().clickDownloadRow();
		check("Iris's row opens Origin's browser", opened == 1);
		check("... not the modrinth.com link", ShaderPackSelectionList.linkOpened == 0);

		// Iris's own init() (Esc, options switch) makes a fresh list with a fresh link row.
		screen.irisInit(true);
		ScreenEvents.tick(screen);
		screen.list().clickDownloadRow();
		check("after Iris re-inits, its new row is routed again", opened == 2 && ShaderPackSelectionList.linkOpened == 0);

		// A pack arrives: Iris hides its row, so Origin's corner button is the way in.
		screen.irisInit(false);
		ScreenEvents.tick(screen);
		check("packs present: one corner button", corners(widgets) == 1);
		ScreenEvents.tick(screen);
		check("the corner button never stacks", corners(widgets) == 1);

		screen.setGuiHidden(true);
		ScreenEvents.tick(screen);
		check("Hide GUI: no corner button", corners(widgets) == 0);
		screen.setGuiHidden(false);
		ScreenEvents.tick(screen);
		check("Show GUI again: the corner button is back", corners(widgets) == 1);

		screen.irisInit(true);
		ScreenEvents.tick(screen);
		check("packs gone again: the corner button steps aside for Iris's row", corners(widgets) == 0);
		check("the screen's other widgets are untouched", widgets.size() == 1);
	}

	private static void oldIris() {
		Screen screen = new old.iris.ShaderPackScreen();
		List<Object> widgets = attach(screen);
		check("old Iris: the corner button shows", corners(widgets) == 1);
		ScreenEvents.tick(screen);
		check("old Iris: still one corner button after a tick", corners(widgets) == 1);
	}

	private static void compat() throws Exception {
		File dir = new File(System.getProperty("java.io.tmpdir"), "origin-shader-compat-" + System.nanoTime());
		dir.mkdirs();
		check("no pack selected: no warning", ShaderCompat.browserWarning(null, dir) == null);
		check("a known modern pack is answered at once",
				ShaderCompat.WARNING.equals(ShaderCompat.browserWarning("ComplementaryReimagined_r5.zip", dir)));
		zip(new File(dir, "MyModern.zip"), "#version 150\nvoid main(){}");
		zip(new File(dir, "MyLegacy.zip"), "#version 120\nvoid main(){}");
		check("an unknown pack isn't read on the caller's thread (null at first)",
				ShaderCompat.browserWarning("MyModern.zip", dir) == null);
		String modern = waitFor("MyModern.zip", dir);
		check("... and warns once the background read is done", ShaderCompat.WARNING.equals(modern));
		ShaderCompat.browserWarning("MyLegacy.zip", dir);
		Thread.sleep(500);
		check("a switch to a legacy pack shows no warning (per name, not the old pack's)",
				ShaderCompat.browserWarning("MyLegacy.zip", dir) == null);
		check("the modern pack's verdict is kept", ShaderCompat.WARNING.equals(ShaderCompat.browserWarning("MyModern.zip", dir)));
	}

	private static String waitFor(String name, File dir) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			String w = ShaderCompat.browserWarning(name, dir);
			if (w != null) {
				return w;
			}
			Thread.sleep(20);
		}
		return null;
	}

	private static void zip(File f, String program) throws Exception {
		ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
		try {
			z.putNextEntry(new ZipEntry("shaders/composite.fsh"));
			z.write(program.getBytes("ISO-8859-1"));
			z.closeEntry();
		} finally {
			z.close();
		}
	}

	private static void check(String what, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}
}
