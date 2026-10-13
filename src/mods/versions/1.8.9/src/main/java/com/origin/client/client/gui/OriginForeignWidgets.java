package com.origin.client.client.gui;

import com.origin.client.client.mixin.menus.GuiScreenAccessor;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

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
 * <p>Runs after a screen's layout is built, which Minecraft also re-runs on every
 * resize, so placement re-settles with the new screen size instead of drifting.
 * 1.8.9: the widgets are the screen's GuiButton list (xPosition / yPosition /
 * width / height are public fields), read through GuiScreenAccessor.
 */
public final class OriginForeignWidgets {

	// Left-edge column used as the relocation target: every screen Origin
	// re-skins keeps its own controls centred or bottom-anchored.
	private static final int MARGIN = 4;
	private static final int GAP = 4;

	private OriginForeignWidgets() {
	}

	/** Moves any other mod's button that overlaps an Origin/vanilla one into the
	 *  nearest free slot. Safe on any screen; a no-op without foreign buttons. */
	public static void avoidOverlap(GuiScreen screen) {
		if (screen instanceof GuiScreenAccessor) {
			avoidOverlap(screen, ((GuiScreenAccessor) screen).originclient$buttonList());
		}
	}

	/** 1.8.9: the same, for a caller that already holds the screen's button list
	 *  (Forge's InitGuiEvent hands it over). */
	public static void avoidOverlap(GuiScreen screen, List<GuiButton> buttons) {
		try {
			if (buttons == null) {
				return;
			}
			List<GuiButton> owned = new ArrayList<>();
			List<GuiButton> foreign = new ArrayList<>();
			for (GuiButton widget : buttons) {
				if (widget == null || !widget.visible) {
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
			// Widgets already settled in free space act as obstacles for the ones
			// still to be placed, so two relocated buttons can't stack up.
			List<GuiButton> placed = new ArrayList<>(owned);
			for (GuiButton widget : foreign) {
				if (!collidesWithAny(widget, widget.xPosition, widget.yPosition, placed)) {
					placed.add(widget);   // the mod's own placement is fine
					continue;
				}
				relocate(screen, widget, placed);
				placed.add(widget);
			}
		} catch (Throwable t) {
			// Layout is a nicety; never let it take a screen down.
			com.origin.client.OriginClient.LOGGER.warn(
					"Origin could not re-place a third-party widget; leaving it where the mod put it", t);
		}
	}

	// Walks the left gutter top-down for the first slot this widget fits in.
	// Falls back to leaving the widget alone rather than forcing it off-screen.
	private static void relocate(GuiScreen screen, GuiButton widget, List<GuiButton> obstacles) {
		int x = MARGIN;
		int height = Math.max(1, widget.height);
		for (int y = MARGIN; y + height <= screen.height - MARGIN; y += height + GAP) {
			if (!collidesWithAny(widget, x, y, obstacles)) {
				widget.xPosition = x;
				widget.yPosition = y;
				return;
			}
		}
	}

	private static boolean collidesWithAny(GuiButton widget, int x, int y, List<GuiButton> others) {
		for (GuiButton other : others) {
			if (other == widget || !other.visible) {
				continue;
			}
			if (intersects(x, y, widget.width, widget.height,
					other.xPosition, other.yPosition, other.width, other.height)) {
				return true;
			}
		}
		return false;
	}

	private static boolean intersects(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
		return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
	}
}
