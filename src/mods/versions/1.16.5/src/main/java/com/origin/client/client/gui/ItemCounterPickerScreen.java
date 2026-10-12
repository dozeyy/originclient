package com.origin.client.client.gui;

import com.origin.client.client.mods.ItemCounter;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * Item Counter's "Items" picker -- the shared item grid (same layout as Item
 * Size). Click any item to count it, click again to stop; the groups pinned at
 * the top of the All tab count several items at once (every arrow, every food).
 * The footer strip shows what's counted, in HUD order; click an icon there to
 * remove it. Saved straight to the mod's "items" option (see ItemCounter for
 * the stored tokens), so the HUD updates live. Back / Esc / close return to
 * Item Counter's page in the mod menu.
 */
public class ItemCounterPickerScreen extends OriginItemGridScreen {
	private static final String MOD = "itemcounter", KEY = "items";
	private static final float CHIP = 44, CHIP_GAP = 6;

	private static List<Entry> PINNED;

	public ItemCounterPickerScreen() {
		this(new OriginModMenuScreen(null, MOD));
	}

	public ItemCounterPickerScreen(Screen returnTo) {
		super("Item Counter", "Pick the items to count. Click again to remove.", "item-counter", "itemcounter",
				returnTo);
	}

	// isSelected runs for every visible cell every frame: re-parse only on change.
	private String pickedCsv = null;
	private java.util.Set<String> picked = java.util.Collections.emptySet();

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
	protected float footerH() {
		return 56;
	}

	// ---- footer: COUNTING | the counted items, in HUD order | Clear ----

	@Override
	protected void drawFooter(OriginDeck d, float x, float y, float w, float h) {
		d.rect(x, y, w, h, 8, CARD, LINE);
		label(d, "Counting", x + 16, y + h / 2 + capH(d));

		List<String> p = picks();
		float cw = buttonW(d, "Clear", "@trash");
		float clearX = x + w - 12 - cw;
		button(d, "Clear", "@trash", clearX, y + 12, cw, 32, Style.SECONDARY, !p.isEmpty(),
				() -> save(new ArrayList<>()));

		float chipX0 = x + 132;
		if (p.isEmpty()) {
			d.textMid("Nothing yet. Click items above.", chipX0, y + h / 2, 13, false, 0, MUTE);
			return;
		}
		// how many chips fit before the Clear button (leaving room for "+N")
		int room = Math.max(0, (int) ((clearX - 16 - 36 - chipX0 + CHIP_GAP) / (CHIP + CHIP_GAP)));
		for (int i = 0; i < p.size() && i < room; i++) {
			ItemCounter.Entry e = ItemCounter.resolve(p.get(i));
			if (e == null) {
				continue;
			}
			float cx = chipX0 + i * (CHIP + CHIP_GAP), cy = y + (h - CHIP) / 2;
			boolean hov = d.over(cx, cy, CHIP, CHIP);
			float hp = OriginUi.hover("item-counter:chip:" + e.key(), hov);
			d.rect(cx, cy, CHIP, CHIP, 6, lerp(ACC_SOFT, RED_SOFT, hp), lerp(mul(ACC, 0.45f), mul(RED, 0.6f), hp));
			drawItem(d, new ItemStack(e.icon()), cx, cy, CHIP, 32);
			final int idx = i;
			d.hit(cx, cy, CHIP, CHIP, () -> {
				List<String> cur = picks();
				if (idx < cur.size()) {
					cur.remove(idx);
					save(cur);
				}
			});
			if (hov) {
				tooltip(e.label(), "Click to remove");
			}
		}
		if (p.size() > room && room > 0) {
			d.textMid("+" + (p.size() - room), chipX0 + room * (CHIP + CHIP_GAP), y + h / 2, 13, true, 0, MUTE);
		}
	}

	/** Half the cap height of the 11px caps label, to cap-centre it like textMid. */
	private static float capH(OriginDeck d) {
		return d.capH(11, true) / 2f;
	}
}
