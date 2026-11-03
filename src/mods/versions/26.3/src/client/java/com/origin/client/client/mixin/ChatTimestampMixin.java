package com.origin.client.client.mixin;

import com.origin.client.client.ext.ChatTextState;
import com.origin.client.client.mods.Mods;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

// Chat mod — every behaviour the card advertises lives here:
//   Timestamps   — prepends a muted [HH:mm]. We target the 3-arg addMessage
//                  because the 1-arg overload delegates to it; the old mixin
//                  targeted the 1-arg and so missed most lines.
//   Stack Spam   — a repeat of the previous message replaces it and gains a
//                  running "(xN)" counter, so spam collapses to one line.
//   Unlimited    — vanilla hard-trims BOTH message lists to 100 lines.
//   Keep History — vanilla wipes chat on disconnect (Gui.onDisconnected ->
//                  clearMessages(true)); F3+D passes FALSE, so gating on the
//                  argument keeps the manual clear working.
//   Smooth Chat  — slides the stack up as a message arrives instead of popping.
//                  1.21.11's pose is a Matrix3x2fStack: pushMatrix/translate(x,y).
//   Text Shadow  — armed here, applied in ChatTextShadowMixin (this era has no
//                  per-call shadow flag on the chat text path).
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
	private double getScale() {
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
		// Each line knows its message: only `previous`'s own lines are taken. If the newest
		// line isn't one (it was hidden or filtered), rebuild instead.
		if (trimmedMessages.isEmpty() || trimmedMessages.get(0).parent() != previous) {
			refreshTrimmedMessages();
			return;
		}
		int removed = 0;
		do {
			trimmedMessages.remove(0);
			removed++;
		} while (!trimmedMessages.isEmpty() && trimmedMessages.get(0).parent() == previous);
		if (chatScrollbarPos > 0) {
			// keep the same lines in view: the ones above moved down by `removed`
			chatScrollbarPos = Math.max(0, chatScrollbarPos - removed);
		}
	}

	private static boolean originclient$opt(String key) {
		return Mods.on("chat") && Mods.bool("chat", key);
	}

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;"
			+ "Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
			+ "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component originclient$transform(Component message) {
		originclient$lastMessageMs = Util.getMillis();
		if (!Mods.on("chat")) {
			return message;
		}
		Component result = message;

		if (Mods.bool("chat", "stackSpam")) {
			String base = message.getString();
			if (base.equals(originclient$lastBase) && !allMessages.isEmpty()) {
				originclient$lastCount++;
				GuiMessage previous = allMessages.remove(0);   // drop the previous identical line
				originclient$dropNewestLines(previous);
				result = Component.literal(base + " ")
						.append(Component.literal("(x" + originclient$lastCount + ")").withStyle(ChatFormatting.GRAY));
			} else {
				originclient$lastBase = base;
				originclient$lastCount = 1;
			}
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

	// ---- Smooth Chat + Text Shadow, both scoped to the public extractRenderState() (26.2) ----

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
			at = @At("HEAD"), require = 1)
	private void originclient$renderStart(GuiGraphicsExtractor guiGraphics, Font font, int tickCount, int mouseX, int mouseY,
										  ChatComponent.DisplayMode displayMode, boolean changeCursor, CallbackInfo ci) {
		ChatTextState.suppressShadow = Mods.on("chat") && !Mods.bool("chat", "textShadow");

		originclient$slid = false;
		if (!originclient$opt("smoothChat")) {
			return;
		}
		long since = Util.getMillis() - originclient$lastMessageMs;
		if (since < 0 || since >= SLIDE_MS) {
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

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
			at = @At("RETURN"), require = 1)
	private void originclient$renderEnd(GuiGraphicsExtractor guiGraphics, Font font, int tickCount, int mouseX, int mouseY,
										ChatComponent.DisplayMode displayMode, boolean changeCursor, CallbackInfo ci) {
		if (originclient$slid) {
			guiGraphics.pose().popMatrix();
			originclient$slid = false;
		}
		ChatTextState.suppressShadow = false;
	}
}
