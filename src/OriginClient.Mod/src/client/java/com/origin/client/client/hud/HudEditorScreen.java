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

// The HUD editing workspace, directly over the LIVE game — no dim, no blur.
// Freeform drag, no snapping; thin center guides appear only while dragging.
// Each element shows a SQUARE outline on its backing bounds, boldened on
// hover/select, plus one small resize handle at its FREE corner (the corner
// diagonally opposite the screen corner it's anchored to). Resizing keeps that
// anchored corner fixed and expands away from it — the same way the element
// grows in-game, so what you see here matches what you get.
//
// quick=true IS the Right Shift screen: the editable surface plus the real
// ORIGIN logo (baked nav-mark geometry) and a screen-centered MODS button.
public class HudEditorScreen extends Screen {
	private final boolean quick;
	private final long openedAt = System.currentTimeMillis();

	private String draggingId = null;
	private double dragOffX, dragOffY, dragX, dragY;
	private double dragW, dragH; // dragged element's size, for the edge clamp
	private String selectedId = null;
	private String hoveredId = null;

	// resize: fixed (anchored) corner stays put; scale grows from it
	private String resizingId = null;
	private double resizeFixedX, resizeFixedY;
	private int resizeBaseW, resizeBaseH;

	private static final int HANDLE_VIS = 5; // drawn square side (small, tucked into the corner)
	private static final int HANDLE_HIT = 6; // extra click tolerance around it

	private static final int BTN_W = 132, BTN_H = 28;

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

	// Preview mode spans the whole screen lifetime — render, measure, AND
	// input hit-testing must all see the same (possibly sample-content) box,
	// otherwise elements like Potions resize against a different size than
	// they display. That mismatch was exactly the "can't resize potions" bug.
	@Override
	protected void init() {
		super.init();
		HudElements.editorPreview = true;
	}

	@Override
	public void removed() {
		HudElements.editorPreview = false;
		super.removed();
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Intentionally nothing: the game stays perfectly visible.
	}

	private int btnX() {
		return (width - BTN_W) / 2;
	}

	private int btnY() {
		return (height - BTN_H) / 2; // perfectly centered in the screen
	}

	// The element is anchored to one screen corner; that corner stays fixed and
	// the box grows away from it. The resize handle therefore lives on the FREE
	// corner (diagonally opposite): freeLeft when right-anchored, freeTop when
	// bottom-anchored.
	private static boolean freeLeft(int anchor) {
		return anchor % 3 == 2;
	}

	private static boolean freeTop(int anchor) {
		return anchor / 3 == 2;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		float in = (float) OriginTheme.easeOut(Math.min(1.0, (System.currentTimeMillis() - openedAt) / 160.0));
		String hovered = null;

		for (HudElements.Element e : HudElements.ALL) {
			// only enabled mods appear here; positions/settings persist in the
			// config regardless, so a disabled mod comes back exactly where it was
			if (!Mods.on(e.modId())) {
				continue;
			}
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

			HudElements.drawBacking(g, (int) x, (int) y, (int) w, (int) h, pos.bg);
			// dark translucent hover highlight — content stays legible through it
			if (active) {
				g.fill((int) x - 4, (int) y - 4, (int) (x + w) + 4, (int) (y + h) + 4, 0x40303030);
			}

			// SQUARE outline on the backing bounds — thin (1px) always; hover
			// state is communicated by brightness, not thickness
			float hv = OriginUi.anim("hud:" + e.id(), active, 120.0);
			int edge = OriginTheme.lerpColor(OriginTheme.STROKE_STRONG, 0xF0FFFFFF, hv);
			squareOutline(g, (int) x - 4, (int) y - 4, (int) (x + w) + 4, (int) (y + h) + 4, 1, edge);

			var p = g.pose();
			p.pushPose();
			p.translate(x, y, 0);
			p.scale((float) pos.scale, (float) pos.scale, 1f);
			try {
				e.renderer().render(g, mc, size[0], size[1]);
			} catch (Throwable ignored) {
				// keep editing even if one element can't preview
			}
			p.popPose();

			// small resize handle tucked neatly into the FREE outline corner
			if (active) {
				int ox0 = (int) x - 4, oy0 = (int) y - 4, ox1 = (int) (x + w) + 4, oy1 = (int) (y + h) + 4;
				int hx0 = freeLeft(pos.anchor) ? ox0 : ox1 - HANDLE_VIS;
				int hy0 = freeTop(pos.anchor) ? oy0 : oy1 - HANDLE_VIS;
				boolean hh = mouseX >= hx0 - HANDLE_HIT && mouseX <= hx0 + HANDLE_VIS + HANDLE_HIT
						&& mouseY >= hy0 - HANDLE_HIT && mouseY <= hy0 + HANDLE_VIS + HANDLE_HIT;
				g.fill(hx0 - 1, hy0 - 1, hx0 + HANDLE_VIS + 1, hy0 + HANDLE_VIS + 1, 0xC0000000);
				g.fill(hx0, hy0, hx0 + HANDLE_VIS, hy0 + HANDLE_VIS, hh ? 0xFFFFFFFF : 0xF0F0F0F0);
			}

			// center alignment guides while dragging (drawn only, never snapped)
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
			int cx = width / 2;
			int bt = btnY();
			// just the mark, sitting a little above the centered button
			OriginUi.glow(g, cx, bt - 40, 110, 0.16f * in);
			OriginUi.logo(g, cx, bt - 40, 56, in);

			// dark, clean MODS button — perfectly centered in the screen
			boolean hoverBtn = in(mouseX, mouseY, btnX(), bt, btnX() + BTN_W, bt + BTN_H);
			float hb = OriginUi.anim("quick:mods", hoverBtn, 120.0);
			OriginUi.panel(g, btnX(), bt - Math.round(hb), BTN_W, BTN_H, 9,
					withAlpha(hoverBtn ? 0xE6181818 : 0xD0101010, in),
					withAlpha(hoverBtn ? 0x66FFFFFF : OriginTheme.STROKE_STRONG, in));
			g.drawString(font, "MODS", cx - font.width("MODS") / 2, bt + 10 - Math.round(hb),
					withAlpha(OriginTheme.TEXT, in), false);
		}
	}

	private static void squareOutline(GuiGraphics g, int x0, int y0, int x1, int y1, int t, int color) {
		g.fill(x0, y0, x1, y0 + t, color);          // top
		g.fill(x0, y1 - t, x1, y1, color);          // bottom
		g.fill(x0, y0, x0 + t, y1, color);          // left
		g.fill(x1 - t, y0, x1, y1, color);          // right
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
			if (!Mods.on(e.modId())) {
				continue;
			}
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = pos.x(width, w), y = pos.y(height, h);
			int ox0 = (int) x - 4, oy0 = (int) y - 4, ox1 = (int) (x + w) + 4, oy1 = (int) (y + h) + 4;
			int hx0 = freeLeft(pos.anchor) ? ox0 : ox1 - HANDLE_VIS;
			int hy0 = freeTop(pos.anchor) ? oy0 : oy1 - HANDLE_VIS;
			// free-corner handle first, so it wins over the body
			if (mx >= hx0 - HANDLE_HIT && mx <= hx0 + HANDLE_VIS + HANDLE_HIT
					&& my >= hy0 - HANDLE_HIT && my <= hy0 + HANDLE_VIS + HANDLE_HIT) {
				selectedId = e.id();
				resizingId = e.id();
				// anchored (fixed) element corner = opposite the free corner
				resizeFixedX = freeLeft(pos.anchor) ? x + w : x;
				resizeFixedY = freeTop(pos.anchor) ? y + h : y;
				resizeBaseW = size[0];
				resizeBaseH = size[1];
				return true;
			}
			if (mx >= x - 4 && mx < x + w + 4 && my >= y - 4 && my < y + h + 4) {
				selectedId = e.id();
				draggingId = e.id();
				dragOffX = mx - x;
				dragOffY = my - y;
				dragX = x;
				dragY = y;
				dragW = w;
				dragH = h;
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
			if (e != null && resizeBaseW > 0 && resizeBaseH > 0) {
				// one HudPos instance: pos() loads a fresh object each call, so
				// setting scale and saving must use the SAME reference.
				HudPos pos = e.pos();
				// scale from the anchored corner: how far the cursor is from it,
				// relative to the element's base size (dominant axis wins)
				double sx = Math.abs(mx - resizeFixedX) / resizeBaseW;
				double sy = Math.abs(my - resizeFixedY) / resizeBaseH;
				// cap so the growing edge can't leave the screen either
				double availW = freeLeft(pos.anchor) ? resizeFixedX : (width - resizeFixedX);
				double availH = freeTop(pos.anchor) ? resizeFixedY : (height - resizeFixedY);
				double maxS = Math.min(2.5, Math.min(availW / resizeBaseW, availH / resizeBaseH));
				pos.scale = Math.max(0.5, Math.min(maxS, Math.max(sx, sy)));
				pos.save(e.id());
			}
			return true;
		}
		if (draggingId != null) {
			// locked fully on-screen: the whole element stays within the window
			// bounds (elements may still overlap each other freely)
			dragX = Math.max(0, Math.min(width - dragW, mx - dragOffX));
			dragY = Math.max(0, Math.min(height - dragH, my - dragOffY));
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
