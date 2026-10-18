package com.origin.client.client.gui;

import com.origin.client.client.mods.Mods;
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
import java.util.List;
import java.util.Locale;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * The searchable, category-tabbed grid of every item -- ONE layout shared by
 * every "pick items" screen (Item Size, Item Counter), so they look and behave
 * the same (Will, 2026-10-12: "use the same layout as item size").
 *
 * Built on the shared Origin window frame (OriginWindow: the redesigned mod
 * menu's panel, header with back + close, design-px OriginDeck drawing). The
 * base owns the catalogue, the toolbar (category chips + search), the scrolling
 * grid and the hover tooltip. A subclass says what a cell click does, which
 * cells are lit, and draws its own footer bar (the size slider / the picked-items
 * strip) in the footerH() design px it reserves at the bottom of the body.
 *
 * Items are drawn at whole multiples of 16 physical px (crisp texels at every
 * window size) and in front of the deck's z, so anything drawn over them (the
 * tooltip, the bottom fade) is lifted to {@link #Z_OVER}.
 *
 * PER-VERSION DELTA (1.21.11):
 * - Identifier names items (the Entry record, BuiltInRegistries.ITEM.getKey).
 * - Input callbacks take event objects: mouseClicked(MouseButtonEvent, boolean),
 *   keyPressed(KeyEvent), charTyped(CharacterEvent); subclasses get the same
 *   events in mouseDragged/mouseReleased. mouseScrolled still takes raw doubles.
 * - GuiGraphics.pose() is a 2D Matrix3x2fStack (pushMatrix / 2-arg translate +
 *   scale) with no z: the deferred GUI layers by submission order instead (a
 *   box drawn after an item that overlaps it lands in a higher layer), so
 *   inFront() just runs its draws after the items -- the same result as
 *   1.21.1's Z_OVER lift.
 * - SwordItem and ArmorItem no longer exist (1.21.2 folded swords into plain Items
 *   with tool/weapon components; 1.21.5 folded armour into the `equippable`
 *   component). categorize() takes the registry id and uses the "_sword" path
 *   suffix + the equippable component instead -- see isSword/isArmorPiece.
 */
public abstract class OriginItemGridScreen extends OriginWindow {

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
	protected record Entry(Item item, Identifier id, String name, ItemStack stack, Cat cat, int order, int regId,
						   String key) {
	}

	// Built once — every non-air item, pre-categorised with a cached stack + name.
	private static List<Entry> ALL_ITEMS;

	// ---- layout (design px; 24px grid) ----
	protected static final float TOOL_Y = BODY_Y;            // chips + search row, 32 tall
	protected static final float GRID_Y = BODY_Y + 32 + 16;  // first row of cells
	private static final int COLS = 14;
	private static final float GAP = 8;
	private static final float CELL = (BODY_W - (COLS - 1) * GAP) / COLS;
	private static final float ITEM = 36;                    // item icon, before snapping to 16px multiples
	/** Pose z for deck draws that must sit over item icons (items render at z ~150+). */
	protected static final float Z_OVER = 1000;

	private String search = "";
	private boolean searchFocused = false;
	private Cat cat = Cat.ALL;

	private final List<Entry> filtered = new ArrayList<>();
	private final String heading, subheading, hoverKey, icon;

	// this frame's tooltip (title + optional grey line), drawn last
	private String tipTitle, tipExtra;

	protected OriginItemGridScreen(String heading, String subheading, String hoverKey, String icon, Screen returnTo) {
		super(Component.literal(heading), returnTo);
		this.heading = heading;
		this.subheading = subheading;
		this.hoverKey = hoverKey;
		this.icon = icon;
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

	/** Design px kept clear of the grid at the bottom of the body for the footer bar (0 = none). */
	protected float footerH() {
		return 0;
	}

	/** Draws the footer bar into the box footerH() keeps clear (design px). */
	protected void drawFooter(OriginDeck d, float x, float y, float w, float h) {
	}

	/** Entries listed before the items on the All tab (e.g. item groups). */
	protected List<Entry> pinned() {
		return List.of();
	}

	/** Extra tooltip line for a cell, or null. */
	protected String tooltipExtra(Entry e) {
		return null;
	}

	protected static List<Entry> allItems() {
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
						categoryOrder(c, id.getPath()), BuiltInRegistries.ITEM.getId(item), id.toString()));
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

	/** 1.21.11 has no SwordItem -- swords are plain Items that carry the tool and
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

	/** 1.21.11 has no ArmorItem -- wearability is the `equippable` data component.
	 *  Armour = equippable into an armour slot: HEAD/CHEST/LEGS/FEET (the humanoid
	 *  set) or BODY (horse/wolf armour, which were AnimalArmorItem -- an ArmorItem --
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

	// ---- filtering ----

	// What `filtered` was last built for. Rebuilding is O(all items) plus a sort,
	// and drawBody() calls filter() on its first line every frame — so without
	// this the screen re-filtered and re-SORTED the entire ~1300-item registry
	// every frame, allocating two lowercase Strings per item as it went. Found on
	// 1.21.11 (Will reported heavy lag in this screen).
	private String lastQuery = null;
	private Cat lastCat = null;

	private void filter() {
		if (search.equals(lastQuery) && cat == lastCat) {
			return;
		}
		lastQuery = search;
		lastCat = cat;

		filtered.clear();
		String q = search.toLowerCase(Locale.ROOT);
		for (Entry e : allItems()) {
			if (cat != Cat.ALL && e.cat != cat) {
				continue;
			}
			if (!q.isEmpty() && !e.name.toLowerCase(Locale.ROOT).contains(q) && !e.id.toString().contains(q)) {
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
				if (q.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(q)) {
					filtered.add(at++, e);
				}
			}
		}
	}

	// ---- the frame's slots ----

	@Override
	protected String heading() {
		return heading;
	}

	@Override
	protected String subheading() {
		return subheading;
	}

	@Override
	protected String headingIcon() {
		return icon;
	}

	private float gridBottom() {
		float f = footerH();
		return H - M - (f > 0 ? f + 16 : 0);
	}

	@Override
	protected void drawBody(OriginDeck d) {
		filter();
		tipTitle = null;
		tipExtra = null;
		drawToolbar(d);
		drawGrid(d);
		float f = footerH();
		if (f > 0) {
			drawFooter(d, M, H - M - f, BODY_W, f);
		}
		if (tipTitle != null) {
			drawTip(d);
		}
	}

	// ---- toolbar: category chips | search ----

	private void drawToolbar(OriginDeck d) {
		float x = M;
		for (Cat c : Cat.values()) {
			String label = c.label.toUpperCase(Locale.ROOT);
			boolean on = cat == c;
			String count = on ? Integer.toString(filtered.size()) : null;
			float w = 24 + d.textW(label, 11, true, CAPS_TRACK) + (count == null ? 0 : 7 + d.textW(count, 11, true, 0));
			boolean hov = d.over(x, TOOL_Y, w, 32);
			float hp = OriginUi.hover(hoverKey + ":chip:" + c, hov);
			d.rect(x, TOOL_Y, w, 32, 6, on ? ACC_SOFT : lerp(0x0AFFFFFF, 0x14FFFFFF, hp));
			float lw = d.textMid(label, x + 12, TOOL_Y + 16, 11, true, CAPS_TRACK, on ? ACC : lerp(DIM, TEXT, hp));
			if (count != null) {
				d.textMid(count, x + 12 + lw + 7, TOOL_Y + 16, 11, true, 0, mul(ACC, 0.8f));
			}
			d.hit(x, TOOL_Y, w, 32, () -> {
				cat = c;
				scroll = scrollTarget = 0;
			});
			x += w + 8;
		}
		float sw = Math.max(140, Math.min(240, W - M - (x + 4)));
		float sx = W - M - sw;
		field(d, sx, TOOL_Y, sw, 32, search, "Search items", searchFocused, "@find");
		d.hit(sx, TOOL_Y, sw, 32, () -> searchFocused = true);
	}

	// ---- the grid ----

	private void drawGrid(OriginDeck d) {
		float top = GRID_Y, bottom = gridBottom();
		float pitch = CELL + GAP;
		int rows = (filtered.size() + COLS - 1) / COLS;
		float contentH = rows == 0 ? 0 : rows * pitch - GAP;
		maxScroll = Math.max(0, contentH - (bottom - top));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);

		if (filtered.isEmpty()) {
			d.textCenter("No items match \"" + search + "\"", W / 2, top + 60, 14, false, 0, MUTE);
			return;
		}
		d.clip(M - 4, top, BODY_W + 8, bottom - top);
		// Only walk the rows that are actually on screen: per-frame cost scales
		// with the view, not the ~1300-item list.
		int firstRow = Math.max(0, (int) (scroll / pitch));
		int lastRow = (int) ((scroll + (bottom - top)) / pitch) + 1;
		int last = Math.min(filtered.size(), (lastRow + 1) * COLS);
		for (int i = firstRow * COLS; i < last; i++) {
			float x = M + (i % COLS) * pitch;
			float y = top + (i / COLS) * pitch - (float) scroll;
			if (y + CELL < top || y > bottom) {
				continue;
			}
			Entry e = filtered.get(i);
			boolean hov = d.over(x, y, CELL, CELL);
			boolean sel = isSelected(e);
			boolean marked = !sel && isMarked(e);
			float hp = OriginUi.hover(hoverKey + ":cell:" + e.key, hov);
			int fill = sel ? lerp(ACC_SOFT, withAlpha(ACC, 0x38), hp) : lerp(CARD, OriginDeck.CARD_H, hp);
			int border = sel ? mul(ACC, 0.7f) : marked ? mul(ACC, 0.45f) : lerp(LINE, 0x29F1E9D2, hp);
			d.rect(x, y, CELL, CELL, 6, fill, border);
			drawItem(d, e.stack, x, y, CELL, ITEM);
			d.hit(x, y, CELL, CELL, () -> cellClicked(e));
			if (hov) {
				tooltip(e.name, tooltipExtra(e));
			}
		}
		d.unclip();

		// soft fade where the grid is cut off (lifted over the item icons)
		if (Mods.metaNum("menuBgOpacity", 1.0) > 0.02 && maxScroll > 0 && scroll < maxScroll - 1) {
			float a = (float) Mods.metaNum("menuBgOpacity", 1.0);
			inFront(d, () -> d.gradient(1, bottom - 32, W - 2, 32, mul(PANEL, 0), mul(PANEL, a)));
		}
		// thin scrollbar at the panel's right edge
		if (maxScroll > 0) {
			float track = bottom - top;
			float thumb = Math.max(28, track * track / (float) (track + maxScroll));
			float ty = top + (track - thumb) * (float) (scroll / maxScroll);
			d.rect(W - 10, ty, 3, thumb, 1.5f, 0x38F1E9D2);
		}
	}

	// ---- shared helpers for subclasses ----

	/**
	 * An item icon centred in a box (design px). The icon is `size` design px
	 * rounded to a whole multiple of 16 PHYSICAL px (so every texel is the same
	 * width), never larger than the box.
	 */
	protected void drawItem(OriginDeck d, ItemStack stack, float boxX, float boxY, float box, float size) {
		if (stack == null || stack.isEmpty() || (d.fa(0xFF000000) >>> 24) < 8) {
			return;
		}
		int k = Math.max(1, Math.round(size * d.s / 16f));
		while (k > 1 && 16 * k > box * d.s) {
			k--;
		}
		int phys = 16 * k;
		float px = Math.round(d.ox + (boxX + box / 2f) * d.s - phys / 2f);
		float py = Math.round(d.oy + (boxY + box / 2f) * d.s - phys / 2f);
		var pose = d.g.pose();
		pose.pushMatrix();
		try {
			pose.translate(px, py);
			// 16 GUI px -> 16*k physical px (the deck pose is 1/guiScale); the item
			// is rendered into this era's item atlas and blitted through this pose
			pose.scale(k, k);
			d.g.renderFakeItem(stack, 0, 0);
		} catch (RuntimeException ex) {
			// fail soft: a broken item model draws nothing
		} finally {
			pose.popMatrix();
		}
	}

	/**
	 * Runs deck draws in front of item icons. 1.21.11: no pose z -- a box
	 * submitted after the items it overlaps goes to a higher GUI layer, so
	 * running the draws after the items is the whole lift (see the class doc).
	 */
	protected static void inFront(OriginDeck d, Runnable draws) {
		draws.run();
	}

	/** Shows a tooltip at the mouse this frame (title + optional grey line). */
	protected void tooltip(String title, String extra) {
		tipTitle = title;
		tipExtra = extra;
	}

	private void drawTip(OriginDeck d) {
		var win = net.minecraft.client.Minecraft.getInstance().getWindow();
		inFront(d, () -> {
			float tw = d.textW(tipTitle, 13, true, 0);
			if (tipExtra != null) {
				tw = Math.max(tw, d.textW(tipExtra, 12, false, 0));
			}
			float w = tw + 20, h = tipExtra == null ? 30 : 48;
			float x = d.mx + 14, y = d.my + 18;
			float maxX = (win.getWidth() - d.ox) / d.s - 8, maxY = (win.getHeight() - d.oy) / d.s - 8;
			if (x + w > maxX) {
				x = d.mx - 14 - w;
			}
			if (y + h > maxY) {
				y = d.my - 10 - h;
			}
			d.rect(x, y, w, h, 6, 0xF50E1110, LINE2);
			d.textMid(tipTitle, x + 10, y + 15, 13, true, 0, TEXT);
			if (tipExtra != null) {
				d.textMid(tipExtra, x + 10, y + 33, 12, false, 0, DIM);
			}
		});
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() == 0) {
			searchFocused = false;   // the search field's own hit turns it back on
		}
		return super.mouseClicked(event, doubled);
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
		if (event.key() == GLFW.GLFW_KEY_BACKSPACE && searchFocused && !search.isEmpty()) {
			search = search.substring(0, search.length() - 1);
			scrollTarget = scroll = 0;
			return true;
		}
		return super.keyPressed(event);   // Esc → back() → returnTo
	}
}
