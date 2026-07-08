package com.origin.client.client.hud;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// The HUD editing workspace: a lightweight layer directly over the LIVE
// game — no dim, no blur, nothing between the player and their world, so
// overlays are positioned against real gameplay. Completely freeform: pixel
// precise drag, no grid, no snapping; thin center guides appear as a visual
// aid only when an element passes near them. Click an element to select it;
// a control card at the bottom gives smooth live sliders for its scale and
// its own background opacity, plus reset. Everything renders through the
// premium OriginUi kit.
public class HudEditorScreen extends Screen {
	private String draggingId = null;
	private double dragOffX, dragOffY, dragX, dragY;
	private String selectedId = null;
	private int dragSlider = 0; // 0 none, 1 scale, 2 bg

	public HudEditorScreen() {
		super(Component.literal("HUD Editor"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Intentionally nothing: the game stays perfectly visible.
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		String hovered = null;

		for (HudElements.Element e : HudElements.ALL) {
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = e.id().equals(draggingId) ? dragX : pos.x(width, w);
			double y = e.id().equals(draggingId) ? dragY : pos.y(height, h);

			boolean hover = mouseX >= x - 4 && mouseX < x + w + 4 && mouseY >= y - 4 && mouseY < y + h + 4;
			if (hover) {
				hovered = e.id();
			}
			boolean selected = e.id().equals(selectedId);
			boolean enabled = Mods.on(e.modId());

			// the element's own backing (what it also shows in-game), plus a
			// hairline edit frame — brighter when selected/hovered
			HudElements.drawBacking(g, (int) x, (int) y, (int) w, (int) h, pos.bg);
			float hv = OriginUi.anim("hud:" + e.id(), hover || selected, 120.0);
			int frame = selected ? 0xB4FFFFFF : OriginTheme.lerpColor(OriginTheme.STROKE, OriginTheme.STROKE_STRONG, hv);
			OriginUi.panel(g, (int) x - 3, (int) y - 3, (int) w + 6, (int) h + 6, 5, 0, frame);

			var p = g.pose();
			p.pushPose();
			p.translate(x, y, 0);
			p.scale((float) pos.scale, (float) pos.scale, 1f);
			try {
				e.renderer().render(g, mc, size[0], size[1]);
			} catch (Throwable t) {
				// keep editing even if one element can't preview
			}
			p.popPose();

			if (hover && !enabled) {
				g.drawString(font, e.label() + " (off)", (int) x, (int) y - 12, OriginTheme.MUTED, false);
			}

			// alignment guides: drawn only, never snapped to
			if (e.id().equals(draggingId)) {
				double cx = x + w / 2, cy = y + h / 2;
				if (Math.abs(cx - width / 2.0) < 5) {
					g.fill(width / 2, 0, width / 2 + 1, height, 0x50FFFFFF);
				}
				if (Math.abs(cy - height / 2.0) < 5) {
					g.fill(0, height / 2, width, height / 2 + 1, 0x50FFFFFF);
				}
			}
		}
		this.hoveredId = hovered;

		// top hint chip
		String hint = "Drag freely · click to select · Esc to save";
		int hw = font.width(hint) + 20;
		OriginUi.panel(g, (width - hw) / 2, 8, hw, 18, 8, 0x90101010, OriginTheme.STROKE);
		g.drawString(font, hint, (width - font.width(hint)) / 2, 13, OriginTheme.TEXT_DIM, false);

		// selected element control card
		if (selectedId != null) {
			var e = byId(selectedId);
			if (e != null) {
				renderControlCard(g, e, mouseX, mouseY);
			}
		}
	}

	private int cardX() {
		return (width - 300) / 2;
	}

	private int cardY() {
		return height - 66;
	}

	private void renderControlCard(GuiGraphics g, HudElements.Element e, int mx, int my) {
		int x = cardX(), y = cardY();
		HudPos pos = e.pos();
		OriginUi.panel(g, x, y, 300, 56, 10, 0xD00E0E0E, OriginTheme.STROKE_STRONG);
		g.drawString(font, e.label(), x + 12, y + 8, OriginTheme.TEXT, false);

		boolean resetHover = in(mx, my, x + 300 - 54, y + 5, x + 300 - 8, y + 21);
		OriginUi.panel(g, x + 300 - 54, y + 5, 46, 16, 7,
				resetHover ? 0x2EFFFFFF : 0x16FFFFFF, OriginTheme.STROKE);
		g.drawString(font, "Reset", x + 300 - 47, y + 9, OriginTheme.TEXT_DIM, false);

		g.drawString(font, "Size", x + 12, y + 27, OriginTheme.MUTED, false);
		OriginUi.slider(g, x + 44, y + 29, 92, (pos.scale - 0.5) / 2.0, dragSlider == 1);
		g.drawString(font, "Back", x + 152, y + 27, OriginTheme.MUTED, false);
		OriginUi.slider(g, x + 186, y + 29, 92, pos.bg, dragSlider == 2);

		String vals = String.format("%.1fx · %.0f%%", pos.scale, pos.bg * 100);
		g.drawString(font, vals, x + 12, y + 42, OriginTheme.MUTED, false);
	}

	private String hoveredId = null;

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != 0) {
			return super.mouseClicked(mx, my, button);
		}
		// control card sliders first
		if (selectedId != null && in(mx, my, cardX(), cardY(), cardX() + 300, cardY() + 56)) {
			var e = byId(selectedId);
			if (e != null) {
				int x = cardX(), y = cardY();
				if (in(mx, my, x + 300 - 54, y + 5, x + 300 - 8, y + 21)) {
					com.origin.client.client.mods.ModsConfigAccess.resetHud(e.id());
					return true;
				}
				if (my >= y + 24 && my <= y + 40) {
					if (mx >= x + 40 && mx <= x + 142) {
						dragSlider = 1;
					} else if (mx >= x + 182 && mx <= x + 284) {
						dragSlider = 2;
					}
					applyCardSlider(mx);
					return true;
				}
			}
			return true; // clicks on the card never fall through
		}

		Minecraft mc = Minecraft.getInstance();
		for (int i = HudElements.ALL.size() - 1; i >= 0; i--) {
			var e = HudElements.ALL.get(i);
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = pos.x(width, w), y = pos.y(height, h);
			if (mx >= x - 4 && mx < x + w + 4 && my >= y - 4 && my < y + h + 4) {
				selectedId = e.id();
				draggingId = e.id();
				dragOffX = mx - x;
				dragOffY = my - y;
				dragX = x;
				dragY = y;
				return true;
			}
		}
		selectedId = null;
		return super.mouseClicked(mx, my, button);
	}

	private void applyCardSlider(double mx) {
		var e = byId(selectedId);
		if (e == null || dragSlider == 0) {
			return;
		}
		HudPos pos = e.pos();
		int x = cardX();
		if (dragSlider == 1) {
			double t = clamp01((mx - (x + 44)) / 92.0);
			pos.scale = 0.5 + t * 2.0;
		} else {
			pos.bg = clamp01((mx - (x + 186)) / 92.0);
		}
		pos.save(e.id());
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragSlider != 0) {
			applyCardSlider(mx);
			return true;
		}
		if (draggingId != null) {
			dragX = mx - dragOffX;
			dragY = my - dragOffY;
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (dragSlider != 0) {
			dragSlider = 0;
			return true;
		}
		if (draggingId != null) {
			var e = byId(draggingId);
			if (e != null) {
				Minecraft mc = Minecraft.getInstance();
				HudPos pos = e.pos();
				int[] size = e.measure().apply(mc);
				pos.setFromAbsolute(dragX, dragY, width, height, size[0] * pos.scale, size[1] * pos.scale);
				pos.save(e.id());
			}
			draggingId = null;
			return true;
		}
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		String target = hoveredId != null ? hoveredId : selectedId;
		if (target != null) {
			var e = byId(target);
			if (e != null) {
				HudPos pos = e.pos();
				pos.scale = Math.max(0.5, Math.min(2.5, pos.scale + sy * 0.05));
				pos.save(e.id());
				return true;
			}
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_R && hoveredId != null) {
			com.origin.client.client.mods.ModsConfigAccess.resetHud(hoveredId);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private static HudElements.Element byId(String id) {
		for (var e : HudElements.ALL) {
			if (e.id().equals(id)) {
				return e;
			}
		}
		return null;
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}

	private static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}
}
