package com.origin.fwvtest;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Local proof only (env FWV_SHOTS = a shots folder; CI never sets it): a timed series of the game's
 * own screenshots once the test level is up, so a rotating kinetic block shows two different angles,
 * then one fullscreen toggle (the same calls Minecraft's F11 handler makes, inside this game, so no
 * key is ever sent to any window) with a shot after the resize, then the toggle back.
 */
final class ProofShots {
	private static final String DIR = System.getenv("FWV_SHOTS");

	private int tick = -1;

	static boolean enabled() {
		return DIR != null && !DIR.isBlank();
	}

	/** Called every client tick after the cogs were queued. */
	void tick(Minecraft mc) {
		if (!enabled() || tick == Integer.MAX_VALUE || mc.level == null) {
			return;
		}
		tick++;
		// Frame rate in the windowed stretches (Minecraft's own once-a-second counter), for comparing backends.
		if (tick == 40 || tick == 60 || tick == 80 || tick == 100 || tick == 200 || tick == 220) {
			FwvTestMod.LOGGER.info("[fwv-test] fps {} at tick {} ({}x{})", mc.getFps(), tick, mc.getWindow().getWidth(), mc.getWindow().getHeight());
		}
		switch (tick) {
			case 60 -> grab(mc, "fwv_a_rotating");
			case 100 -> grab(mc, "fwv_b_rotating");
			case 110 -> toggleFullscreen(mc);
			case 170 -> grab(mc, "fwv_c_after_resize");
			case 180 -> toggleFullscreen(mc);
			case 230 -> {
				grab(mc, "fwv_d_restored");
				tick = Integer.MAX_VALUE;
			}
			default -> {
			}
		}
	}

	private static void toggleFullscreen(Minecraft mc) {
		// KeyboardHandler's F11 branch, minus options.save() (the run folder's options stay as they were).
		mc.getWindow().toggleFullScreen();
		mc.options.fullscreen().set(mc.getWindow().isFullscreen());
		FwvTestMod.LOGGER.info("[fwv-test] fullscreen toggled: fullscreen={} size={}x{}", mc.getWindow().isFullscreen(),
				mc.getWindow().getWidth(), mc.getWindow().getHeight());
	}

	private static void grab(Minecraft mc, String name) {
		String file = name + "_" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + ".png";
		// Screenshot.grab makes only the last level ("screenshots"); a fresh shots folder needs its parent.
		new File(DIR, "screenshots").mkdirs();
		Screenshot.grab(new File(DIR), file, mc.getMainRenderTarget(), c -> {
		});
		FwvTestMod.LOGGER.info("[fwv-test] captured {}", file);
	}
}
