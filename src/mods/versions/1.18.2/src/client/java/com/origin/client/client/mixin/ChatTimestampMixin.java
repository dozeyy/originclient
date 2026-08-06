package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

// Chat mod, message-side behaviour:
//   Timestamps — prepends a muted [HH:mm]. 1.18.2 predates the chat-signature
//                era entirely: the public addMessage(Component) delegates to
//                the private addMessage(Component,int), which every NEW message
//                funnels through (bytecode-verified). rescaleChat() re-adds via
//                the 4-arg overload with refresh=true, so hooking the 2-arg one
//                never double-stamps on a chat rescale.
//   Stack Spam — when a message repeats the previous one, the earlier copy is
//                removed and the new one gets a running "(xN)" counter, so spam
//                collapses to a single updating line.
// Opacity/scale ride the vanilla accessibility options from the feature tick.
@Mixin(ChatComponent.class)
public abstract class ChatTimestampMixin {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	@Shadow @Final private List<GuiMessage<Component>> allMessages;

	// 1.18.2's re-trim after a mutation is the public rescaleChat()
	// (refreshTrimmedMessage is the later 1.19.x name).
	@Shadow
	public abstract void rescaleChat();

	// "Unlimited" with a guard rail: kept lines are never released otherwise, so
	// this is the memory ceiling (~80x vanilla, a few MB of text at worst).
	private static final int ORIGIN_MAX_HISTORY = 8192;
	// Slide-in duration. Short on purpose — chat must never feel laggy.
	private static final long SLIDE_MS = 140L;

	@Shadow
	public double getScale() {
		throw new AssertionError();
	}

	private static long originclient$lastMessageMs = 0L;
	private boolean originclient$slid;

	private static boolean originclient$opt(String key) {
		return Mods.on("chat") && Mods.bool("chat", key);
	}

	private static String originclient$lastBase = null;
	private static int originclient$lastCount = 1;

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;I)V",
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
				allMessages.remove(0);          // drop the previous identical line
				rescaleChat();
				result = new TextComponent(base + " ")
						.append(new TextComponent("(x" + originclient$lastCount + ")").withStyle(ChatFormatting.GRAY));
			} else {
				originclient$lastBase = base;
				originclient$lastCount = 1;
			}
		}

		if (Mods.bool("chat", "timestamps")) {
			// Append the message as a sibling of an unstyled root so it keeps its
			// own colour (white) instead of inheriting the timestamp's grey — the
			// old code styled the parent grey and the message inherited it.
			Component stamp = new TextComponent("[" + LocalTime.now().format(TIME) + "] ")
					.withStyle(ChatFormatting.DARK_GRAY);
			result = new TextComponent("").append(stamp).append(result);
		}
		return result;
	}

	// ---- Unlimited Chat: both trims live in the private 4-arg addMessage ----

	@ModifyConstant(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V",
			constant = @Constant(intValue = 100), require = 1)
	private int originclient$historyCap(int vanillaCap) {
		return originclient$opt("unlimited") ? ORIGIN_MAX_HISTORY : vanillaCap;
	}

	// ---- Keep Chat History across a disconnect ----

	@Inject(method = "clearMessages", at = @At("HEAD"), cancellable = true, require = 1)
	private void originclient$keepHistory(boolean clearSentMsgHistory, CallbackInfo ci) {
		// Only the disconnect path passes true; the manual clear (F3+D) passes false.
		if (clearSentMsgHistory && originclient$opt("keepHistory")) {
			ci.cancel();
		}
	}

	// ---- Smooth Chat: slide the stack into place instead of popping ----

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At("HEAD"), require = 1)
	private void originclient$slideStart(PoseStack poseStack, int tickCount, CallbackInfo ci) {
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
		float offset = (float) ((1.0F - eased) * 9 * getScale());
		poseStack.pushPose();
		poseStack.translate(0.0, offset, 0.0);
		originclient$slid = true;
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At("RETURN"), require = 1)
	private void originclient$slideEnd(PoseStack poseStack, int tickCount, CallbackInfo ci) {
		if (originclient$slid) {
			poseStack.popPose();
			originclient$slid = false;
		}
	}

	// ---- Text Shadow in Chat ----

	@Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;drawShadow("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I"),
			require = 1)
	private int originclient$chatShadow(Font font, PoseStack pose, FormattedCharSequence text,
										float x, float y, int color) {
		// Mod off -> vanilla behaviour (shadow on).
		if (!Mods.on("chat") || Mods.bool("chat", "textShadow")) {
			return font.drawShadow(pose, text, x, y, color);
		}
		return font.draw(pose, text, x, y, color);
	}
}
