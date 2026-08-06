package com.origin.client.client.ext;

/**
 * One-frame handshake between ChatTimestampMixin and ChatTextShadowMixin.
 *
 * From 1.21.6 the chat lines are drawn from a lambda inside ChatComponent.render,
 * whose compiled name is a synthetic (method_NNNNN) — not a stable mixin target.
 * So "Text Shadow in Chat" is done by arming this flag for exactly the span of
 * ChatComponent.render and reading it at GuiGraphics.drawString.
 *
 * Render-thread only, so a plain static is correct (and free).
 */
public final class ChatTextState {

	/** True only while chat is rendering AND the player turned its shadow off. */
	public static boolean suppressShadow;

	private ChatTextState() {
	}
}
