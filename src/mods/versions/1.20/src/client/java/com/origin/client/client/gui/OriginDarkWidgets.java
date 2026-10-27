package com.origin.client.client.gui;

import net.minecraft.resources.ResourceLocation;

/**
 * Origin's dark vanilla widgets on the eras before the 1.20.2 sprite atlas (Will, 2026-10-26:
 * every vanilla button looks like the Default Dark Mode pack's, built in). On 1.20.2+ the dark
 * set is plain jar resources (gui/sprites/widget/*.png). Here buttons, pre-1.19.4 slider parts and
 * the lock button come out of vanilla's widgets.png, which also holds the hotbar and is Mojang's
 * art, so it is never shipped or overridden: the Dark* mixins swap only the texture id inside
 * vanilla's own button draw for Origin's dark_widgets.png / dark_slider.png, which put Origin's
 * own pixels at widgets.png's UVs (tools/dark-widgets/make_widgets.py legacy / legacy-slider).
 * Vanilla's blits, text, alpha, hover and focus logic all run unchanged.
 *
 * <p>Whole-file widget textures (gui/checkbox.png; slider.png + tab_button.png on 1.19.4+) are
 * Origin-drawn jar overrides and need no code. Text fields are fills: their two frame colours are
 * swapped for 1.21.1's dark text_field (well #0E0E0F, edge #505055, focused edge stays white).
 *
 * <p>Fail-soft: the first throw latches {@link #broken} and every hook hands vanilla's texture /
 * colour back for the session. Each hook logs once the first time it runs, which is the CI proof
 * that the mixin landed. No allocation per frame (constant ids, an int bitmask).
 */
public final class OriginDarkWidgets {
	public static final ResourceLocation WIDGETS = new ResourceLocation("originclient", "textures/gui/dark_widgets.png");

	/** 1.21.1 text_field: the well and its 1 px edge (focused keeps vanilla's white edge). */
	public static final int FIELD = 0xFF0E0E0F;
	public static final int FIELD_EDGE = 0xFF505055;

	public static final int BUTTON = 1;
	public static final int SLIDER_TRACK = 2;
	public static final int SLIDER_HANDLE = 4;
	public static final int LOCK = 8;
	public static final int EDIT_EDGE = 16;
	public static final int EDIT_FIELD = 32;

	private static volatile boolean broken = false;
	private static int seen = 0;

	private OriginDarkWidgets() {
	}

	/** The texture a vanilla widget draw should bind: Origin's dark sheet, or vanilla's on failure. */
	public static ResourceLocation texture(ResourceLocation vanilla, ResourceLocation dark, int hook) {
		if (broken) {
			return vanilla;
		}
		try {
			landed(hook);
			return dark;
		} catch (Throwable t) {
			fail(t);
			return vanilla;
		}
	}

	/** A text-field frame colour: 1.21.1's dark one, or vanilla's on failure. */
	public static int colour(int vanilla, int dark, int hook) {
		if (broken) {
			return vanilla;
		}
		try {
			landed(hook);
			return dark;
		} catch (Throwable t) {
			fail(t);
			return vanilla;
		}
	}

	private static void landed(int hook) {
		if ((seen & hook) == 0) {
			seen |= hook;
			com.origin.client.OriginClient.LOGGER.info("Origin dark widgets: {} hook live", name(hook));
		}
	}

	private static void fail(Throwable t) {
		broken = true;
		com.origin.client.OriginClient.LOGGER.error("Origin dark widgets failed; vanilla widgets for this session", t);
	}

	private static String name(int hook) {
		switch (hook) {
			case BUTTON: return "button";
			case SLIDER_TRACK: return "slider track";
			case SLIDER_HANDLE: return "slider handle";
			case LOCK: return "lock button";
			case EDIT_EDGE: return "text field edge";
			case EDIT_FIELD: return "text field well";
			default: return "widget";
		}
	}
}
