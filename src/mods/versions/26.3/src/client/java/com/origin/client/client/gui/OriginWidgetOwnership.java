package com.origin.client.client.gui;

/**
 * Decides whether a widget's LOOK belongs to Origin.
 *
 * <p>Origin's widget mixins hook the base classes ({@code AbstractButton},
 * {@code AbstractSliderButton}, ...), so they fire for every subclass in the
 * game -- including button classes that other mods define. Those mods draw
 * their own art (an icon, a texture, a custom label) from inside the very
 * method Origin cancels, so restyling them replaced the mod's button with an
 * empty Origin box. Create's custom buttons were the visible case.
 *
 * <p>The rule is deliberately about WHO WROTE THE CLASS, not what it looks
 * like:
 * <ul>
 *   <li>A class from {@code net.minecraft.*} / {@code com.mojang.*} is a
 *       vanilla widget -- Origin restyles it, which is the whole point of the
 *       client.</li>
 *   <li>A class from {@code com.origin.*} is Origin's own -- restyled too.</li>
 *   <li>Anything else was compiled by another mod. Its drawing is its own
 *       identity, so Origin leaves it completely alone.</li>
 * </ul>
 *
 * <p>Note what this deliberately does NOT do: a mod that adds a plain vanilla
 * {@code Button} still gets the Origin look, because the class is still
 * Minecraft's and there is no custom art to erase. Only mods that wrote their
 * own widget class keep their own style -- which is exactly the line between
 * "a button on an Origin screen" and "Create's button".
 *
 * <p>Version-independent (no Minecraft API is touched), so this lives in
 * shared/ and syncs to every module.
 */
public final class OriginWidgetOwnership {
	// Mod Menu's pause-screen entry is a normal Minecraft button with only a
	// small update badge added after the base render. Origin owns the base look
	// so it should match the rest of the pause menu; the badge still renders.
	private static final String MOD_MENU_BUTTON =
			"com.terraformersmc.modmenu.gui.widget.ModMenuButtonWidget";

	// Package prefixes whose widgets Origin is entitled to redraw.
	private static final String[] OWNED_PREFIXES = {
			"net.minecraft.",
			"com.mojang.",
			"com.origin.",
	};

	// Resolved once per widget CLASS, not per widget and not per frame: this is
	// consulted from render paths that run for every button, every frame.
	// ClassValue is the JDK's own per-class cache and holds no strong reference
	// that would pin a mod's classloader.
	private static final ClassValue<Boolean> FOREIGN = new ClassValue<Boolean>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			String name = type.getName();
			if (MOD_MENU_BUTTON.equals(name)) {
				return Boolean.FALSE;
			}
			for (String prefix : OWNED_PREFIXES) {
				if (name.startsWith(prefix)) {
					return Boolean.FALSE;
				}
			}
			return Boolean.TRUE;
		}
	};

	private OriginWidgetOwnership() {
	}

	/**
	 * True when the widget's class comes from another mod, meaning Origin must
	 * not draw over it. Callers return false / skip cancelling so the widget's
	 * own rendering runs untouched.
	 */
	public static boolean isForeign(Object widget) {
		return widget != null && FOREIGN.get(widget.getClass());
	}

	/** Convenience inverse -- reads better at call sites that ask permission. */
	public static boolean originOwnsLook(Object widget) {
		return !isForeign(widget);
	}

	/**
	 * True only for classes Origin itself wrote.
	 *
	 * <p>NOT the same question as {@link #originOwnsLook} -- that one answers
	 * "may Origin repaint this widget?", for which a {@code net.minecraft.*}
	 * class is a YES. This one answers "is this Origin's own code?", for which
	 * vanilla is a NO. Used to decide whether the current SCREEN belongs to
	 * Origin, where treating vanilla as ours would restyle the entire game.
	 */
	public static boolean isOriginOwnClass(Object object) {
		return object != null && object.getClass().getName().startsWith("com.origin.");
	}
}
