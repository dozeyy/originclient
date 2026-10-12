package com.origin.client.client.gui;

import com.origin.client.client.mods.ItemCounter;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Item Counter's "Items" picker -- the shared item grid (same layout as Item
 * Size). Click any item to count it, click again to stop; the groups pinned at
 * the top of the All tab count several items at once (every arrow, every food).
 * The footer strip shows what's counted, in HUD order; click an icon there to
 * remove it. Saved straight to the mod's "items" option (see ItemCounter for
 * the stored tokens), so the HUD updates live.
 */
public class ItemCounterPickerScreen extends OriginItemGridScreen {
	private static final String MOD = "itemcounter", KEY = "items";
	private static final int CHIP = 20;

	private static List<Entry> PINNED;

	public ItemCounterPickerScreen() {
		super("Item Counter", "Pick the items to count. Click again to remove.", "item-counter");
	}

	// isSelected runs for every visible cell every frame: re-parse only on change.
	private String pickedCsv = null;
	private java.util.Set<String> picked = java.util.Set.of();

	private static List<String> picks() {
		return ItemCounter.tokens(Mods.mode(MOD, KEY));
	}

	private static void save(List<String> tokens) {
		Mods.set(MOD, KEY, String.join(",", tokens));
	}

	@Override
	protected List<Entry> pinned() {
		if (PINNED == null) {
			List<Entry> out = new ArrayList<>();
			for (ItemCounter.Entry g : ItemCounter.GROUPS) {
				out.add(pinnedEntry(g.key(), g.label(), g.icon()));
			}
			PINNED = out;
		}
		return PINNED;
	}

	@Override
	protected String tooltipExtra(Entry e) {
		return e.id() == null ? ItemCounter.GROUP_HINTS.get(e.key()) : null;
	}

	@Override
	protected void cellClicked(Entry e) {
		List<String> p = picks();
		if (!p.remove(e.key())) {
			p.add(e.key());
		}
		save(p);
	}

	@Override
	protected boolean isSelected(Entry e) {
		String csv = Mods.mode(MOD, KEY);
		if (!csv.equals(pickedCsv)) {
			picked = new java.util.HashSet<>(ItemCounter.tokens(csv));
			pickedCsv = csv;
		}
		return picked.contains(e.key());
	}

	@Override
	protected int footerSpace() {
		return 44;
	}

	// ---- footer: the counted items, in HUD order ----

	private int barY() {
		return py() + ph() - 38;
	}

	private int clearX() {
		return gridRight() - 8 - 46;
	}

	private int chipX0() {
		return gridLeft() + 70;
	}

	/** How many chips fit between the label and the Clear button. */
	private int chipRoom() {
		return Math.max(0, (clearX() - 8 - chipX0()) / CHIP);
	}

	@Override
	protected void renderFooter(Gfx g, int mx, int my) {
		int by = barY();
		int bx0 = gridLeft(), bx1 = gridRight();
		OriginUi.panel(g, bx0, by, bx1 - bx0, 30, 8, OriginTheme.BOX_FILL, OriginTheme.BOX_BORDER);
		OriginText.drawBold(g, font, "Counting", bx0 + 10, by + 11, OriginTheme.TEXT, false);

		List<String> p = picks();
		if (p.isEmpty()) {
			OriginText.draw(g, font, "Nothing yet -- click items above.", chipX0(), by + 11, OriginTheme.MUTED, false);
		}
		int room = chipRoom();
		String hoverName = null;
		for (int i = 0; i < p.size() && i < room; i++) {
			ItemCounter.Entry e = ItemCounter.resolve(p.get(i));
			if (e == null) {
				continue;
			}
			int x = chipX0() + i * CHIP;
			boolean hover = in(mx, my, x, by + 5, x + CHIP - 2, by + 25);
			float hp = OriginUi.hover("item-counter:chip:" + e.key(), hover);
			OriginUi.panel(g, x, by + 5, CHIP - 2, 20, 6,
					OriginTheme.lerpColor(OriginTheme.withAlpha(OriginTheme.SUCCESS, 0x40), 0x40FF5555, hp), 0);
			drawItemIcon(g, new ItemStack(e.icon()), x + 1, by + 7, 16);
			if (hover) {
				hoverName = e.label();
			}
		}
		if (p.size() > room && room > 0) {
			OriginText.draw(g, font, "+" + (p.size() - room), chipX0() + room * CHIP, by + 11, OriginTheme.MUTED, false);
		}

		int rX = clearX(), rW = 46;
		boolean rHover = in(mx, my, rX, by + 6, rX + rW, by + 24);
		float hp = OriginUi.hover("item-counter:clear", rHover);
		OriginUi.panel(g, rX, by + 6, rW, 18, 6,
				OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, hp),
				OriginTheme.lerpColor(OriginTheme.BOX_BORDER, OriginTheme.STROKE_HOVER, hp));
		OriginText.draw(g, font, "Clear", rX + (rW - OriginText.width(font, "Clear")) / 2, by + 11, OriginTheme.TEXT_DIM, false);

		if (hoverName != null) {
			g.renderComponentTooltip(font, List.of(new TextComponent(hoverName),
					new TextComponent("Click to remove").withStyle(net.minecraft.ChatFormatting.GRAY)), mx, my);
		}
	}

	@Override
	protected boolean footerClicked(double mx, double my) {
		int by = barY();
		if (in(mx, my, clearX(), by + 6, clearX() + 46, by + 24)) {
			save(new ArrayList<>());
			return true;
		}
		List<String> p = picks();
		for (int i = 0; i < p.size() && i < chipRoom(); i++) {
			int x = chipX0() + i * CHIP;
			if (in(mx, my, x, by + 5, x + CHIP - 2, by + 25)) {
				p.remove(i);
				save(p);
				return true;
			}
		}
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(new OriginModMenuScreen(null, MOD));
	}
}
