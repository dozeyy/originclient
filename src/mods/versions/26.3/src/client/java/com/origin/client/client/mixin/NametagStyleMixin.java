package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nametag STYLING for the Nametags mod: text shadow, opacity, a custom background
 * colour, and independent overrides for your OWN tag.
 *
 * <p>PER-VERSION DELTA (1.21.11): on 1.21.1 this redirected the two
 * {@link Font#drawInBatch} calls inside {@code EntityRenderer.renderNameTag} and
 * read the entity straight out of the captured parameters. Both of those are
 * gone here — name tags are now EXTRACTED into
 * {@code SubmitNodeStorage.NameTagSubmit} records during entity submission and
 * drawn later, in one batch, by the name-tag feature renderer. That
 * method still makes exactly the same pair of drawInBatch calls (a faint
 * SEE_THROUGH pass with a black background box, then a solid NORMAL pass with
 * none), so the redirect still works — it just lives here instead.
 *
 * <p>The one thing the new shape cannot give us is entity identity: a
 * NameTagSubmit carries only the rendered {@link Component}, not the entity it
 * came from. "Your own tag" is therefore matched by comparing the text against
 * the local player's display name. That is exact in every normal case and can
 * only ever mis-fire on another entity literally renamed to your own username —
 * in which case it restyles that tag too, which is cosmetic and harmless.
 *
 * <p>Every option is read live and gated on the mod being on, so it fails soft
 * to vanilla the instant the mod is off.
 */
@Mixin(SubmitNodeCollection.class)
public class NametagStyleMixin {

	// 26.3: name tags are generic world-text submits (TextFeatureRenderer.Submit
	// holding a Content.Text: x, y, text, dropShadow, colour, background, outline).
	// SubmitNodeCollection.submitNameTag builds each pass (solid NORMAL + faint
	// SEE_THROUGH with the background box) through the private static
	// nameTag(pose, x, y, text, light, colour, background, mode) factory (javap -c,
	// 26.3) -- the one place every tag passes, so the RETURN value is restyled there.
	// Content.Text carries a drop-shadow flag again, so Text Shadow works on 26.3.
	@Inject(method = "nameTag", at = @At("RETURN"), cancellable = true)
	private static void originclient$styleNameTag(Matrix4f pose, float x, float y, FormattedCharSequence text,
			int light, int color, int bgColor, Font.DisplayMode mode,
			CallbackInfoReturnable<TextFeatureRenderer.Submit> cir) {
		try {
			TextFeatureRenderer.Submit s = cir.getReturnValue();
			if (s == null) {
				return;
			}
			String plain = originclient$plain(text);
			// Friends & Party: a party member's tag in the party colour (LAN worlds only;
			// PartyWorld decides, matching the tag's text -- see originclient$isSelf for
			// why there is no entity here). Keeps vanilla's per-pass alpha like the colours below.
			Integer party = null;
			try {
				party = com.origin.client.client.social.PartyWorld.nametagTint(Component.literal(plain));
			} catch (Throwable ignored) {
				// no tint
			}
			if (party != null) {
				color = (color & 0xFF000000) | (party & 0xFFFFFF);
			}
			if (!Mods.on("nametags")) {
				if (party != null) {
					cir.setReturnValue(originclient$with(s, x, y, text, false, color, bgColor));
				}
				return;
			}
			boolean self = originclient$isSelf(plain);
			boolean over = self && Mods.bool("nametags", "ownOverride");
			double op = Mods.num("nametags", "opacity");
			if (op <= 0.0) {
				op = 1.0;
			}
			// Text colour: keep vanilla's per-pass ALPHA (that's what makes the two-pass
			// see-through/solid look), swap only the RGB. Priority: your own override (if
			// self) -> the party colour -> the global custom text colour -> vanilla/team.
			int rgb = color & 0xFFFFFF;
			if (over) {
				rgb = Mods.color("nametags", "ownTextColor") & 0xFFFFFF;
			} else if (party != null) {
				// the party colour wins over the global custom colour
			} else if (Mods.bool("nametags", "overrideColor")) {
				rgb = Mods.color("nametags", "textColor") & 0xFFFFFF;
			}
			int newColor = originclient$scaleAlpha((color & 0xFF000000) | rgb, op);
			// Background box: only the pass that actually has one (alpha != 0).
			int newBg = bgColor;
			if (((bgColor >>> 24) & 0xFF) != 0) {
				int bg = over ? Mods.color("nametags", "ownBackgroundColor") : Mods.color("nametags", "backgroundColor");
				newBg = originclient$scaleAlpha(bg, op);
			}
			// Text Shadow on the solid pass only (the see-through pass is the faint
			// "behind a wall" copy; a shadow there would double up through walls).
			boolean shadow = mode == Font.DisplayMode.NORMAL && Mods.bool("nametags", "textShadow");
			cir.setReturnValue(originclient$with(s, x, y, text, shadow, newColor, newBg));
		} catch (Throwable ignored) {
			// vanilla's tag
		}
	}

	private static TextFeatureRenderer.Submit originclient$with(TextFeatureRenderer.Submit s, float x, float y,
			FormattedCharSequence text, boolean shadow, int color, int bg) {
		return new TextFeatureRenderer.Submit(s.pose(), s.displayMode(), s.lightCoords(),
				new TextFeatureRenderer.Content.Text(x, y, text, shadow, color, bg, 0));
	}

	private static String originclient$plain(FormattedCharSequence text) {
		StringBuilder sb = new StringBuilder();
		text.accept((index, style, codePoint) -> {
			sb.appendCodePoint(codePoint);
			return true;
		});
		return sb.toString();
	}

	private static boolean originclient$isSelf(String text) {
		try {
			Minecraft mc = Minecraft.getInstance();
			return mc.player != null && mc.player.getDisplayName() != null
					&& mc.player.getDisplayName().getString().equals(text);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static int originclient$scaleAlpha(int argb, double f) {
		int a = (int) Math.round(((argb >>> 24) & 0xFF) * f);
		a = Math.max(0, Math.min(255, a));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
