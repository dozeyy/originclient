package com.origin.client.client.mixin;

import com.origin.client.client.ext.ChatTextState;
import com.origin.client.client.mods.Mods;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

// Chat mod — every behaviour the card advertises lives here:
//   Timestamps   — prepends a muted [HH:mm]. We target the 3-arg addMessage
//                  because the 1-arg overload delegates to it (verified against
//                  the 1.21.1 jar); the old mixin targeted the 1-arg and so
//                  missed most lines.
//   Stack Spam   — when a message repeats the previous one, the earlier copy is
//                  removed and the new one gets a running "(xN)" counter, so spam
//                  collapses to a single updating line.
//   Unlimited    — vanilla hard-trims BOTH message lists to 100 lines. The two
//                  trims are the only int-100 constants in their methods.
//   Keep History — vanilla wipes chat on disconnect (Gui.onDisconnected ->
//                  clearMessages(true)). F3+D passes FALSE, so gating on the
//                  argument keeps the manual clear working while chat survives
//                  a server switch.
//   Smooth Chat  — slides the stack up as a message arrives instead of popping.
//                  1.21.6+ pose is a Matrix3x2fStack: pushMatrix/translate(x,y).
//   Text Shadow  — armed here, applied in ChatTextShadowMixin: 1.21.6+ draws the
//                  chat lines from a lambda inside render(), so there is no stable
//                  call site to redirect — we flag the span instead.
// Opacity/scale ride the vanilla accessibility options from the feature tick.
@Mixin(ChatComponent.class)
public abstract class ChatTimestampMixin {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	// "Unlimited" with a guard rail: kept lines are never released otherwise, so
	// this is the memory ceiling (~80x vanilla, a few MB of text at worst).
	private static final int ORIGIN_MAX_HISTORY = 8192;
	// Slide-in duration. Short on purpose — chat must never feel laggy.
	private static final long SLIDE_MS = 140L;

	@Shadow @Final private List<GuiMessage> allMessages;
	@Shadow @Final private List<GuiMessage.Line> trimmedMessages;
	@Shadow private int chatScrollbarPos;

	@Shadow
	private void refreshTrimmedMessages() {
		throw new AssertionError();
	}

	@Shadow
	private int getLineHeight() {
		throw new AssertionError();
	}

	@Shadow
	public double getScale() {
		throw new AssertionError();
	}

	private static String originclient$lastBase = null;
	private static int originclient$lastCount = 1;
	private static long originclient$lastMessageMs = 0L;
	private boolean originclient$slid;

	/** Stack Spam: takes the newest message's wrapped lines off the display list in
	 *  place. Rebuilding the whole list (refreshTrimmedMessages) re-wrapped every kept
	 *  line (up to 8192 with Unlimited) on each repeat, and re-adding them bumped the
	 *  scroll once per line, so a player scrolled up was thrown to the top.
	 *  Lines go in newest-first: a message's LAST wrapped line (endOfEntry) is at
	 *  index 0 and its earlier lines follow up to the next endOfEntry. */
	private void originclient$dropNewestLines(GuiMessage previous) {
		// Lines don't know their message here, so the newest block must match `previous`
		// by tick AND by text; another message from the same tick (when the repeat was
		// hidden or filtered) never loses its lines. No match: rebuild instead.
		int n = originclient$newestBlockSize();
		if (n == 0 || trimmedMessages.get(0).addedTime() != previous.addedTime()
				|| !originclient$blockText(n).equals(originclient$squash(StringDecomposer.getPlainText(previous.content())))) {
			refreshTrimmedMessages();
			return;
		}
		int removed = n;
		for (int i = 0; i < n; i++) {
			trimmedMessages.remove(0);
		}
		if (chatScrollbarPos > 0) {
			// keep the same lines in view: the ones above moved down by `removed`
			chatScrollbarPos = Math.max(0, chatScrollbarPos - removed);
		}
	}

	/** Lines in the newest message block: index 0 (its last wrapped line) plus the
	 *  following lines up to the next message's endOfEntry. */
	private int originclient$newestBlockSize() {
		if (trimmedMessages.isEmpty()) {
			return 0;
		}
		int n = 1;
		while (n < trimmedMessages.size() && !trimmedMessages.get(n).endOfEntry()) {
			n++;
		}
		return n;
	}

	/** The newest block's text in reading order (lines are stored newest-first), no whitespace. */
	private String originclient$blockText(int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = n - 1; i >= 0; i--) {
			trimmedMessages.get(i).content().accept((index, style, cp) -> {
				if (!Character.isWhitespace(cp)) {
					sb.appendCodePoint(cp);
				}
				return true;
			});
		}
		return sb.toString();
	}

	private static String originclient$squash(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		s.codePoints().filter(cp -> !Character.isWhitespace(cp)).forEach(sb::appendCodePoint);
		return sb.toString();
	}

	private static boolean originclient$opt(String key) {
		return Mods.on("chat") && Mods.bool("chat", key);
	}

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;"
			+ "Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component originclient$transform(Component message) {
		originclient$lastMessageMs = Util.getMillis();
		if (!Mods.on("chat")) {
			// Stacking skipped: the line remembered for Stack Spam is no longer the newest one,
			// so a repeat after turning Chat back on must not remove whatever line is (MEDIA-4).
			originclient$lastBase = null;
			return message;
		}
		Component result = message;

		if (Mods.bool("chat", "stackSpam")) {
			String base = message.getString();
			if (base.equals(originclient$lastBase) && !allMessages.isEmpty()) {
				originclient$lastCount++;
				GuiMessage previous = allMessages.remove(0);   // drop the previous identical line
				originclient$dropNewestLines(previous);
				// Keep the line's own styles (colour, hover, click link): the original
				// message sits under an unstyled root, so it is not rebuilt from plain text
				// and the grey counter does not inherit its style. ChatCopy strips this
				// exact shape (last sibling = grey "(xN)").
				result = Component.empty().append(message).append(Component.literal(" "))
						.append(Component.literal("(x" + originclient$lastCount + ")").withStyle(ChatFormatting.GRAY));
			} else {
				originclient$lastBase = base;
				originclient$lastCount = 1;
			}
		} else {
			originclient$lastBase = null;   // Stack Spam off: same as above (MEDIA-4)
		}

		if (Mods.bool("chat", "timestamps")) {
			// Append the message as a sibling of an unstyled root so it keeps its
			// own colour (white) instead of inheriting the timestamp's grey — the
			// old code styled the parent grey and the message inherited it.
			Component stamp = Component.literal("[" + LocalTime.now().format(TIME) + "] ")
					.withStyle(ChatFormatting.DARK_GRAY);
			result = Component.empty().append(stamp).append(result);
		}
		return result;
	}

	// ---- Unlimited Chat: the two history trims ----

	@ModifyConstant(method = "addMessageToQueue", constant = @Constant(intValue = 100), require = 1)
	private int originclient$fullHistoryCap(int vanillaCap) {
		return originclient$opt("unlimited") ? ORIGIN_MAX_HISTORY : vanillaCap;
	}

	@ModifyConstant(method = "addMessageToDisplayQueue", constant = @Constant(intValue = 100), require = 1)
	private int originclient$displayHistoryCap(int vanillaCap) {
		return originclient$opt("unlimited") ? ORIGIN_MAX_HISTORY : vanillaCap;
	}

	// ---- Keep Chat History across a disconnect ----

	@Inject(method = "clearMessages", at = @At("HEAD"), cancellable = true, require = 1)
	private void originclient$keepHistory(boolean clearSentMsgHistory, CallbackInfo ci) {
		// Only the disconnect path passes true; F3+D passes false and still clears.
		if (clearSentMsgHistory && originclient$opt("keepHistory")) {
			ci.cancel();
		}
	}

	// ---- Smooth Chat: slide the stack into place instead of popping ----

	@Inject(method = "render", at = @At("HEAD"), require = 1)
	private void originclient$slideStart(GuiGraphics guiGraphics, int tickCount, int mouseX, int mouseY,
										 boolean focused, CallbackInfo ci) {
		ChatTextState.suppressShadow = Mods.on("chat") && !Mods.bool("chat", "textShadow");

		originclient$slid = false;
		if (!originclient$opt("smoothChat")) {
			return;
		}
		long since = Util.getMillis() - originclient$lastMessageMs;
		// Scrolled up to read back: vanilla keeps that view still as lines arrive, so the
		// stack must not slide under the player's eyes on every message (MEDIA-3).
		if (since < 0 || since >= SLIDE_MS || chatScrollbarPos > 0) {
			return;
		}
		// Ease-out cubic: fast off the mark, settles softly — the house motion feel.
		float t = (float) since / SLIDE_MS;
		float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
		float offset = (float) ((1.0F - eased) * getLineHeight() * getScale());
		guiGraphics.pose().pushMatrix();
		guiGraphics.pose().translate(0.0F, offset);
		originclient$slid = true;
	}

	@Inject(method = "render", at = @At("RETURN"), require = 1)
	private void originclient$slideEnd(GuiGraphics guiGraphics, int tickCount, int mouseX, int mouseY,
									   boolean focused, CallbackInfo ci) {
		if (originclient$slid) {
			guiGraphics.pose().popMatrix();
			originclient$slid = false;
		}
		ChatTextState.suppressShadow = false;
	}

}
