package com.origin.client.client.gui;

import net.minecraft.client.gui.GuiGraphics;

// Tiny vector glyphs for the mod grid, drawn with fills on an 18x18 cell —
// no textures, no font, so they stay crisp at any GUI scale and cost
// nothing. Each glyph is an abstract read of what the mod does, in the
// mono palette.
public final class ModIcons {
	private ModIcons() {
	}

	/** Draws the icon for a mod id with its top-left at (x,y), 18x18. */
	public static void draw(GuiGraphics g, String id, int x, int y, int color) {
		int c = color;
		int dim = (c & 0xFFFFFF) | 0x60000000;
		switch (id) {
			case "fps" -> { // speedo arc + needle
				box(g, x + 2, y + 10, 14, 2, dim);
				box(g, x + 4, y + 6, 2, 4, dim);
				box(g, x + 12, y + 6, 2, 4, dim);
				box(g, x + 8, y + 4, 2, 8, c);
			}
			case "cps" -> { // mouse with split buttons
				box(g, x + 5, y + 2, 8, 14, dim);
				box(g, x + 5, y + 2, 4, 6, c);
			}
			case "coords" -> { // crosshair + point
				box(g, x + 8, y + 2, 2, 14, dim);
				box(g, x + 2, y + 8, 14, 2, dim);
				box(g, x + 11, y + 4, 3, 3, c);
			}
			case "keystrokes" -> { // WASD cluster
				box(g, x + 7, y + 3, 4, 4, c);
				box(g, x + 2, y + 8, 4, 4, dim);
				box(g, x + 7, y + 8, 4, 4, dim);
				box(g, x + 12, y + 8, 4, 4, dim);
				box(g, x + 4, y + 13, 10, 2, dim);
			}
			case "potionhud" -> { // bottle
				box(g, x + 7, y + 2, 4, 3, dim);
				box(g, x + 5, y + 5, 8, 10, dim);
				box(g, x + 6, y + 9, 6, 5, c);
			}
			case "armorhud" -> { // chestplate
				box(g, x + 4, y + 3, 10, 4, c);
				box(g, x + 5, y + 7, 8, 8, dim);
			}
			case "serveraddress" -> { // globe/net
				box(g, x + 3, y + 3, 12, 12, dim);
				box(g, x + 8, y + 3, 2, 12, c);
				box(g, x + 3, y + 8, 12, 2, c);
			}
			case "packdisplay" -> { // package
				box(g, x + 3, y + 5, 12, 10, dim);
				box(g, x + 3, y + 5, 12, 3, c);
				box(g, x + 8, y + 5, 2, 10, c);
			}
			case "zoom" -> { // magnifier
				ring(g, x + 4, y + 4, 8, c);
				box(g, x + 11, y + 11, 4, 2, c);
			}
			case "freelook" -> { // eye
				box(g, x + 3, y + 7, 12, 4, dim);
				box(g, x + 7, y + 6, 4, 6, c);
			}
			case "togglesprint" -> { // fast arrows
				box(g, x + 3, y + 5, 6, 2, dim);
				box(g, x + 3, y + 11, 6, 2, dim);
				box(g, x + 9, y + 4, 6, 2, c);
				box(g, x + 9, y + 8, 6, 2, c);
				box(g, x + 9, y + 12, 6, 2, c);
			}
			case "togglesneak" -> { // low profile
				box(g, x + 3, y + 11, 12, 3, c);
				box(g, x + 6, y + 8, 6, 3, dim);
			}
			case "fullbright" -> { // sun
				box(g, x + 6, y + 6, 6, 6, c);
				box(g, x + 8, y + 2, 2, 3, dim);
				box(g, x + 8, y + 13, 2, 3, dim);
				box(g, x + 2, y + 8, 3, 2, dim);
				box(g, x + 13, y + 8, 3, 2, dim);
			}
			case "blockoverlay" -> { // outlined cube
				ring(g, x + 3, y + 3, 12, c);
				box(g, x + 6, y + 6, 6, 6, dim);
			}
			case "chunkborders" -> { // grid
				for (int i = 0; i <= 2; i++) {
					box(g, x + 3, y + 3 + i * 5, 12, 1, i == 1 ? c : dim);
					box(g, x + 3 + i * 5, y + 3, 1, 12, i == 1 ? c : dim);
				}
			}
			case "hitboxes" -> { // box around figure
				ring(g, x + 3, y + 2, 12, dim);
				box(g, x + 7, y + 5, 4, 4, c);
				box(g, x + 8, y + 9, 2, 5, c);
			}
			case "nametags" -> { // tag above head
				box(g, x + 4, y + 3, 10, 4, c);
				box(g, x + 7, y + 9, 4, 4, dim);
				box(g, x + 8, y + 13, 2, 3, dim);
			}
			case "weather" -> { // struck rain
				box(g, x + 4, y + 4, 10, 4, dim);
				box(g, x + 5, y + 10, 2, 4, dim);
				box(g, x + 9, y + 10, 2, 4, dim);
				diag(g, x + 3, y + 3, 12, c);
			}
			case "customsky" -> { // horizon
				box(g, x + 3, y + 10, 12, 2, c);
				box(g, x + 5, y + 4, 4, 4, dim);
			}
			case "timechanger" -> { // clock
				ring(g, x + 3, y + 3, 12, dim);
				box(g, x + 8, y + 5, 2, 5, c);
				box(g, x + 8, y + 9, 4, 2, c);
			}
			case "motionblur" -> { // trailing bars
				box(g, x + 10, y + 4, 4, 10, c);
				box(g, x + 6, y + 5, 3, 8, dim);
				box(g, x + 3, y + 6, 2, 6, (c & 0xFFFFFF) | 0x30000000);
			}
			case "particles" -> { // scatter
				box(g, x + 4, y + 4, 3, 3, c);
				box(g, x + 11, y + 5, 2, 2, dim);
				box(g, x + 6, y + 10, 2, 2, dim);
				box(g, x + 11, y + 11, 3, 3, c);
			}
			case "chat" -> { // speech bubble
				box(g, x + 3, y + 4, 12, 8, dim);
				box(g, x + 5, y + 12, 3, 3, dim);
				box(g, x + 5, y + 7, 8, 2, c);
			}
			case "scoreboard" -> { // list panel
				box(g, x + 3, y + 3, 12, 12, dim);
				box(g, x + 5, y + 5, 6, 2, c);
				box(g, x + 5, y + 8, 8, 2, c);
				box(g, x + 5, y + 11, 5, 2, c);
			}
			default -> ring(g, x + 3, y + 3, 12, c);
		}
	}

	private static void box(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + h, color);
	}

	private static void ring(GuiGraphics g, int x, int y, int size, int color) {
		g.fill(x, y, x + size, y + 1, color);
		g.fill(x, y + size - 1, x + size, y + size, color);
		g.fill(x, y, x + 1, y + size, color);
		g.fill(x + size - 1, y, x + size, y + size, color);
	}

	private static void diag(GuiGraphics g, int x, int y, int len, int color) {
		for (int i = 0; i < len; i += 2) {
			g.fill(x + i, y + i, x + i + 2, y + i + 2, color);
		}
	}
}
