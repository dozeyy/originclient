package com.origin.client.client.ext;

/**
 * One-frame handshake between ChatTimestampMixin and ChatTextShadowMixin.
 *
 * 1.21.11 has no per-call shadow flag on the chat text path: chat lines go
 * through ActiveTextCollector, whose Parameters record carries pose/opacity/
 * scissor only, and the drop-shadow boolean is hard-coded where the
 * GuiTextRenderState is built — shared by every piece of GUI text. So "Text
 * Shadow in Chat" is done by arming this flag for exactly the span of
 * ChatComponent.render and reading it at the one place the flag is baked in.
 *
 * Render-thread only, so a plain static is correct (and free).
 */
public final class ChatTextState {

	/** True only while chat is rendering AND the player turned its shadow off. */
	public static boolean suppressShadow;

	private ChatTextState() {
	}
}
