package com.origin.client.client.gui;

import com.origin.client.client.mods.ItemSizes;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.ResourceLocation;

/**
 * The Item Size Customizer — set a custom dropped-item render size per item.
 *
 * The shared item grid (OriginItemGridScreen); click an item to select it, then
 * drag the size slider (0.25x–3.0x) at the bottom. Sizes persist immediately via
 * {@link ItemSizes} and apply live to dropped items in-world (ItemEntityScaleMixin).
 *
 * PER-VERSION DELTA (1.21.10): ResourceLocation ids; drag/release arrive as MouseButtonEvent.
 */
public class OriginItemSizeScreen extends OriginItemGridScreen {

	private ResourceLocation selected = null;
	private boolean draggingSlider = false;

	public OriginItemSizeScreen() {
		super("Item Size", "Set dropped-item render sizes per item.", "item-size");
	}

	@Override
	protected void cellClicked(Entry e) {
		selected = e.id().equals(selected) ? null : e.id();
	}

	@Override
	protected boolean isSelected(Entry e) {
		return e.id().equals(selected);
	}

	@Override
	protected boolean isMarked(Entry e) {
		return ItemSizes.isCustom(e.id());
	}

	@Override
	protected int footerSpace() {
		return selected != null ? 44 : 12;
	}

	@Override
	protected void renderFooter(GuiGraphics g, int mx, int my) {
		if (selected != null) {
			renderSizeBar(g, mx, my);
		}
	}

	private void renderSizeBar(GuiGraphics g, int mx, int my) {
		Entry e = entryById(selected);
		if (e == null) {
			selected = null;
			return;
		}
		int by = py() + ph() - 38;
		int bx0 = gridLeft(), bx1 = gridRight();
		OriginUi.panel(g, bx0, by, bx1 - bx0, 30, 8, OriginTheme.BOX_FILL, OriginTheme.BOX_BORDER);
		drawItemIcon(g, e.stack(), bx0 + 6, by + 7, 16);
		String name = OriginText.ellipsize(font, e.name(), 130);
		OriginText.draw(g, font, name, bx0 + 28, by + 11, OriginTheme.TEXT, false);

		float size = ItemSizes.get(e.id());
		// Reset button (right)
		int rW = 46, rX = bx1 - 8 - rW;
		boolean rHover = in(mx, my, rX, by + 6, rX + rW, by + 24);
		float resetHp = OriginUi.hover("item-size:reset", rHover);
		OriginUi.panel(g, rX, by + 6, rW, 18, 6,
				OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, resetHp),
				OriginTheme.lerpColor(OriginTheme.BOX_BORDER, OriginTheme.STROKE_HOVER, resetHp));
		OriginText.draw(g, font, "Reset", rX + (rW - OriginText.width(font, "Reset")) / 2, by + 11, OriginTheme.TEXT_DIM, false);

		// value label
		String val = String.format("%.2fx", size);
		int vW = OriginText.width(font, val);
		int vX = rX - 8 - vW;
		OriginText.draw(g, font, val, vX, by + 11, OriginTheme.TEXT, false);

		// slider track (between name and value)
		int trackX = bx0 + 170;
		int trackW = vX - 10 - trackX;
		if (trackW > 20) {
			double t = (size - ItemSizes.MIN) / (ItemSizes.MAX - ItemSizes.MIN);
			OriginUi.slider(g, trackX, by + 12, trackW, t, draggingSlider);
		}
	}

	/** Id -> entry, built once alongside ALL_ITEMS. entryById is called every
	 *  frame while an item is selected (the size bar draws its icon and name). */
	private static java.util.Map<ResourceLocation, Entry> BY_ID;

	private Entry entryById(ResourceLocation id) {
		if (id == null) {
			return null;
		}
		if (BY_ID == null) {
			java.util.Map<ResourceLocation, Entry> m = new java.util.HashMap<>();
			for (Entry e : allItems()) {
				m.put(e.id(), e);
			}
			BY_ID = m;
		}
		return BY_ID.get(id);
	}

	// ---- slider math ----
	private int[] sliderBounds() {
		int by = py() + ph() - 38;
		int bx0 = gridLeft(), bx1 = gridRight();
		int rW = 46, rX = bx1 - 8 - rW;
		String val = "9.99x";
		int vX = rX - 8 - OriginText.width(font, val);
		int trackX = bx0 + 170;
		int trackW = vX - 10 - trackX;
		return new int[]{trackX, by + 12, trackW};
	}

	private void applySlider(double mx) {
		int[] s = sliderBounds();
		if (s[2] <= 0) {
			return;
		}
		double t = Math.max(0, Math.min(1, (mx - s[0]) / (double) s[2]));
		double v = ItemSizes.MIN + t * (ItemSizes.MAX - ItemSizes.MIN);
		v = Math.round(v * 20) / 20.0; // 0.05 steps
		ItemSizes.set(selected, (float) v);
	}

	@Override
	protected boolean footerClicked(double mx, double my) {
		// selected size bar (slider + reset)
		if (selected != null) {
			int by = py() + ph() - 38;
			int rW = 46, rX = gridRight() - 8 - rW;
			if (in(mx, my, rX, by + 6, rX + rW, by + 24)) {
				ItemSizes.reset(selected);
				return true;
			}
			int[] s = sliderBounds();
			if (my >= by && my <= by + 30 && mx >= s[0] - 4 && mx <= s[0] + s[2] + 4) {
				draggingSlider = true;
				applySlider(mx);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingSlider) {
			applySlider(event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingSlider = false;
		return super.mouseReleased(event);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(new OriginModMenuScreen());
	}
}
