import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

// Mac boot probe, the control for 1.8.9: no Minecraft, no mods, only 1.8.9's own LWJGL 2 + JInput under
// x64 Java. Opens the window the way Minecraft does (Display.create with a 24-bit depth pixel format).
// If THIS has no usable GL, a game failure at the same point is the runner or macOS, not Origin.
// Java 8 source: compile with the Temurin 8 JDK.
public class GlProbe2 {
	public static void main(String[] args) throws Exception {
		System.out.println("glprobe2: os.arch=" + System.getProperty("os.arch") + " java=" + System.getProperty("java.version"));
		try {
			Display.setDisplayMode(new DisplayMode(640, 360));
			Display.setTitle("GL probe");
			Display.create(new PixelFormat().withDepthBits(24));
			System.out.println("glprobe2: lwjgl=" + org.lwjgl.Sys.getVersion() + " version=" + GL11.glGetString(GL11.GL_VERSION)
					+ " renderer=" + GL11.glGetString(GL11.GL_RENDERER) + " vendor=" + GL11.glGetString(GL11.GL_VENDOR));
			GL11.glClearColor(0.25f, 0.5f, 0.75f, 1f);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
			GL11.glReadPixels(1, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
			System.out.println("glprobe2: readback=" + (px.get(0) & 255) + "," + (px.get(1) & 255) + "," + (px.get(2) & 255) + " glError=" + GL11.glGetError());
			Display.update();
			Display.destroy();
		} catch (Throwable t) {
			System.out.println("glprobe2: LWJGL FAILED: " + t);
		}
		try {
			// JInput: does its x64 native load under Rosetta at all, and does it see a mouse on this box?
			Controller[] cs = ControllerEnvironment.getDefaultEnvironment().getControllers();
			System.out.println("glprobe2: jinput env supported=" + ControllerEnvironment.getDefaultEnvironment().isSupported() + " controllers=" + cs.length);
			for (Controller c : cs) {
				System.out.println("glprobe2: jinput controller " + c.getName() + " type=" + c.getType());
			}
		} catch (Throwable t) {
			System.out.println("glprobe2: JINPUT FAILED: " + t);
		}
		try {
			// The exact class Origin's RawMouseInput builds.
			Class<?> raw = Class.forName("net.java.games.input.RawInputEnvironmentPlugin");
			Object env = raw.newInstance();
			System.out.println("glprobe2: RawInputEnvironmentPlugin isSupported=" + raw.getMethod("isSupported").invoke(env));
		} catch (Throwable t) {
			System.out.println("glprobe2: RawInputEnvironmentPlugin FAILED: " + t);
		}
		System.out.println("glprobe2: RESULT done");
		System.exit(0);
	}
}
