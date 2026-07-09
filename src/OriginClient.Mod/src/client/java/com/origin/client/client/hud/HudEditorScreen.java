package com.origin.client.client.hud;

import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// The HUD editing workspace: a lightweight layer directly over the LIVE game
// — no dim, no blur, so overlays are positioned against real gameplay.
// Completely freeform: pixel-precise drag, no snapping; thin center guides
// appear only while an element passes near them. Hovering an element shows a
// dark translucent highlight + a bold outline + the single top-right resize
// handle; scroll also scales the hovered element.
//
// quick mode IS the Right Shift screen (spec: "I should be able to edit my
// screen the moment I press Right Shift"): the same editable surface plus the
// ORIGIN logo (same mark+wordmark as the launcher's corner) and one dark MODS
// button that leads to the full grid.
public class HudEditorScreen extends Screen {
	private final boolean quick;
	private final long openedAt = System.currentTimeMillis();

	private String draggingId = null;
	private double dragOffX, dragOffY, dragX, dragY;
	private String selectedId = null;
	private String hoveredId = null;

	// resize via the single top-right handle
	private String resizingId = null;
	private double resizeElemX;
	private int resizeBaseW;
	private static final int HANDLE = 8;

	private static final int BTN_W = 132, BTN_H = 26;

	public HudEditorScreen() {
		this(false);
	}

	public HudEditorScreen(boolean quick) {
		super(Component.literal(quick ? "Origin" : "HUD Editor"));
		this.quick = quick;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Intentionally nothing: the game stays perfectly visible.
	}

	private int btnX() {
		return (width - BTN_W) / 2;
	}

	private int btnY() {
		return height - BTN_H - 24;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		float in = (float) OriginTheme.easeOut(Math.min(1.0, (System.currentTimeMillis() - openedAt) / 160.0));
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
			boolean active = hover || e.id().equals(selectedId) || e.id().equals(draggingId) || e.id().equals(resizingId);

			// element's own backing, then a dark translucent hover highlight the
			// content stays legible through
			HudElements.drawBacking(g, (int) x, (int) y, (int) w, (int) h, pos.bg);
			if (active) {
				g.fill((int) x - 2, (int) y - 2, (int) (x + w) + 2, (int) (y + h) + 2, 0x48303030);
			}

			// box around every element; bold when hovered/selected
			float hv = OriginUi.anim("hud:" + e.id(), active, 120.0);
			int frame = OriginTheme.lerpColor(OriginTheme.STROKE_STRONG, 0xE6FFFFFF, hv);
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

			// the single top-right resize handle
			if (active) {
				int hxp = (int) (x + w), hyp = (int) y;
				boolean hh = Math.abs(mouseX - hxp) <= HANDLE && Math.abs(mouseY - hyp) <= HANDLE;
				g.fill(hxp - HANDLE / 2 - 1, hyp - HANDLE / 2 - 1, hxp + HANDLE / 2 + 1, hyp + HANDLE / 2 + 1, 0xB0000000);
				g.fill(hxp - HANDLE / 2, hyp - HANDLE / 2, hxp + HANDLE / 2, hyp + HANDLE / 2, hh ? 0xFFFFFFFF : 0xE0E0E0E0);
			}

			// center alignment guides: drawn only, never snapped to
			if (e.id().equals(draggingId)) {
				double ccx = x + w / 2, ccy = y + h / 2;
				if (Math.abs(ccx - width / 2.0) < 5) {
					g.fill(width / 2, 0, width / 2 + 1, height, 0x50FFFFFF);
				}
				if (Math.abs(ccy - height / 2.0) < 5) {
					g.fill(0, height / 2, width, height / 2 + 1, 0x50FFFFFF);
				}
			}
		}
		this.hoveredId = hovered;

		if (quick) {
			// ORIGIN logo — same mark + wordmark as the launcher's corner
			int cx = width / 2;
			int ly = 26;
			OriginUi.glow(g, cx, ly, 90, 0.14f * in);
			OriginUi.mark(g, cx - 28, ly, 16, in);
			g.drawString(font, "ORIGIN", cx - 12, ly - 4, withAlpha(OriginTheme.TEXT, in), false);

			// dark MODS button, same chip language as the menu
			boolean hoverBtn = in(mouseX, mouseY, btnX(), btnY(), btnX() + BTN_W, btnY() + BTN_H);
			float hb = OriginUi.anim("quick:mods", hoverBtn, 120.0);
			OriginUi.panel(g, btnX(), btnY() - Math.round(hb), BTN_W, BTN_H, 9,
					withAlpha(hoverBtn ? 0xE6181818 : 0xD0101010, in),
					withAlpha(hoverBtn ? 0x66FFFFFF : OriginTheme.STROKE_STRONG, in));
			g.drawString(font, "MODS", cx - font.width("MODS") / 2, btnY() + 9 - Math.round(hb),
					withAlpha(OriginTheme.TEXT, in), false);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != 0) {
			return super.mouseClicked(mx, my, button);
		}
		if (quick && in(mx, my, btnX(), btnY(), btnX() + BTN_W, btnY() + BTN_H)) {
			Minecraft.getInstance().setScreen(new OriginModMenuScreen());
			return true;
		}

		Minecraft mc = Minecraft.getInstance();
		for (int i = HudElements.ALL.size() - 1; i >= 0; i--) {
			var e = HudElements.ALL.get(i);
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = pos.x(width, w), y = pos.y(height, h);
			// top-right handle first, so it wins over the body
			if (Math.abs(mx - (x + w)) <= HANDLE && Math.abs(my - y) <= HANDLE) {
				selectedId = e.id();
				resizingId = e.id();
				resizeElemX = x;
				resizeBaseW = size[0];
				return true;
			}
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

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (resizingId != null) {
			var e = byId(resizingId);
			if (e != null && resizeBaseW > 0) {
				HudPos pos = e.pos();
				pos.scale = Math.max(0.5, Math.min(2.5, (mx - resizeElemX) / resizeBaseW));
				pos.save(e.id());
			}
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
		if (resizingId != null) {
			resizingId = null;
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
		if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			onClose();
			return true;
		}
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

	private static int withAlpha(int argb, float alpha) {
		int a = (int) (((argb >>> 24) & 0xFF) * alpha);
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
