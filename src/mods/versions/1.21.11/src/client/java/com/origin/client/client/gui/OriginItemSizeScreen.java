package com.origin.client.client.gui;

import com.origin.client.client.mods.ItemSizes;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.equipment.Equippable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Item Size Customizer grid — set a custom dropped-item render size per item.
 *
 * A searchable, category-tabbed grid of every item; click one to select it, then
 * drag the size slider (0.25x–3.0x) at the bottom. Sizes persist immediately via
 * {@link ItemSizes} and apply live to dropped items in-world (ItemEntityScaleMixin).
 * Styled with the shared Origin kit (rounded SDF panels, Inter text) to match the
 * mod menu it opens from.
 *
 * PER-VERSION DELTA (1.21.11):
 * - ResourceLocation was renamed net.minecraft.resources.Identifier (the Entry
 *   record, the `selected` field, BuiltInRegistries.ITEM.getKey).
 * - Input callbacks take event objects: mouseClicked(MouseButtonEvent, boolean),
 *   mouseDragged(MouseButtonEvent, double, double), mouseReleased(MouseButtonEvent),
 *   keyPressed(KeyEvent), charTyped(CharacterEvent). The x/y/button/key values are
 *   unpacked off the event at the top of each method so every body below is the
 *   1.21.1 logic unchanged. mouseScrolled is the one holdout still taking raw
 *   doubles.
 * - GuiGraphics.pose() is a Matrix3x2fStack: pushMatrix()/popMatrix() and 2-arg
 *   translate/scale (no z), used by drawItemIcon.
 * - GuiGraphics.renderTooltip(Font, Component, int, int) is gone; this era defers
 *   tooltips through setTooltipForNextFrame(Font, Component, int, int).
 * - SwordItem and ArmorItem no longer exist (1.21.2 folded swords into plain Items
 *   with tool/weapon components; 1.21.5 folded armour into the `equippable`
 *   component). categorize() now takes the registry id and uses the "_sword" path
 *   suffix + the equippable component instead — see isSword/isArmorPiece for why.
 *   Every other item class it tests (Bow/Crossbow/Trident/Mace/Shield/BlockItem)
 *   still exists and is still what those items are built from.
 */
public class OriginItemSizeScreen extends Screen {

	private enum Cat {
		ALL("All"), COMBAT("Combat"), TOOLS("Tools"), ARMOR("Armor"),
		FOOD("Food"), BLOCKS("Blocks"), MISC("Misc");

		final String label;

		Cat(String l) {
			this.label = l;
		}
	}

	// order = the in-category sort key (tier/slot ranking for gear); regId = raw
	// registry index, used for the "common → rare" order in All/Blocks/Food/Misc.
	private record Entry(Item item, Identifier id, String name, ItemStack stack, Cat cat, int order, int regId) {
	}

	// Built once — every non-air item, pre-categorised with a cached stack + name.
	private static List<Entry> ALL_ITEMS;

	private String search = "";
	private boolean searchFocused = false;
	private Cat cat = Cat.ALL;
	private double scroll = 0, scrollTarget = 0;
	private long lastNanos = 0;
	private Identifier selected = null;
	private boolean draggingSlider = false;

	private final List<Entry> filtered = new ArrayList<>();

	public OriginItemSizeScreen() {
		super(Component.literal("Item Size"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static List<Entry> allItems() {
		if (ALL_ITEMS == null) {
			List<Entry> out = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item == Items.AIR) {
					continue;
				}
				ItemStack st = new ItemStack(item);
				Identifier id = BuiltInRegistries.ITEM.getKey(item);
				Cat c = categorize(item, st, id);
				out.add(new Entry(item, id, st.getHoverName().getString(), st, c,
						categoryOrder(c, id.getPath()), BuiltInRegistries.ITEM.getId(item)));
			}
			ALL_ITEMS = out;
		}
		return ALL_ITEMS;
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

	private static Cat categorize(Item item, ItemStack st, Identifier id) {
		if (isArmorPiece(item, st)) {
			return Cat.ARMOR;
		}
		if (isSword(id) || item instanceof BowItem || item instanceof CrossbowItem
				|| item instanceof TridentItem || item instanceof MaceItem || item instanceof ShieldItem) {
			return Cat.COMBAT;
		}
		if (st.has(DataComponents.TOOL)) {
			return Cat.TOOLS;
		}
		if (st.has(DataComponents.FOOD)) {
			return Cat.FOOD;
		}
		if (item instanceof BlockItem) {
			return Cat.BLOCKS;
		}
		return Cat.MISC;
	}

	/** 1.21.11 has no SwordItem — swords are plain Items that carry the tool and
	 *  weapon data components, and axes/pickaxes/shovels/hoes carry both of those
	 *  too, so no component tells a sword apart. The registry path does, and
	 *  categoryOrder() above already ranks swords by exactly this "&lt;tier&gt;_sword"
	 *  suffix, so the whole file agrees on one definition. The alternative,
	 *  ItemTags.SWORDS, is not usable here: item tags only populate once a world
	 *  loads, and this grid is reachable straight from the title screen (where the
	 *  ALL_ITEMS cache is built once and kept). */
	private static boolean isSword(Identifier id) {
		return id.getPath().endsWith("_sword");
	}

	/** 1.21.11 has no ArmorItem — wearability is the `equippable` data component.
	 *  Armour = equippable into an armour slot: HEAD/CHEST/LEGS/FEET (the humanoid
	 *  set) or BODY (horse/wolf armour, which were AnimalArmorItem — an ArmorItem —
	 *  on 1.21.1). EquipmentSlot.isArmor() is exactly that test, so OFFHAND
	 *  (shields) and SADDLE fall straight through to the checks below, same as they
	 *  did on 1.21.1. BlockItems are excluded so wearable blocks (mob heads, carved
	 *  pumpkin) stay in Blocks, which is where 1.21.1 put them. */
	private static boolean isArmorPiece(Item item, ItemStack st) {
		if (item instanceof BlockItem) {
			return false;
		}
		Equippable eq = st.get(DataComponents.EQUIPPABLE);
		return eq != null && eq.slot().isArmor();
	}

	// ---- geometry ----
	private int px() {
		return (width - pw()) / 2;
	}

	private int py() {
		return (height - ph()) / 2;
	}

	private int pw() {
		return (int) (width * 0.78);
	}

	private int ph() {
		return (int) (height * 0.80);
	}

	private int gridLeft() {
		return px() + 14;
	}

	private int gridRight() {
		return px() + pw() - 14;
	}

	private int gridTop() {
		// Below the category tab row (tabs sit at py+62, 16 tall → bottom py+78);
		// a clear gap so the first row of items never clips into the tabs.
		return py() + 86;
	}

	private int gridBottom() {
		return py() + ph() - (selected != null ? 44 : 12);
	}

	private static final int CELL = 26;

	private int cols() {
		return Math.max(1, (gridRight() - gridLeft()) / CELL);
	}

	// What `filtered` was last built for. Rebuilding is O(all items) plus a sort,
	// and render() calls filter() unconditionally on its first line — so without
	// this the screen re-filtered and re-SORTED the entire ~1300-item registry
	// every frame, allocating two lowercase Strings per item as it went. That,
	// not the drawing, was the Item Size lag Will reported: the work is identical
	// whether or not anything changed, and it changes only when the player types
	// or switches category. (Same bug in 1.21.1's copy — fix it there too.)
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
	}

	private double maxScroll() {
		int rows = (filtered.size() + cols() - 1) / cols();
		return Math.max(0, rows * CELL - (gridBottom() - gridTop()));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
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
		OriginUi.panel(g, px() + 14, hy, 24, 20, 6, backHover ? 0x2EFFFFFF : 0x16FFFFFF,
				backHover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
		OriginUi.iconChevron(g, px() + 21, hy + 5, 10, OriginTheme.TEXT, true);
		OriginText.drawBold(g, font, "Item Size", px() + 46, hy + 2, OriginTheme.TEXT, false);
		OriginText.draw(g, font, "Set dropped-item render sizes per item.", px() + 46, hy + 13, OriginTheme.MUTED, false);

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
			OriginUi.panel(g, tx, ty, tw, 16, 6,
					active ? OriginTheme.BOX_FILL_HOVER : (hover ? OriginTheme.BOX_FILL : 0x00000000),
					active ? OriginTheme.STROKE_STRONG : 0);
			OriginText.drawBold(g, font, c.label, tx + 8, ty + 4,
					active ? OriginTheme.TEXT : (hover ? OriginTheme.TEXT_DIM : OriginTheme.MUTED), false);
			tx += tw + 4;
		}

		// grid
		int top = gridTop(), bottom = gridBottom();
		g.enableScissor(px(), top, px() + pw(), bottom);
		int cols = cols();
		int gx0 = gridLeft();
		// Only walk the rows that are actually on screen. This used to iterate the
		// whole filtered list and `continue` past everything off-screen, so the
		// per-frame cost scaled with the LIST, not the view — which is exactly what
		// Will saw: Combat (a dozen items) smooth, All (~1300) crawling.
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
			boolean isSel = e.id.equals(selected);
			boolean custom = ItemSizes.isCustom(e.id);
			OriginUi.panel(g, x, y, CELL - 2, CELL - 2, 6,
					isSel ? 0x552F7D53 : (cellHover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL),
					isSel ? 0xB37FA98F : (custom ? 0x807FA98F : OriginTheme.BOX_BORDER));
			drawItemIcon(g, e.stack, x + 4, y + 4, 16);
		}
		g.disableScissor();

		// selected item → size slider bar
		if (selected != null) {
			renderSizeBar(g, mouseX, mouseY);
		}

		// hover tooltip (item name)
		if (mouseY >= top && mouseY < bottom) {
			Entry h = cellAt(mouseX, mouseY);
			if (h != null) {
				// 1.21.11: renderTooltip(Font, Component, x, y) is gone — tooltips are
				// queued and drawn at the end of the frame instead.
				g.setTooltipForNextFrame(font, Component.literal(h.name), mouseX, mouseY);
			}
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
		drawItemIcon(g, e.stack, bx0 + 6, by + 7, 16);
		String name = OriginText.ellipsize(font, e.name, 130);
		OriginText.draw(g, font, name, bx0 + 28, by + 11, OriginTheme.TEXT, false);

		float size = ItemSizes.get(e.id);
		// Reset button (right)
		int rW = 46, rX = bx1 - 8 - rW;
		boolean rHover = in(mx, my, rX, by + 6, rX + rW, by + 24);
		OriginUi.panel(g, rX, by + 6, rW, 18, 6, rHover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
				rHover ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER);
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

	private void drawItemIcon(GuiGraphics g, ItemStack stack, int x, int y, int size) {
		// 1.21.11: pose() is a Matrix3x2fStack — pushMatrix/popMatrix and 2-arg
		// translate/scale (there is no z on a 2D matrix stack).
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		float s = size / 16f;
		pose.scale(s, s);
		g.renderFakeItem(stack, 0, 0);
		pose.popMatrix();
	}

	/** Id -> entry, built once alongside ALL_ITEMS. entryById is called every
	 *  frame while an item is selected (the size bar draws its icon and name), and
	 *  used to be a linear scan of all ~1300 entries. */
	private static Map<Identifier, Entry> BY_ID;

	private Entry entryById(Identifier id) {
		if (id == null) {
			return null;
		}
		if (BY_ID == null) {
			Map<Identifier, Entry> m = new HashMap<>();
			for (Entry e : allItems()) {
				m.put(e.id, e);
			}
			BY_ID = m;
		}
		return BY_ID.get(id);
	}

	/** The cell under the cursor, found by arithmetic rather than by scanning the
	 *  whole list — this runs every frame (hover highlight + tooltip) and used to
	 *  be a linear search over all ~1300 filtered entries. */
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
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x(), my = event.y();
		int button = event.button();
		if (button != 0) {
			return super.mouseClicked(event, doubled);
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
		// grid cell → select
		Entry e = cellAt(mx, my);
		if (e != null) {
			selected = e.id.equals(selected) ? null : e.id;
			return true;
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		double mx = event.x();
		if (draggingSlider) {
			applySlider(mx);
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
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - sy * CELL));
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		char chr = (char) event.codepoint();
		if (searchFocused && chr >= 32 && search.length() < 30) {
			search += chr;
			scrollTarget = scroll = 0;
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		if (key == GLFW.GLFW_KEY_BACKSPACE && searchFocused && !search.isEmpty()) {
			search = search.substring(0, search.length() - 1);
			scrollTarget = scroll = 0;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(new OriginModMenuScreen());
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}
}
