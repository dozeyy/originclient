package com.origin.client.client.gui;

import com.origin.client.client.mods.ItemSizes;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 *
 * PER-VERSION DELTA (26.2), ported from the 1.21.11 screen (SAME render era):
 * - Screen draws through {@code extractRenderState(GuiGraphicsExtractor, ...)}, not
 *   {@code render(GuiGraphics, ...)} — the retained-mode GUI. Everything the 1.21.11
 *   copy did through {@code GuiGraphics} it does through {@code GuiGraphicsExtractor}
 *   ({@code fill}/{@code enableScissor}/{@code pose}/{@code setTooltipForNextFrame}
 *   all survive; {@code renderFakeItem} → {@code fakeItem}).
 * - Text uses the VANILLA font (this module has no SDF {@code OriginText} yet, and
 *   the mod menu is vanilla-font by design): {@code OriginText.draw/drawBold} →
 *   {@code g.text(font, …)}, {@code OriginText.width/widthBold} → {@code font.width},
 *   {@code OriginText.ellipsize} → the local {@link #ellipsize} helper. The back
 *   chevron is a font glyph rather than {@code OriginUi.iconChevron} (absent here).
 * - {@code Minecraft.setScreen} → {@code setScreenAndShow}.
 * - SwordItem / ArmorItem don't exist (folded into components) — categorisation uses
 *   the registry path suffix + the {@code equippable} component, same as 1.21.11.
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

	// Vanilla-font ellipsize: trim to fit maxW, appending "…" when truncated.
	private static String ellipsize(Font font, String s, int maxW) {
		if (font.width(s) <= maxW) {
			return s;
		}
		String ell = "...";
		int budget = maxW - font.width(ell);
		String head = font.plainSubstrByWidth(s, Math.max(0, budget));
		return head + ell;
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

	/** In-category sort key. Lower = earlier. Gear is grouped by type then tier/slot;
	 *  everything else falls back to registry order (regId) via a big base so it
	 *  trails the explicitly-ranked entries. */
	private static int categoryOrder(Cat cat, String path) {
		switch (cat) {
			case COMBAT: {
				if (path.endsWith("_sword")) {
					int t = indexOf(TIERS, path.substring(0, path.length() - 6));
					return t >= 0 ? t : 6;
				}
				int i = indexOf(new String[]{"shield", "mace", "trident", "bow", "crossbow"}, path);
				return i >= 0 ? 10 + i : 100;
			}
			case TOOLS: {
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
				for (int slot = 0; slot < ARMOR_SLOTS.length; slot++) {
					if (path.endsWith("_" + ARMOR_SLOTS[slot]) || path.equals("turtle_helmet")) {
						String tierName = path.equals("turtle_helmet") ? "turtle"
								: path.substring(0, path.length() - ARMOR_SLOTS[slot].length() - 1);
						int tier = indexOf(ARMOR_TIERS, tierName);
						if (tier < 0) {
							tier = ARMOR_TIERS.length;
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
				return 0;
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

	// No SwordItem — swords are plain Items; the registry "_sword" suffix identifies
	// them, matching categoryOrder above. (ItemTags.SWORDS only populates once a world
	// loads; this grid is reachable before that, so the path is the stable test.)
	private static boolean isSword(Identifier id) {
		return id.getPath().endsWith("_sword");
	}

	// No ArmorItem — wearability is the `equippable` component; armour = equippable
	// into an armour slot. BlockItems excluded so wearable blocks stay in Blocks.
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
		return py() + 86;
	}

	private int gridBottom() {
		return py() + ph() - (selected != null ? 44 : 12);
	}

	private static final int CELL = 26;

	private int cols() {
		return Math.max(1, (gridRight() - gridLeft()) / CELL);
	}

	// What `filtered` was last built for. Rebuilding is O(all items) plus a sort, and
	// extractRenderState calls filter() unconditionally — so cache the last query/cat
	// to avoid re-filtering + re-sorting the ~1300-item registry every frame.
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
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
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
		// Back chevron as a font glyph (no OriginUi.iconChevron in this module).
		g.text(font, "‹", px() + 22, hy + 6, OriginTheme.TEXT, false);
		g.text(font, "Item Size", px() + 46, hy + 2, OriginTheme.TEXT, false);
		g.text(font, "Set dropped-item render sizes per item.", px() + 46, hy + 13, OriginTheme.MUTED, false);

		// search bar
		int sy = py() + 40;
		int sx = gridLeft();
		int sw = gridRight() - gridLeft();
		OriginUi.panel(g, sx, sy, sw, 20, 8, searchFocused ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
				searchFocused ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER);
		OriginUi.icon(g, "@search", sx + 5, sy + 3, 14, OriginTheme.MUTED);
		if (search.isEmpty() && !searchFocused) {
			g.text(font, "Search items", sx + 24, sy + 6, OriginTheme.MUTED, false);
		} else {
			g.text(font, search, sx + 24, sy + 6, OriginTheme.TEXT, false);
		}

		// category tabs
		int tx = gridLeft();
		int ty = py() + 62;
		for (Cat c : Cat.values()) {
			int tw = font.width(c.label) + 16;
			boolean active = cat == c;
			boolean hover = in(mouseX, mouseY, tx, ty, tx + tw, ty + 16);
			OriginUi.panel(g, tx, ty, tw, 16, 6,
					active ? OriginTheme.BOX_FILL_HOVER : (hover ? OriginTheme.BOX_FILL : 0x00000000),
					active ? OriginTheme.STROKE_STRONG : 0);
			g.text(font, c.label, tx + 8, ty + 4,
					active ? OriginTheme.TEXT : (hover ? OriginTheme.TEXT_DIM : OriginTheme.MUTED), false);
			tx += tw + 4;
		}

		// grid
		int top = gridTop(), bottom = gridBottom();
		g.enableScissor(px(), top, px() + pw(), bottom);
		int cols = cols();
		int gx0 = gridLeft();
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
				g.setTooltipForNextFrame(font, Component.literal(h.name), mouseX, mouseY);
			}
		}
	}

	private void renderSizeBar(GuiGraphicsExtractor g, int mx, int my) {
		Entry e = entryById(selected);
		if (e == null) {
			selected = null;
			return;
		}
		int by = py() + ph() - 38;
		int bx0 = gridLeft(), bx1 = gridRight();
		OriginUi.panel(g, bx0, by, bx1 - bx0, 30, 8, OriginTheme.BOX_FILL, OriginTheme.BOX_BORDER);
		drawItemIcon(g, e.stack, bx0 + 6, by + 7, 16);
		String name = ellipsize(font, e.name, 130);
		g.text(font, name, bx0 + 28, by + 11, OriginTheme.TEXT, false);

		float size = ItemSizes.get(e.id);
		// Reset button (right)
		int rW = 46, rX = bx1 - 8 - rW;
		boolean rHover = in(mx, my, rX, by + 6, rX + rW, by + 24);
		OriginUi.panel(g, rX, by + 6, rW, 18, 6, rHover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
				rHover ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER);
		g.text(font, "Reset", rX + (rW - font.width("Reset")) / 2, by + 11, OriginTheme.TEXT_DIM, false);

		// value label
		String val = String.format("%.2fx", size);
		int vW = font.width(val);
		int vX = rX - 8 - vW;
		g.text(font, val, vX, by + 11, OriginTheme.TEXT, false);

		// slider track (between name and value)
		int trackX = bx0 + 170;
		int trackW = vX - 10 - trackX;
		if (trackW > 20) {
			double t = (size - ItemSizes.MIN) / (ItemSizes.MAX - ItemSizes.MIN);
			OriginUi.slider(g, trackX, by + 12, trackW, t, draggingSlider);
		}
	}

	private void drawItemIcon(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int size) {
		// pose() is a Matrix3x2fStack — pushMatrix/popMatrix + 2-arg translate/scale.
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		float s = size / 16f;
		pose.scale(s, s);
		g.fakeItem(stack, 0, 0);
		pose.popMatrix();
	}

	// Id -> entry, built once alongside ALL_ITEMS (entryById runs every frame while an
	// item is selected; a map keeps it off a linear scan of ~1300 entries).
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

	// The cell under the cursor, by arithmetic (runs every frame for hover + tooltip).
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
		int vX = rX - 8 - font.width(val);
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
			int tw = font.width(c.label) + 16;
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
		Minecraft.getInstance().setScreenAndShow(new OriginModMenuScreen());
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}
}
