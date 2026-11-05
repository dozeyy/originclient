package com.origin.client.client.shaders;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Supplier;

// Origin's "Download Shaders" entry on Iris's Shader Packs screen.
//
// Iris 1.8+ shows its own big centred "Download Shaders" row while shaderpacks/ is
// empty; it opens a "leave the game?" link to modrinth.com (SHADER-1). Origin points
// that row at its in-game browser instead, and its own small corner button shows only
// while Iris's row doesn't (so the player never sees two buttons with one name).
//
// Iris rebuilds its widgets by calling its own init() (Hide GUI, Esc, the pack/options
// switch), which Fabric's AFTER_INIT never sees: it wipes the corner button and makes a
// fresh list with a fresh link row. So the state is re-checked every screen tick, which
// also keeps the corner button off while Iris's Hide GUI shows only the world (SHADER-10).
//
// Iris internals are read by reflection (no compile dep). Every lookup is fail-soft: if
// Iris renames a field, its row keeps its own link and Origin's corner button stays,
// the screen as it was before this hook. Java 8 syntax: the same file ships in every
// Fabric module.
public final class IrisScreenHook {
	private IrisScreenHook() {
	}

	// Cached lookups (one Iris jar per game). null = not found: that part is skipped.
	private static volatile boolean looked;
	private static Field listField;     // ShaderPackScreen.shaderPackList
	private static Field rowField;      // ShaderPackSelectionList.downloadButton (a PinnedEntry)
	private static Field actionField;   // PinnedEntry.onClick (Runnable)
	private static Field hiddenField;   // ShaderPackScreen.guiHidden

	/** Called from AFTER_INIT on Iris's ShaderPackScreen; keeps the entry right while it is open. */
	public static void attach(final Screen screen, final List<?> widgets, final Component label,
			final Supplier<AbstractWidget> cornerButton, final Runnable openBrowser) {
		sync(screen, widgets, label, cornerButton, openBrowser);
		ScreenEvents.afterTick(screen).register(s -> sync(screen, widgets, label, cornerButton, openBrowser));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void sync(Screen screen, List<?> widgets, Component label,
			Supplier<AbstractWidget> cornerButton, Runnable openBrowser) {
		try {
			boolean irisRowIsOurs = routeIrisRow(screen, openBrowser);
			boolean want = !guiHidden(screen) && !irisRowIsOurs;
			AbstractWidget present = null;
			for (Object w : widgets) {
				if (w instanceof Button && label.equals(((Button) w).getMessage())) {
					present = (AbstractWidget) w;
					break;
				}
			}
			if (want && present == null) {
				((List) widgets).add(cornerButton.get());
			} else if (!want && present != null) {
				widgets.remove(present);
			}
		} catch (Throwable t) {
			// never break Iris's screen over Origin's button
		}
	}

	/**
	 * Points Iris's own "Download Shaders" row at Origin's browser. True only when that
	 * row is on screen now and opens the browser; false when Iris has no such row, hides
	 * it (packs exist), or a lookup failed.
	 */
	private static boolean routeIrisRow(Screen screen, Runnable openBrowser) {
		lookUp(screen.getClass());
		if (listField == null || rowField == null || actionField == null) {
			return false;
		}
		try {
			Object list = listField.get(screen);
			if (!(list instanceof AbstractSelectionList)) {
				return false;
			}
			Object row = rowField.get(list);
			if (row == null) {
				return false;
			}
			// Iris makes a new list (and row) on each of its re-inits, so set it every time
			// the row still carries Iris's own link.
			if (!(actionField.get(row) instanceof BrowserLink)) {
				actionField.set(row, new BrowserLink(openBrowser));
			}
			return ((AbstractSelectionList<?>) list).children().contains(row);
		} catch (Throwable t) {
			// e.g. a later Java that refuses to set a final field: Iris keeps its link
			actionField = null;
			return false;
		}
	}

	/** Iris's Hide GUI (in a world): only the world shows, so Origin's button hides too. */
	private static boolean guiHidden(Screen screen) {
		lookUp(screen.getClass());
		try {
			return hiddenField != null && hiddenField.getBoolean(screen);
		} catch (Throwable t) {
			return false;
		}
	}

	/** Marks the row's action as Origin's, so it is set once per Iris list. */
	private static final class BrowserLink implements Runnable {
		private final Runnable open;

		BrowserLink(Runnable open) {
			this.open = open;
		}

		@Override
		public void run() {
			open.run();
		}
	}

	private static synchronized void lookUp(Class<?> screenClass) {
		if (looked) {
			return;
		}
		looked = true;
		hiddenField = field(screenClass, "guiHidden", boolean.class);
		listField = field(screenClass, "shaderPackList", null);
		if (listField == null) {
			return;
		}
		rowField = field(listField.getType(), "downloadButton", null);
		if (rowField == null) {
			return;
		}
		// The row's click action: its one Runnable field (named onClick in Iris 1.8-1.10).
		for (Field f : rowField.getType().getDeclaredFields()) {
			if (f.getType() == Runnable.class && !Modifier.isStatic(f.getModifiers())) {
				try {
					f.setAccessible(true);
					actionField = f;
				} catch (Throwable ignored) {
					// stays null: the row keeps Iris's link
				}
				return;
			}
		}
	}

	private static Field field(Class<?> c, String name, Class<?> type) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			try {
				Field f = k.getDeclaredField(name);
				if (type != null && f.getType() != type) {
					return null;
				}
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException e) {
				// try the superclass
			} catch (Throwable t) {
				return null;
			}
		}
		return null;
	}
}
