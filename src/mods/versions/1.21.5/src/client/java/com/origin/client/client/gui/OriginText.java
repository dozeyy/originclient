package com.origin.client.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * Menu text for 1.21.5.
 *
 * <p>Draws through the Inter bitmap font provider. The MSDF vector-text path the
 * other modules use is NOT available here — see {@code disabled-msdf/README.md}
 * at the module root for why 1.21.5 needs its own port rather than either
 * neighbour's. Only glyphs are affected: panels, buttons and the vector icons
 * are anti-aliased on this version like everywhere else.
 */
public final class OriginText {
	public static final ResourceLocation REGULAR =
			ResourceLocation.fromNamespaceAndPath("originclient", "inter");
	public static final ResourceLocation SEMIBOLD =
			ResourceLocation.fromNamespaceAndPath("originclient", "inter_semibold");

	private OriginText() {
	}

	public static MutableComponent regular(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(REGULAR));
	}

	public static MutableComponent semibold(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(SEMIBOLD));
	}

	public static int draw(GuiGraphics g, Font font, String s, int x, int y, int color, boolean shadow) {
		MutableComponent c = regular(s);
		g.drawString(font, c, x, y, color, shadow);
		return x + font.width(c);
	}

	public static int drawBold(GuiGraphics g, Font font, String s, int x, int y, int color, boolean shadow) {
		MutableComponent c = semibold(s);
		g.drawString(font, c, x, y, color, shadow);
		return x + font.width(c);
	}

	public static int width(Font font, String s) {
		return font.width(regular(s));
	}

	public static int widthBold(Font font, String s) {
		return font.width(semibold(s));
	}

	public static String ellipsize(Font font, String s, int maxW) {
		if (width(font, s) <= maxW) {
			return s;
		}
		String ell = "…";
		int ew = width(font, ell);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			if (width(font, sb.toString() + s.charAt(i)) + ew > maxW) {
				break;
			}
			sb.append(s.charAt(i));
		}
		return sb.append(ell).toString();
	}
}
