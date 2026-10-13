package com.origin.client.client.mixin.server;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

// Chat mod, message-side behaviour (1.8.9: GuiNewChat, which neither Forge nor
// OptiFine replaces, so INVOKE/constant injection points inside it are safe):
//   Timestamps -- prepends a muted [HH:mm]. Every NEW message funnels through
//                printChatMessageWithOptionalDeletion(IChatComponent, int)
//                (printChatMessage delegates to it with id 0); refreshChat()
//                re-adds history through the private setChatLine directly, so
//                hooking the public one never double-stamps on a chat re-wrap.
//   Stack Spam -- when a message repeats the previous one, the earlier copy is
//                removed and the new one gets a running "(xN)" counter, so spam
//                collapses to a single updating line.
//   Unlimited  -- both 100-line trims live in setChatLine.
//   Smooth     -- drawChat(int) HEAD/RETURN brackets the whole stack in one
//                GL translate.
//   Shadow     -- drawChat draws each line with FontRenderer.drawStringWithShadow
//                (an INVOKE inside GuiNewChat, safe with OptiFine's FontRenderer:
//                the call site is vanilla's).
// Keep Chat History: 1.8.9 itself never clears chat on disconnect (only F3+D
// clears it, which should stay), so there is nothing to hook on this version.
// Opacity/scale ride the vanilla chat options from the feature tick.
@Mixin(GuiNewChat.class)
public abstract class ChatTimestampMixin {
	@Unique
	private static final DateTimeFormatter originclient$TIME = DateTimeFormatter.ofPattern("HH:mm");

	@Shadow @Final private List<ChatLine> chatLines;

	@Shadow
	public abstract void refreshChat();

	@Shadow
	public abstract float getChatScale();

	// "Unlimited" with a guard rail: kept lines are never released otherwise, so
	// this is the memory ceiling (~80x vanilla, a few MB of text at worst).
	@Unique
	private static final int ORIGIN_MAX_HISTORY = 8192;
	// Slide-in duration. Short on purpose -- chat must never feel laggy.
	@Unique
	private static final long SLIDE_MS = 140L;

	@Unique
	private static long originclient$lastMessageMs = 0L;
	@Unique
	private boolean originclient$slid;
	@Unique
	private static String originclient$lastBase = null;
	@Unique
	private static int originclient$lastCount = 1;

	@Unique
	private static boolean originclient$opt(String key) {
		return Mods.on("chat") && Mods.bool("chat", key);
	}

	@ModifyVariable(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), argsOnly = true)
	private IChatComponent originclient$transform(IChatComponent message) {
		originclient$lastMessageMs = Minecraft.getSystemTime();
		if (message == null) {
			return null;
		}
		try {
			if (!Mods.on("chat")) {
				return message;
			}
			IChatComponent result = message;

			if (Mods.bool("chat", "stackSpam")) {
				String base = message.getUnformattedText();
				if (base.equals(originclient$lastBase) && !chatLines.isEmpty()) {
					originclient$lastCount++;
					chatLines.remove(0);          // drop the previous identical line (index 0 = newest)
					refreshChat();
					// 1.8.9: keep the line's own styles (a copy), counter appended in grey.
					result = message.createCopy();
					result.appendSibling(new ChatComponentText(" (x" + originclient$lastCount + ")")
							.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY)));
				} else {
					originclient$lastBase = base;
					originclient$lastCount = 1;
				}
			}

			if (Mods.bool("chat", "timestamps")) {
				// The message is a sibling of an unstyled root so it keeps its own
				// colour instead of inheriting the timestamp's grey.
				IChatComponent stamp = new ChatComponentText("[" + LocalTime.now().format(originclient$TIME) + "] ")
						.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));
				IChatComponent root = new ChatComponentText("");
				root.appendSibling(stamp);
				root.appendSibling(result);
				result = root;
			}
			return result;
		} catch (Throwable t) {
			return message;
		}
	}

	// ---- Unlimited Chat: both trims (chatLines and drawnChatLines) live in setChatLine ----

	@ModifyConstant(method = "setChatLine", constant = @Constant(intValue = 100))
	private int originclient$historyCap(int vanillaCap) {
		return originclient$opt("unlimited") ? ORIGIN_MAX_HISTORY : vanillaCap;
	}

	// ---- Smooth Chat: slide the stack into place instead of popping ----

	@Inject(method = "drawChat", at = @At("HEAD"))
	private void originclient$slideStart(int updateCounter, CallbackInfo ci) {
		originclient$slid = false;
		if (!originclient$opt("smoothChat")) {
			return;
		}
		long since = Minecraft.getSystemTime() - originclient$lastMessageMs;
		if (since < 0 || since >= SLIDE_MS) {
			return;
		}
		// Ease-out cubic: fast off the mark, settles softly -- the house motion feel.
		float t = (float) since / SLIDE_MS;
		float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
		float offset = (1.0F - eased) * 9 * getChatScale();
		GlStateManager.pushMatrix();
		GlStateManager.translate(0.0F, offset, 0.0F);
		originclient$slid = true;
	}

	@Inject(method = "drawChat", at = @At("RETURN"))
	private void originclient$slideEnd(int updateCounter, CallbackInfo ci) {
		if (originclient$slid) {
			GlStateManager.popMatrix();
			originclient$slid = false;
		}
	}

	// ---- Text Shadow in Chat ----

	@Redirect(method = "drawChat", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;FFI)I"))
	private int originclient$chatShadow(FontRenderer font, String text, float x, float y, int color) {
		// Mod off -> vanilla behaviour (shadow on).
		if (!Mods.on("chat") || Mods.bool("chat", "textShadow")) {
			return font.drawStringWithShadow(text, x, y, color);
		}
		return font.drawString(text, x, y, color, false);
	}
}
