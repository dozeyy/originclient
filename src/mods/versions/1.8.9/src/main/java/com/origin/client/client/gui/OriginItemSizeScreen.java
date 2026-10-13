package com.origin.client.client.gui;

import com.origin.client.client.mods.ItemSizes;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;

import java.util.Locale;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * The Item Size Customizer — set a custom dropped-item render size per item.
 *
 * The shared item grid (OriginItemGridScreen); click an item to select it, then
 * drag the size slider (0.25x–3.0x) in the footer bar. Sizes persist immediately
 * via {@link ItemSizes} and apply live to dropped items in-world
 * (the world area's dropped-item render hook). Back / Esc / close return to the mod menu.
 *
 * 1.8.9: sizes stay keyed by registry id (one cell per id -- see
 * OriginItemGridScreen); input is 1.16.5's, through the OriginScreen bridge.
 */
public class OriginItemSizeScreen extends OriginItemGridScreen {

	private ResourceLocation selected = null;
	private boolean draggingSlider = false;
	private float sliderX0, sliderX1;   // the track, recorded while drawing (design px)

	public OriginItemSizeScreen() {
		this(new OriginModMenuScreen());
	}

	public OriginItemSizeScreen(GuiScreen returnTo) {
		super("Item Size", "Set dropped-item render sizes per item.", "item-size", "itemsize", returnTo);
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
	protected float footerH() {
		return selected != null ? 56 : 0;
	}

	// The selected item's bar: icon + name | size slider | value | Reset.
	@Override
	protected void drawFooter(OriginDeck d, float x, float y, float w, float h) {
		Entry e = entryById(selected);
		if (e == null) {
			selected = null;
			return;
		}
		d.rect(x, y, w, h, 8, CARD, LINE);
		d.rect(x + 8, y + 8, 40, 40, 6, 0x0AFFFFFF);
		drawItem(d, e.stack(), x + 8, y + 8, 40, 32);
		d.textMid(d.fit(e.name(), 200, 14, true, 0), x + 60, y + h / 2, 14, true, 0, TEXT);

		float size = ItemSizes.get(e.id());
		float rw = buttonW(d, "Reset", "@reset");
		float rx = x + w - 12 - rw;
		boolean custom = ItemSizes.isCustom(e.id());
		button(d, "Reset", "@reset", rx, y + 12, rw, 32, Style.SECONDARY, custom, () -> ItemSizes.reset(selected));

		String val = String.format(Locale.ROOT, "%.2fx", size);
		float valW = d.textW("9.99x", 14, true, 0);
		d.textRight(val, rx - 20, y + h / 2, 14, true, 0, TEXT);

		float tx = x + 280, tw = rx - 20 - valW - 20 - tx;
		if (tw > 40) {
			double t = (size - ItemSizes.MIN) / (ItemSizes.MAX - ItemSizes.MIN);
			d.slider(tx, y + h / 2, tw, (float) t, draggingSlider);
			d.hit(tx - 8, y + 8, tw + 16, h - 16, () -> {
				draggingSlider = true;
				sliderX0 = tx;
				sliderX1 = tx + tw;
			});
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

	// ---- slider ----

	private void applySlider(double guiMx) {
		if (selected == null || sliderX1 <= sliderX0) {
			return;
		}
		double t = Math.max(0, Math.min(1, (dX(guiMx) - sliderX0) / (double) (sliderX1 - sliderX0)));
		double v = ItemSizes.MIN + t * (ItemSizes.MAX - ItemSizes.MIN);
		v = Math.round(v * 20) / 20.0; // 0.05 steps
		ItemSizes.set(selected, (float) v);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		boolean used = super.mouseClicked(mx, my, button);
		if (draggingSlider) {   // a press on the track jumps the thumb there
			applySlider(mx);
		}
		return used;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (draggingSlider) {
			applySlider(mx);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		draggingSlider = false;
		return super.mouseReleased(mx, my, button);
	}
}
