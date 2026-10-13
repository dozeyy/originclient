package com.origin.client.client.dev;

import com.origin.client.client.OriginClientMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * DEV-ONLY cubemap grabber, used to author Origin's own title-screen panorama.
 *
 * WHY THIS EXISTS AT ALL: the six panorama faces have to be rendered by the game
 * itself, with a shader pack active, because Iris cannot run on the title screen
 * (there is no world there). So the shader look must be BAKED into the images at
 * capture time -- this is the tool that bakes it.
 *
 * It is a thin trigger, not an implementation: Minecraft already ships the whole
 * cubemap routine as the public Minecraft.grabPanoramixScreenshot(File), which
 * renders 6 faces at 4096x4096 (yaw stepped 0/90/180/270 plus a straight-up and
 * straight-down pass), with the block outline disabled and
 * GameRenderer.setPanoramicScreenshotParameters() set from the camera's forward
 * vector. Vanilla only ever calls it behind SharedConstants.DEBUG_PANORAMA_SCREENSHOT,
 * which ships false, so there is no key combo to press -- hence this hook.
 * Using the game's own routine (rather than six hand-aimed screenshots) is what
 * guarantees the faces line up seamlessly at the cube edges.
 *
 * OFF UNLESS EXPLICITLY ASKED FOR. register() is only called when
 * -Dorigin.panoramacapture=true is on the command line, so no listener, no key
 * poll, and no class load happens for a player. Capture with F8, in a world.
 *
 * Output: run/panorama-capture/panorama_{0..5}.png. Those are the raw plates --
 * they still get downscaled to 1024 and graded before they ship as assets.
 */
public final class PanoramaCapture {
	/** GLFW key that fires a capture. F8 is unbound in vanilla and by Origin. */
	private static final int CAPTURE_KEY = GLFW.GLFW_KEY_F8;

	/** Edge trigger -- a capture is expensive, so it fires on press, not while held. */
	private static boolean wasDown = false;

	private PanoramaCapture() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PanoramaCapture::onEndTick);
		com.origin.client.OriginClient.LOGGER.info(
				"Origin DEV: panorama capture armed -- press F8 in a world to grab a cubemap");
	}

	private static void onEndTick(Minecraft mc) {
		boolean down = OriginClientMod.isRawKeyDown(CAPTURE_KEY);
		// Needs a player: grabPanoramixScreenshot reads the player's rotation to
		// derive the six facings, and NPEs without one.
		if (down && !wasDown && mc.player != null) {
			capture(mc);
		}
		wasDown = down;
	}

	private static void capture(Minecraft mc) {
		try {
			File dir = new File(mc.gameDirectory, "panorama-capture");
			if (!dir.exists() && !dir.mkdirs()) {
				com.origin.client.OriginClient.LOGGER.error(
						"Origin DEV: could not create {}", dir.getAbsolutePath());
				return;
			}
			com.origin.client.OriginClient.LOGGER.info(
					"Origin DEV: capturing panorama into {} (6 x 4096px, this stalls the client)",
					dir.getAbsolutePath());
			// Returns the chat component vanilla would have shown; log it so the
			// run log records the real outcome rather than an assumed one.
			var result = mc.grabPanoramixScreenshot(dir);
			com.origin.client.OriginClient.LOGGER.info(
					"Origin DEV: panorama capture finished -- {}", result.getString());
		} catch (Throwable t) {
			// Dev tool: never take the client down with it.
			com.origin.client.OriginClient.LOGGER.error("Origin DEV: panorama capture failed", t);
		}
	}
}
