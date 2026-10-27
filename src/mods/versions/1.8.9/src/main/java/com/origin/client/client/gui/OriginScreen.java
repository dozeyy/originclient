package com.origin.client.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * 1.8.9-only base for every Origin screen: a GuiScreen that speaks the MODERN
 * (1.16.5 Screen) event shapes, so screen code ports with its input logic intact.
 *
 * WHY: 1.8.9's GuiScreen has int mouse coordinates, void handlers, no release /
 * drag deltas / scroll callbacks and one keyTyped(char, key) for both keys and
 * characters. Origin's screens (mod menu, every OriginWindow, HUD editor,
 * pickers) were written against Screen's boolean handlers with double mouse
 * coordinates -- the OriginDeck kit maps the mouse onto PHYSICAL pixels, where
 * int GUI coordinates would lose up to guiScale-1 px of precision. This class
 * translates once:
 *
 *   GuiScreen (1.8.9)                       -> modern hook here
 *   initGui()                               -> init()
 *   drawScreen(mx, my, pt)                  -> render(Gfx, mx, my, pt)
 *   updateScreen()                          -> tick()
 *   onGuiClosed()                           -> removed()
 *   doesGuiPauseGame()                      -> isPauseScreen()
 *   mouse press / release / move-while-down -> mouseClicked / mouseReleased /
 *                                              mouseDragged(mx, my, btn, dx, dy)
 *   wheel (Mouse.getEventDWheel)            -> mouseScrolled(mx, my, notches)
 *   keyTyped(char, key)                     -> keyPressed(key, 0, mods), then
 *                                              charTyped(char, mods) if unhandled
 *   Esc (when shouldCloseOnEsc)             -> onClose()
 *
 * Mouse coordinates are DOUBLE GUI px read straight from the LWJGL event
 * (Mouse.getEventX/Y), as the modern Screen receives them. KEY CODES are LWJGL2
 * (Keyboard.KEY_*), never GLFW: compare with Keyboard constants. `modifiers` are
 * GLFW-style bits (MOD_SHIFT 1, MOD_CONTROL 2, MOD_ALT 4) so modifier checks port
 * unchanged; GuiScreen.isShiftKeyDown()/isCtrlKeyDown() also work.
 *
 * A modern handler returning false falls through to vanilla (buttonList clicks,
 * Esc). render's default draws the vanilla buttonList/labelList, so a subclass
 * that calls super.render(...) gets its GuiButtons like Screen's children.
 * Every bridge catches Throwable: a broken Origin screen closes to vanilla
 * instead of crashing the game (mandate 4).
 */
public abstract class OriginScreen extends GuiScreen {
	public static final int MOD_SHIFT = 1, MOD_CONTROL = 2, MOD_ALT = 4;

	/** Screen.title. */
	protected final IChatComponent title;
	/** Screen.minecraft / Screen.font aliases (set before every init). */
	protected Minecraft minecraft;
	protected FontRenderer font;

	private int dragButton = -1;
	private double lastDragX, lastDragY;

	protected OriginScreen(IChatComponent title) {
		this.title = title == null ? new ChatComponentText("") : title;
	}

	protected OriginScreen(String title) {
		this(new ChatComponentText(title == null ? "" : title));
	}

	public IChatComponent getTitle() {
		return title;
	}

	// ---- modern hooks (override these) ----

	/** Screen.init(): buttonList is already cleared, width/height are set. */
	protected void init() {
	}

	/** Screen.render: default draws the vanilla buttonList + labelList. */
	public void render(Gfx g, int mouseX, int mouseY, float partialTick) {
		super.drawScreen(mouseX, mouseY, partialTick);
	}

	/** Screen.tick(). */
	public void tick() {
	}

	/** Screen.removed(): the screen is being replaced or closed. */
	public void removed() {
	}

	/** Screen.onClose(): Esc / programmatic close. Default leaves to the game. */
	public void onClose() {
		mc.displayGuiScreen(null);
	}

	public boolean shouldCloseOnEsc() {
		return true;
	}

	public boolean isPauseScreen() {
		return true;
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return false;
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return false;
	}

	/** `delta` in wheel notches, positive = away from the user (scroll up), like GLFW. */
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return false;
	}

	/** `keyCode` is LWJGL2 (Keyboard.KEY_*); `scanCode` is always 0 on 1.8.9. */
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return false;
	}

	public boolean charTyped(char chr, int modifiers) {
		return false;
	}

	// ---- helpers ----

	/** The current mouse in double GUI px (Mouse.getX/Y, not the event), for code outside events. */
	public double mouseGuiX() {
		return Mouse.getX() * (double) width / Math.max(1, mc.displayWidth);
	}

	public double mouseGuiY() {
		return height - Mouse.getY() * (double) height / Math.max(1, mc.displayHeight) - 1;
	}

	/** GLFW-style modifier bits from the live keyboard. */
	public static int modifiers() {
		int m = 0;
		if (isShiftKeyDown()) {
			m |= MOD_SHIFT;
		}
		if (isCtrlKeyDown()) {
			m |= MOD_CONTROL;
		}
		if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
			m |= MOD_ALT;
		}
		return m;
	}

	public static boolean hasShiftDown() {
		return isShiftKeyDown();
	}

	public static boolean hasControlDown() {
		return isCtrlKeyDown();
	}

	public static boolean hasAltDown() {
		return (modifiers() & MOD_ALT) != 0;
	}

	// ---- the 1.8.9 side of the bridge ----

	@Override
	public void setWorldAndResolution(Minecraft mc, int width, int height) {
		this.minecraft = mc;
		this.font = mc.fontRendererObj;
		super.setWorldAndResolution(mc, width, height);
	}

	@Override
	public final void initGui() {
		// Modern screens get key repeats (held Backspace / arrows) from GLFW; LWJGL 2
		// only sends them while repeat events are on (vanilla GuiChat does the same).
		Keyboard.enableRepeatEvents(true);
		try {
			init();
		} catch (Throwable t) {
			fail("init", t);
		}
	}

	/** How many times an Origin screen drained its input queue (bench: input samples per second). Render thread. */
	public static long inputPolls;

	@Override
	public void handleInput() throws IOException {
		inputPolls++;
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_SCREEN_INPUT);
		}
		try {
			super.handleInput();
		} finally {
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_SCREEN_INPUT);
			}
		}
	}

	// ---- input every frame ----
	//
	// 1.8.9 reads a screen's input queue only in Minecraft.runTick (javap: runTick is GuiScreen.handleInput's
	// only caller), i.e. 20 times a second: a click, wheel step or key waited up to 50 ms, and a drag moved in
	// 50 ms steps, however high the frame rate. 1.16.5+ gets GLFW callbacks every frame. So while an Origin
	// screen is open, HudHooks calls frameInput at Forge's RenderTickEvent START, which runGameLoop fires
	// once per frame AFTER runTick and BEFORE the frame's updateCameraAndRender (javap 1.8.9 runGameLoop:
	// runTick @177, onRenderTickStart @334, updateCameraAndRender @359; LWJGL fills the queue in the previous
	// frame's Display.update). It runs the screen's own handleInput -- the same Mouse.next()/Keyboard.next()
	// drain (and Forge's MouseInputEvent/KeyboardInputEvent) runTick runs -- so runTick then finds the queues
	// empty: every event is handled exactly once. In-game keybinds are untouched: runTick's in-game loops only
	// read the queues when no screen is open, and with a screen open vanilla's own drain empties them first.
	// Vanilla screens keep vanilla's tick-time input.
	//
	// The frame is already under way here (matrix pushed, the main framebuffer bound), so the few actions
	// that leave the world or reload everything (Social.join's disconnect + connect, the pack hub's resource
	// reload, FPS Coach's Stop/Undo, which can switch OptiFine shaders) go through outsideFrame, which defers
	// them to the next client tick.
	private static boolean inFrameInput, frameInputOff;
	private static final java.util.ArrayDeque<Runnable> OUTSIDE_FRAME = new java.util.ArrayDeque<>();

	/** Render thread, RenderTickEvent START (HudHooks). Drains an open Origin screen's input this frame. */
	public static void frameInput(Minecraft mc) {
		if (frameInputOff || !(mc.currentScreen instanceof OriginScreen)) {
			return;
		}
		inFrameInput = true;
		try {
			mc.currentScreen.handleInput();
		} catch (Throwable t) {
			// fall back to vanilla's tick-time input for the rest of the session
			frameInputOff = true;
			com.origin.client.OriginClient.LOGGER.error("[origin] per-frame screen input failed; back to tick input", t);
		} finally {
			inFrameInput = false;
		}
	}

	/**
	 * Runs `action` now, unless it was triggered from the per-frame input drain: then it runs at the start of
	 * the next client tick, between frames, where vanilla runs every screen action. Render thread.
	 */
	public static void outsideFrame(Runnable action) {
		if (inFrameInput) {
			OUTSIDE_FRAME.add(action);
		} else {
			action.run();
		}
	}

	// GuiScreen.handleKeyboardInput = keyTyped on key-down, then Minecraft.dispatchKeypresses, which toggles
	// fullscreen on F11 (javap 1.8.9). A fullscreen toggle resizes and rebuilds the main framebuffer the frame
	// is drawing into, so during the per-frame drain it waits for the next client tick, as it would in vanilla.
	@Override
	public void handleKeyboardInput() throws IOException {
		if (inFrameInput && mc != null && Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()
				&& Keyboard.getEventKey() != 0
				&& Keyboard.getEventKey() == mc.gameSettings.keyBindFullscreen.getKeyCode()) {
			keyTyped(Keyboard.getEventCharacter(), Keyboard.getEventKey());
			final Minecraft m = mc;
			OUTSIDE_FRAME.add(m::toggleFullscreen);
			return;
		}
		super.handleKeyboardInput();
	}

	/** Render thread, ClientTickEvent START (HudHooks): runs the actions outsideFrame deferred. */
	public static void runDeferred() {
		Runnable r;
		while ((r = OUTSIDE_FRAME.poll()) != null) {
			try {
				r.run();
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.error("[origin] deferred screen action failed", t);
			}
		}
	}

	@Override
	public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_SCREEN_RENDER);
		}
		Gfx g = new Gfx();
		try {
			render(g, mouseX, mouseY, partialTicks);
		} catch (Throwable t) {
			fail("render", t);
		} finally {
			g.pose().popAll();
			g.disableScissor();
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_SCREEN_RENDER);
			}
		}
	}

	@Override
	public final void updateScreen() {
		try {
			tick();
		} catch (Throwable t) {
			fail("tick", t);
		}
	}

	@Override
	public final void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
		try {
			removed();
		} catch (Throwable t) {
			log("removed", t);
		}
	}

	@Override
	public final boolean doesGuiPauseGame() {
		return isPauseScreen();
	}

	@Override
	public void handleMouseInput() throws IOException {
		// vanilla dispatches press / release / move-while-down to the int handlers below
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0) {
			// LWJGL2 reports 120 per notch on most systems, but some drivers send
			// smaller steps: never round a real scroll to zero.
			double notches = Math.abs(wheel) >= 120 ? wheel / 120.0 : Math.signum(wheel);
			wheelEvents++;
			try {
				mouseScrolled(eventX(), eventY(), notches);
			} catch (Throwable t) {
				fail("mouseScrolled", t);
			}
		}
	}

	@Override
	protected final void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
		double x = eventX(), y = eventY();
		dragButton = button;
		pressEvents++;
		lastDragX = x;
		lastDragY = y;
		boolean used;
		try {
			used = mouseClicked(x, y, button);
		} catch (Throwable t) {
			fail("mouseClicked", t);
			return;
		}
		if (!used) {
			super.mouseClicked(mouseX, mouseY, button);
		}
	}

	@Override
	protected final void mouseReleased(int mouseX, int mouseY, int button) {
		dragButton = -1;
		releaseEvents++;
		boolean used;
		try {
			used = mouseReleased(eventX(), eventY(), button);
		} catch (Throwable t) {
			fail("mouseReleased", t);
			return;
		}
		if (!used) {
			super.mouseReleased(mouseX, mouseY, button);
		}
	}

	/** Bench (hudrobot): presses, releases and wheel steps this bridge handed on. Render thread. */
	public static long pressEvents, releaseEvents, wheelEvents;

	/** Bench (hudrobot): drag positions handed to mouseDragged through this bridge, and the last one. Render thread. */
	public static long dragDeliveries;
	public static double lastDeliveredX, lastDeliveredY;

	@Override
	protected final void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
		double x = eventX(), y = eventY();
		deliverDrag(x, y, dragButton >= 0 ? dragButton : button);
	}

	private void deliverDrag(double x, double y, int button) {
		double dx = x - lastDragX, dy = y - lastDragY;
		lastDragX = x;
		lastDragY = y;
		if (dx == 0 && dy == 0) {
			return;
		}
		dragDeliveries++;
		lastDeliveredX = x;
		lastDeliveredY = y;
		try {
			mouseDragged(x, y, button, dx, dy);
		} catch (Throwable t) {
			fail("mouseDragged", t);
		}
	}

	@Override
	protected final void keyTyped(char typedChar, int keyCode) throws IOException {
		int mods = modifiers();
		try {
			if (keyCode != 0 && keyPressed(keyCode, 0, mods)) {
				return;
			}
			// LWJGL2 delivers the character with its key event; control chars stay keys
			if (typedChar >= ' ' && typedChar != 127 && ChatAllowedCharacters.isAllowedCharacter(typedChar)
					&& charTyped(typedChar, mods)) {
				return;
			}
		} catch (Throwable t) {
			fail("keyPressed", t);
			return;
		}
		if (keyCode == Keyboard.KEY_ESCAPE && shouldCloseOnEsc()) {
			onClose();
		}
	}

	// Mouse.getEventX/Y in double GUI px (vanilla floors them to ints).
	private double eventX() {
		return Mouse.getEventX() * (double) width / Math.max(1, mc.displayWidth);
	}

	private double eventY() {
		return height - Mouse.getEventY() * (double) height / Math.max(1, mc.displayHeight) - 1;
	}

	// Screen classes that threw this session. Whoever OPENS an Origin screen in
	// place of a vanilla one (title screen, pause menu ...) checks this first, so
	// a broken replacement latches to vanilla instead of re-opening every frame.
	private static final java.util.Set<String> BROKEN =
			java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

	/** True once a screen of this class has failed in any bridge this session. */
	public static boolean isBroken(Class<?> screenClass) {
		return screenClass != null && BROKEN.contains(screenClass.getName());
	}

	/** A bridge threw: log once, latch the class broken, leave the screen (vanilla floor), never crash. */
	private void fail(String where, Throwable t) {
		if (BROKEN.add(getClass().getName())) {
			log(where, t);
		}
		try {
			if (mc != null && mc.currentScreen == this) {
				mc.displayGuiScreen(null);
			}
		} catch (Throwable ignored) {
			// nothing safer left to do
		}
	}

	private void log(String where, Throwable t) {
		try {
			com.origin.client.OriginClient.LOGGER.error("Origin screen " + getClass().getSimpleName()
					+ " failed in " + where + "; closing it", t);
		} catch (Throwable ignored) {
			// logging must never throw
		}
	}
}
