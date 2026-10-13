package com.origin.client.client.gui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps buttons added by other mods from landing on top of Origin's own.
 *
 * <p>Origin re-skins vanilla screens in place, so a mod that adds its own button
 * at a hard-coded position (or that assumes vanilla's layout) can end up sitting
 * on a button Origin drew. This nudges those -- and ONLY those -- out of the
 * way.
 *
 * <p>Deliberately minimal: a foreign widget that already sits in free space is
 * never touched, so a mod's own layout is preserved wherever it does not
 * actually conflict. Only a widget that genuinely overlaps something gets moved,
 * and it keeps its own size and its own rendering ({@link
 * OriginWidgetOwnership}) -- just at a free spot.
 *
 * <p>Runs from {@code init()} TAIL, which Minecraft also re-runs on every
 * resize, so placement re-settles with the new screen size instead of drifting.
 */
public final class OriginForeignWidgets {

	// Left-edge column used as the relocation target. Chosen because every
	// screen Origin re-skins keeps its own controls centered or bottom-anchored,
	// so the left gutter is the reliably empty strip.
	private static final int MARGIN = 4;
	private static final int GAP = 4;

	private OriginForeignWidgets() {
	}

	/**
	 * Moves any other mod's widget that overlaps an Origin/vanilla widget into
	 * the nearest free slot. Safe to call on any screen; does nothing when the
	 * screen has no foreign widgets, which is the overwhelmingly common case.
	 */
	public static void avoidOverlap(Screen screen) {
		try {
			List<AbstractWidget> owned = new ArrayList<>();
			List<AbstractWidget> foreign = new ArrayList<>();

			for (GuiEventListener child : screen.children()) {
				if (!(child instanceof AbstractWidget)) {
					continue;
				}
				AbstractWidget widget = (AbstractWidget) child;
				if (!widget.visible) {
					continue;
				}
				if (OriginWidgetOwnership.isForeign(widget)) {
					foreign.add(widget);
				} else {
					owned.add(widget);
				}
			}

			if (foreign.isEmpty()) {
				return;
			}

			// Widgets already settled in free space act as obstacles for the
			// ones still to be placed, so two relocated buttons can't stack up
			// on the same slot.
			List<AbstractWidget> placed = new ArrayList<>(owned);

			for (AbstractWidget widget : foreign) {
				if (!collidesWithAny(widget, widget.getX(), widget.getY(), placed)) {
					// The mod's own placement is fine -- leave it exactly there.
					placed.add(widget);
					continue;
				}
				relocate(screen, widget, placed);
				placed.add(widget);
			}
		} catch (Throwable t) {
			// Layout is a nicety; never let it take a screen down. Origin's
			// fail-soft contract applies here as everywhere else.
			com.origin.client.OriginClient.LOGGER.warn(
					"Origin could not re-place a third-party widget; leaving it where the mod put it", t);
		}
	}

	// Walks the left gutter top-down for the first slot this widget fits in.
	// Falls back to leaving the widget alone rather than forcing it off-screen.
	private static void relocate(Screen screen, AbstractWidget widget, List<AbstractWidget> obstacles) {
		int x = MARGIN;
		int height = Math.max(1, widget.getHeight());

		for (int y = MARGIN; y + height <= screen.height - MARGIN; y += height + GAP) {
			if (!collidesWithAny(widget, x, y, obstacles)) {
				widget.setX(x);
				widget.setY(y);
				return;
			}
		}
		// No free slot: the mod's original position is still the best guess.
	}

	private static boolean collidesWithAny(AbstractWidget widget, int x, int y, List<AbstractWidget> others) {
		int w = widget.getWidth();
		int h = widget.getHeight();
		for (AbstractWidget other : others) {
			if (other == widget || !other.visible) {
				continue;
			}
			if (intersects(x, y, w, h, other.getX(), other.getY(), other.getWidth(), other.getHeight())) {
				return true;
			}
		}
		return false;
	}

	private static boolean intersects(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
		return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
	}
}
