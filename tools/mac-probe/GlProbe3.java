import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

// Mac boot probe, the control for 1.21.1: no Minecraft, no mods, only the game's own LWJGL 3 and the
// window hints Minecraft uses. If THIS has no usable GL context, a game failure at the same point is
// the runner (or macOS itself), not Origin. It also shows which GL versions macOS really hands out.
public class GlProbe3 {
	public static void main(String[] args) {
		System.out.println("glprobe: os.arch=" + System.getProperty("os.arch") + " java=" + System.getProperty("java.version"));
		GLFW.glfwSetErrorCallback(GLFWErrorCallback.createPrint(System.out));
		if (!GLFW.glfwInit()) {
			System.out.println("glprobe: RESULT glfwInit failed");
			return;
		}
		for (int[] want : new int[][] {{4, 6}, {4, 5}, {4, 1}, {3, 2}}) {
			GLFW.glfwDefaultWindowHints();
			GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, want[0]);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, want[1]);
			GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
			GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
			long w = GLFW.glfwCreateWindow(640, 360, "GL probe", 0L, 0L);
			String label = "glprobe: asked " + want[0] + "." + want[1] + " core: ";
			if (w == 0L) {
				System.out.println(label + "NO CONTEXT");
				continue;
			}
			GLFW.glfwMakeContextCurrent(w);
			GL.createCapabilities();
			// A clear and a read-back proves drawing does something, not only that a context exists.
			GL11.glClearColor(0.25f, 0.5f, 0.75f, 1f);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			ByteBuffer px = BufferUtils.createByteBuffer(4);
			GL11.glReadPixels(1, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
			System.out.println(label + "version=" + GL11.glGetString(GL11.GL_VERSION) + " renderer=" + GL11.glGetString(GL11.GL_RENDERER)
					+ " vendor=" + GL11.glGetString(GL11.GL_VENDOR) + " readback=" + (px.get(0) & 255) + "," + (px.get(1) & 255) + "," + (px.get(2) & 255)
					+ " glError=" + GL11.glGetError());
			GLFW.glfwSwapBuffers(w);
			GLFW.glfwMakeContextCurrent(0L);
			GLFW.glfwDestroyWindow(w);
		}
		GLFW.glfwTerminate();
		System.out.println("glprobe: RESULT done");
	}
}
