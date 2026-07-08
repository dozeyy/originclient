package com.origin.client.client.hud;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// Drag-and-drop HUD layout editor: every Origin HUD element appears as a
// draggable box (enabled ones solid, disabled ones ghosted so they can
// still be placed). Dragging snaps to an 8px grid with center alignment
// guides; dropping re-derives the element's screen-edge anchor so the
// position holds across resolutions. R while hovering resets that element,
// scroll wheel rescales it. The vanilla HUD stays fixed by design.
public class HudEditorScreen extends Screen {
	private static final int GRID = 8;

	private String draggingId = null;
	private double dragOffX, dragOffY;
	private double dragX, dragY;

	public HudEditorScreen() {
		super(Component.literal("HUD Editor"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Dim the world slightly; no full background (the game stays visible
		// so placement is done in context).
		g.fill(0, 0, width, height, 0x50000000);

		Minecraft mc = Minecraft.getInstance();
		String hovered = null;

		for (HudElements.Element e : HudElements.ALL) {
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x, y;
			if (e.id().equals(draggingId)) {
				x = snap(dragX);
				y = snap(dragY);
			} else {
				x = pos.x(width, w);
				y = pos.y(height, h);
			}

			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
			if (hover) {
				hovered = e.id();
			}
			boolean enabled = Mods.on(e.modId());

			// Box behind the element (the drag handle).
			int fill = e.id().equals(draggingId) ? 0x40FFFFFF : hover ? 0x30FFFFFF : 0x18FFFFFF;
			g.fill((int) x - 2, (int) y - 2, (int) (x + w) + 2, (int) (y + h) + 2, fill);
			int border = enabled ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE;
			hline(g, (int) x - 2, (int) (x + w) + 2, (int) y - 2, border);
			hline(g, (int) x - 2, (int) (x + w) + 2, (int) (y + h) + 1, border);
			vline(g, (int) x - 2, (int) y - 2, (int) (y + h) + 2, border);
			vline(g, (int) (x + w) + 1, (int) y - 2, (int) (y + h) + 2, border);

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
		}

		// Center alignment guides while dragging.
		if (draggingId != null) {
			vline(g, width / 2, 0, height, 0x30FFFFFF);
			hline(g, 0, width, height / 2, 0x30FFFFFF);
		}

		String hint = "Drag to move  ·  Scroll to resize  ·  R to reset  ·  Esc to save & close";
		g.drawString(font, hint, (width - font.width(hint)) / 2, height - 16, OriginTheme.MUTED, false);
		super.render(g, mouseX, mouseY, partialTick);

		this.hoveredId = hovered;
	}

	private String hoveredId = null;

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			Minecraft mc = Minecraft.getInstance();
			// Topmost hit wins (iterate in reverse draw order).
			for (int i = HudElements.ALL.size() - 1; i >= 0; i--) {
				var e = HudElements.ALL.get(i);
				HudPos pos = e.pos();
				int[] size = e.measure().apply(mc);
				double w = size[0] * pos.scale, h = size[1] * pos.scale;
				double x = pos.x(width, w), y = pos.y(height, h);
				if (mx >= x && mx < x + w && my >= y && my < y + h) {
					draggingId = e.id();
					dragOffX = mx - x;
					dragOffY = my - y;
					dragX = x;
					dragY = y;
					return true;
				}
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (draggingId != null) {
			dragX = mx - dragOffX;
			dragY = my - dragOffY;
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (draggingId != null) {
			var e = byId(draggingId);
			if (e != null) {
				Minecraft mc = Minecraft.getInstance();
				HudPos pos = e.pos();
				int[] size = e.measure().apply(mc);
				pos.setFromAbsolute(snap(dragX), snap(dragY), width, height,
						size[0] * pos.scale, size[1] * pos.scale);
				pos.save(e.id());
			}
			draggingId = null;
			return true;
		}
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (hoveredId != null) {
			var e = byId(hoveredId);
			if (e != null) {
				HudPos pos = e.pos();
				pos.scale = Math.max(0.5, Math.min(2.5, pos.scale + sy * 0.1));
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

	private static double snap(double v) {
		return Math.round(v / GRID) * (double) GRID;
	}

	private static void hline(GuiGraphics g, int x0, int x1, int y, int color) {
		g.fill(x0, y, x1, y + 1, color);
	}

	private static void vline(GuiGraphics g, int x, int y0, int y1, int color) {
		g.fill(x, y0, x + 1, y1, color);
	}
}
