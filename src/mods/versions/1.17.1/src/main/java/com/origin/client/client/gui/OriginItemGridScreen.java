package com.origin.client.client.gui;

import com.origin.client.client.theme.OriginTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The searchable, category-tabbed grid of every item -- ONE layout shared by
 * every "pick items" screen (Item Size, Item Counter), so they look and behave
 * the same (Will, 2026-10-12: "use the same layout as item size").
 *
 * The base owns the catalogue, header, search, tabs, the scrolling grid and the
 * hover tooltip. A subclass says what a cell click does, which cells are lit,
 * and draws its own footer bar (the size slider / the picked-items strip) in the
 * space footerSpace() reserves at the bottom. Styled with the shared Origin kit
 * (rounded SDF panels, Inter text) to match the mod menu it opens from.
 */
public abstract class OriginItemGridScreen extends Screen {

	protected enum Cat {
		ALL("All"), COMBAT("Combat"), TOOLS("Tools"), ARMOR("Armor"),
		FOOD("Food"), BLOCKS("Blocks"), MISC("Misc");

		final String label;

		Cat(String l) {
			this.label = l;
		}
	}

	// order = the in-category sort key (tier/slot ranking for gear); regId = raw
	// registry index, used for the "common → rare" order in All/Blocks/Food/Misc.
	// key = what a subclass stores: the registry id for an item, or a pinned
	// entry's own key (e.g. Item Counter's "Arrows" group).
	protected record Entry(Item item, ResourceLocation id, String name, ItemStack stack, Cat cat, int order, int regId,
						   String key) {
	}

	// Built once — every non-air item, pre-categorised with a cached stack + name.
	private static List<Entry> ALL_ITEMS;

	private String search = "";
	private boolean searchFocused = false;
	private Cat cat = Cat.ALL;
	private double scroll = 0, scrollTarget = 0;
	private long lastNanos = 0;

	private final List<Entry> filtered = new ArrayList<>();
	private final String heading, subheading, hoverKey;

	protected OriginItemGridScreen(String heading, String subheading, String hoverKey) {
		super(new TextComponent(heading));
		this.heading = heading;
		this.subheading = subheading;
		this.hoverKey = hoverKey;
	}

	// ---- what a subclass decides ----

	/** A cell was clicked. */
	protected abstract void cellClicked(Entry e);

	/** Lit green (the current pick). */
	protected abstract boolean isSelected(Entry e);

	/** Green outline only (has a saved value, but isn't the current pick). */
	protected boolean isMarked(Entry e) {
		return false;
	}

	/** Bottom space kept clear of the grid for the footer bar (12 = none). */
	protected int footerSpace() {
		return 12;
	}

	/** Draws the footer bar into the space footerSpace() keeps clear. */
	protected void renderFooter(Gfx g, int mx, int my) {
	}

	/** A click outside the header/search/tabs/grid; true if the footer used it. */
	protected boolean footerClicked(double mx, double my) {
		return false;
	}

	/** Entries listed before the items on the All tab (e.g. item groups). */
	protected List<Entry> pinned() {
		return List.of();
	}

	/** Extra tooltip line for a cell, or null. */
	protected String tooltipExtra(Entry e) {
		return null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	protected static List<Entry> allItems() {
		if (ALL_ITEMS == null) {
			List<Entry> out = new ArrayList<>();
			for (Item item : Registry.ITEM) {
				if (item == Items.AIR) {
					continue;
				}
				ItemStack st = new ItemStack(item);
				ResourceLocation id = Registry.ITEM.getKey(item);
				Cat c = categorize(item, st);
				out.add(new Entry(item, id, st.getHoverName().getString(), st, c,
						categoryOrder(c, id.getPath()), Registry.ITEM.getId(item), id.toString()));
			}
			ALL_ITEMS = out;
		}
		return ALL_ITEMS;
	}

	/** A pinned (non-item) entry: drawn with `icon`, stored as `key`. */
	protected static Entry pinnedEntry(String key, String name, Item icon) {
		return new Entry(icon, null, name, new ItemStack(icon), Cat.ALL, 0, -1, key);
	}

	// Material tiers, best → worst, shared by tools/combat/armor rankings.
	private static final String[] TIERS = {"netherite", "diamond", "iron", "stone", "golden", "wooden"};
	private static final String[] ARMOR_TIERS = {"netherite", "diamond", "iron", "chainmail", "golden", "leather"};
	private static final String[] ARMOR_SLOTS = {"helmet", "chestplate", "leggings", "boots"};

	private static int indexOf(String[] arr, String needle) {
		for (int i = 0; i < arr.length; i++) {
			if (needle.equals(arr[i])) {
				return i;
			}
		}
		return -1;
	}

	/** In-category sort key. Lower = earlier. Gear is grouped by type then tier/slot
	 *  (Will's spec); everything else falls back to registry order (regId) via a big
	 *  base so it trails the explicitly-ranked entries. */
	private static int categoryOrder(Cat cat, String path) {
		switch (cat) {
			case COMBAT: {
				// swords by tier, then shield, mace, trident, bow, crossbow.
				if (path.endsWith("_sword")) {
					int t = indexOf(TIERS, path.substring(0, path.length() - 6));
					return t >= 0 ? t : 6;
				}
				int i = indexOf(new String[]{"shield", "mace", "trident", "bow", "crossbow"}, path);
				return i >= 0 ? 10 + i : 100;
			}
			case TOOLS: {
				// pickaxe, axe, shovel, hoe — each best-tier first — then the rest.
				String[] types = {"pickaxe", "axe", "shovel", "hoe"};
				for (int ti = 0; ti < types.length; ti++) {
					if (path.endsWith("_" + types[ti])) {
						int tier = indexOf(TIERS, path.substring(0, path.length() - types[ti].length() - 1));
						return ti * 10 + (tier >= 0 ? tier : 9);
					}
				}
				int i = indexOf(new String[]{"shears", "flint_and_steel", "fishing_rod", "brush", "spyglass"}, path);
				return i >= 0 ? 50 + i : 100;
			}
			case ARMOR: {
				// full sets best→worst, each helmet→chestplate→leggings→boots;
				// horse armour + wolf armour + elytra trail at the very end.
				for (int slot = 0; slot < ARMOR_SLOTS.length; slot++) {
					if (path.endsWith("_" + ARMOR_SLOTS[slot]) || path.equals("turtle_helmet")) {
						String tierName = path.equals("turtle_helmet") ? "turtle"
								: path.substring(0, path.length() - ARMOR_SLOTS[slot].length() - 1);
						int tier = indexOf(ARMOR_TIERS, tierName);
						if (tier < 0) {
							tier = ARMOR_TIERS.length; // turtle/other after leather
						}
						return tier * 4 + slot;
					}
				}
				if (path.endsWith("_horse_armor")) {
					return 200 + indexOf(new String[]{"leather", "iron", "golden", "diamond"}, path.replace("_horse_armor", ""));
				}
				if (path.equals("wolf_armor")) {
					return 250;
				}
				if (path.equals("elytra")) {
					return 300;
				}
				return 400;
			}
			default:
				return 0; // Blocks / Food / Misc / All → sorted by regId instead
		}
	}

	private static Cat categorize(Item item, ItemStack st) {
		if (item instanceof ArmorItem) {
			return Cat.ARMOR;
		}
		if (item instanceof SwordItem || item instanceof BowItem || item instanceof CrossbowItem
				|| item instanceof TridentItem || item instanceof ShieldItem) {
			return Cat.COMBAT;
		}
		if (item instanceof net.minecraft.world.item.DiggerItem) {
			return Cat.TOOLS;
		}
		if (item.isEdible()) {
			return Cat.FOOD;
		}
		if (item instanceof BlockItem) {
			return Cat.BLOCKS;
		}
		return Cat.MISC;
	}

	// ---- geometry ----
	protected int px() {
		return (width - pw()) / 2;
	}

	protected int py() {
		return (height - ph()) / 2;
	}

	protected int pw() {
		return (int) (width * 0.78);
	}

	protected int ph() {
		return (int) (height * 0.80);
	}

	protected int gridLeft() {
		return px() + 14;
	}

	protected int gridRight() {
		return px() + pw() - 14;
	}

	private int gridTop() {
		// Below the category tab row (tabs sit at py+62, 16 tall → bottom py+78);
		// a clear gap so the first row of items never clips into the tabs.
		return py() + 86;
	}

	private int gridBottom() {
		return py() + ph() - footerSpace();
	}

	private static final int CELL = 26;

	private int cols() {
		return Math.max(1, (gridRight() - gridLeft()) / CELL);
	}

	// What `filtered` was last built for. Rebuilding is O(all items) plus a sort,
	// and render() calls filter() unconditionally on its first line — so without
	// this the screen re-filtered and re-SORTED the entire ~1300-item registry
	// every frame, allocating two lowercase Strings per item as it went. Found on
	// 1.21.11 (Will reported heavy lag in this screen) and fixed here at the same
	// time: the code is identical, so the bug was identical.
	private String lastQuery = null;
	private Cat lastCat = null;

	private void filter() {
		if (search.equals(lastQuery) && cat == lastCat) {
			return;
		}
		lastQuery = search;
		lastCat = cat;

		filtered.clear();
		String q = search.toLowerCase();
		for (Entry e : allItems()) {
			if (cat != Cat.ALL && e.cat != cat) {
				continue;
			}
			if (!q.isEmpty() && !e.name.toLowerCase().contains(q) && !e.id.toString().contains(q)) {
				continue;
			}
			filtered.add(e);
		}
		// Gear categories use the tier/slot ranking; everything else (incl. All,
		// Blocks, Food, Misc) uses registry order = roughly common → rare.
		boolean ranked = cat == Cat.COMBAT || cat == Cat.TOOLS || cat == Cat.ARMOR;
		filtered.sort((a, b) -> ranked
				? (a.order != b.order ? Integer.compare(a.order, b.order) : Integer.compare(a.regId, b.regId))
				: Integer.compare(a.regId, b.regId));
		if (cat == Cat.ALL) {
			int at = 0;
			for (Entry e : pinned()) {
				if (q.isEmpty() || e.name.toLowerCase().contains(q)) {
					filtered.add(at++, e);
				}
			}
		}
	}

	private double maxScroll() {
		int rows = (filtered.size() + cols() - 1) / cols();
		return Math.max(0, rows * CELL - (gridBottom() - gridTop()));
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		Gfx g = new Gfx(poseStack);
		filter();
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 16.7 : Math.min(50.0, (now - lastNanos) / 1_000_000.0);
		lastNanos = now;
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget));
		scroll += (scrollTarget - scroll) * Math.min(1.0, dt / 60.0);

		// backdrop + panel
		g.fill(0, 0, width, height, 0x88000000);
		OriginUi.panel(g, px(), py(), pw(), ph(), 12, 0xF00E0E0E, OriginTheme.STROKE);

		// header: back + title
		int hy = py() + 16;
		boolean backHover = in(mouseX, mouseY, px() + 14, hy, px() + 38, hy + 20);
		float backHp = OriginUi.hover(hoverKey + ":back", backHover);
		OriginUi.panel(g, px() + 14, hy, 24, 20, 6,
				OriginTheme.lerpColor(0x16FFFFFF, 0x2EFFFFFF, backHp),
				OriginTheme.lerpColor(OriginTheme.STROKE, OriginTheme.STROKE_HOVER, backHp));
		OriginUi.iconChevron(g, px() + 21, hy + 5, 10, OriginTheme.TEXT, true);
		OriginText.drawBold(g, font, heading, px() + 46, hy + 2, OriginTheme.TEXT, false);
		OriginText.draw(g, font, subheading, px() + 46, hy + 13, OriginTheme.MUTED, false);

		// search bar
		int sy = py() + 40;
		int sx = gridLeft();
		int sw = gridRight() - gridLeft();
		OriginUi.panel(g, sx, sy, sw, 20, 8, searchFocused ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
				searchFocused ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER);
		OriginUi.icon(g, "@search", sx + 5, sy + 3, 14, OriginTheme.MUTED);
		if (search.isEmpty() && !searchFocused) {
			OriginText.draw(g, font, "Search items", sx + 24, sy + 6, OriginTheme.MUTED, false);
		} else {
			OriginText.draw(g, font, search, sx + 24, sy + 6, OriginTheme.TEXT, false);
		}

		// category tabs
		int tx = gridLeft();
		int ty = py() + 62;
		for (Cat c : Cat.values()) {
			int tw = OriginText.widthBold(font, c.label) + 16;
			boolean active = cat == c;
			boolean hover = in(mouseX, mouseY, tx, ty, tx + tw, ty + 16);
			float hp = OriginUi.hover(hoverKey + ":tab:" + c.name(), hover);
			OriginUi.panel(g, tx, ty, tw, 16, 6,
					active ? OriginTheme.BOX_FILL_HOVER
							: OriginTheme.lerpColor(0x00000000, OriginTheme.BOX_FILL, hp),
					active ? OriginTheme.STROKE_STRONG : 0);
			OriginText.drawBold(g, font, c.label, tx + 8, ty + 4,
					active ? OriginTheme.TEXT : OriginTheme.lerpColor(OriginTheme.MUTED, OriginTheme.TEXT_DIM, hp), false);
			tx += tw + 4;
		}

		// grid
		int top = gridTop(), bottom = gridBottom();
		g.enableScissor(px(), top, px() + pw(), bottom);
		int cols = cols();
		int gx0 = gridLeft();
		// Only walk the rows that are actually on screen. This used to iterate the
		// whole filtered list and `continue` past everything off-screen, so the
		// per-frame cost scaled with the LIST, not the view.
		int firstRow = Math.max(0, (int) scroll / CELL);
		int lastRow = (int) (scroll + (bottom - top)) / CELL + 1;
		int first = firstRow * cols;
		int last = Math.min(filtered.size(), (lastRow + 1) * cols);
		for (int i = first; i < last; i++) {
			int col = i % cols, row = i / cols;
			int x = gx0 + col * CELL;
			int y = top + row * CELL - (int) scroll;
			if (y + CELL < top || y > bottom) {
				continue;
			}
			Entry e = filtered.get(i);
			boolean cellHover = in(mouseX, mouseY, x, y, x + CELL - 2, y + CELL - 2) && mouseY >= top && mouseY < bottom;
			boolean isSel = isSelected(e);
			boolean custom = isMarked(e);
			float cellHp = OriginUi.hover(hoverKey + ":cell:" + e.key, cellHover);
			OriginUi.panel(g, x, y, CELL - 2, CELL - 2, 6,
					isSel ? OriginTheme.withAlpha(OriginTheme.SUCCESS, 0x55)
							: OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, cellHp),
					isSel ? 0xB37ACF9E : (custom ? 0x807ACF9E : OriginTheme.BOX_BORDER));
			drawItemIcon(g, e.stack, x + 4, y + 4, 16);
		}
		g.disableScissor();

		renderFooter(g, mouseX, mouseY);

		// hover tooltip (item name)
		if (mouseY >= top && mouseY < bottom) {
			Entry h = cellAt(mouseX, mouseY);
			if (h != null) {
				String extra = tooltipExtra(h);
				if (extra == null) {
					g.renderTooltip(font, new TextComponent(h.name), mouseX, mouseY);
				} else {
					g.renderComponentTooltip(font, List.of(new TextComponent(h.name),
							new TextComponent(extra).withStyle(net.minecraft.ChatFormatting.GRAY)), mouseX, mouseY);
				}
			}
		}
	}

	protected void drawItemIcon(Gfx g, ItemStack stack, int x, int y, int size) {
		var pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		float s = size / 16f;
		pose.scale(s, s, 1f);
		g.renderFakeItem(stack, 0, 0);
		pose.popPose();
	}

	/** The cell under the cursor, found by arithmetic rather than by scanning the
	 *  whole list — this runs every frame (hover highlight + tooltip). */
	private Entry cellAt(double mx, double my) {
		int top = gridTop(), bottom = gridBottom();
		if (my < top || my >= bottom) {
			return null;
		}
		int gx0 = gridLeft();
		int cols = cols();
		int col = (int) ((mx - gx0) / CELL);
		int row = (int) ((my - top + scroll) / CELL);
		if (col < 0 || col >= cols || row < 0 || mx < gx0) {
			return null;
		}
		int i = row * cols + col;
		if (i < 0 || i >= filtered.size()) {
			return null;
		}
		// Reject the 2px gutter between cells so hover matches what was drawn.
		int x = gx0 + col * CELL;
		int y = top + row * CELL - (int) scroll;
		return in(mx, my, x, y, x + CELL - 2, y + CELL - 2) ? filtered.get(i) : null;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != 0) {
			return super.mouseClicked(mx, my, button);
		}
		int hy = py() + 16;
		if (in(mx, my, px() + 14, hy, px() + 38, hy + 20)) {
			onClose();
			return true;
		}
		// search
		int sy = py() + 40;
		searchFocused = in(mx, my, gridLeft(), sy, gridRight(), sy + 20);
		if (searchFocused) {
			return true;
		}
		// category tabs
		int tx = gridLeft(), ty = py() + 62;
		for (Cat c : Cat.values()) {
			int tw = OriginText.widthBold(font, c.label) + 16;
			if (in(mx, my, tx, ty, tx + tw, ty + 16)) {
				cat = c;
				scrollTarget = scroll = 0;
				return true;
			}
			tx += tw + 4;
		}
		if (footerClicked(mx, my)) {
			return true;
		}
		Entry e = cellAt(mx, my);
		if (e != null) {
			cellClicked(e);
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - amount * CELL));
		return true;
	}

	@Override
	public boolean charTyped(char chr, int mods) {
		if (searchFocused && chr >= 32 && search.length() < 30) {
			search += chr;
			scrollTarget = scroll = 0;
			return true;
		}
		return super.charTyped(chr, mods);
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		if (key == GLFW.GLFW_KEY_BACKSPACE && searchFocused && !search.isEmpty()) {
			search = search.substring(0, search.length() - 1);
			scrollTarget = scroll = 0;
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}

	/** Back arrow / Esc: where this screen returns to. */
	@Override
	public abstract void onClose();

	protected static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}
}
