package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.hud.HudEditorScreen;
import com.origin.client.client.hud.HudElements;
import com.origin.client.client.mods.ModOption;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.Profiles;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * The Right Shift mod menu -- 2026-10 redesign: Lunar's layout, Origin's look,
 * the smooth font. Built 1:1 from Will's approved mock (design/menu-redesign,
 * v5: mock4.tpl.html is the spec; the PNGs in design/menu-redesign/v5 are what
 * he signed off). One centred 920x580 panel in design px (OriginDeck scales it):
 *
 *   header   brand | MODS SETTINGS WAYPOINTS COSMETICS FRIENDS | Edit HUD  x
 *            (the tabs centred in the space between the brand and Edit HUD)
 *   toolbar  profile dropdown | category chips | grid/list | search
 *   body     4-across mod cards (or a 4-across list), or a mod's own page
 *            (back, title, description, live HUD preview, two-column options),
 *            or the Settings tab (General / Performance / Menu).
 *
 * Alignment rule (Will: "line up the words with the lines"): everything sits
 * on ONE 24px content margin and the 16px gap; labels are cap-centred in their
 * rows. Click targets are recorded while drawing (OriginDeck.hit), so render
 * and input share one geometry. Esc always goes up exactly one level.
 *
 * Pre-1.20 port of 1.21.1's screen: drawing goes through the module's Gfx shim
 * (no GuiGraphics), and render/mouseScrolled take this era's signatures.
 */
public class OriginModMenuScreen extends Screen {
	// ---- the design grid (design px; see the class comment) ----
	private static final float W = 920, H = 580;
	private static final float M = 24;               // the one content margin
	private static final float CW = W - 2 * M;       // content width (872)
	private static final float HEAD_H = 64;
	private static final float BAR_Y = 80;           // toolbar row, 32 tall
	private static final float BODY_Y = 128;         // first card / row
	private static final float BOTTOM = 572;         // content stops 8 above the panel foot
	private static final float GAP = 16;
	private static final float CARD_H = 184, ROW_H = 44, OPT_H = 40;
	private static final float SEARCH_W = 176;

	private static final long OPEN_MS = 180, PAGE_MS = 150;

	private enum Tab {MODS, SETTINGS}

	private enum Cat {
		ALL("All"), NEW("New"), HUD("HUD"), VISUAL("Visual"), GAMEPLAY("Gameplay"), SERVER("Server");

		final String label;

		Cat(String label) {
			this.label = label;
		}
	}

	private enum SubTab {
		GENERAL("General"), PERFORMANCE("Performance"), MENU("Menu");

		final String label;

		SubTab(String label) {
			this.label = label;
		}
	}

	private final Screen returnTo;
	private final long openedAt = System.currentTimeMillis();
	private long closingAt = -1;

	private Tab tab = Tab.MODS;
	private Cat cat = Cat.ALL;
	private SubTab subTab = SubTab.GENERAL;
	private String page = null;                       // open mod's id, null = the library
	private long pageChangedAt = 0;

	private String search = "";
	private boolean searchFocused = false;
	private String optSearch = "";                    // mod page / settings tab filter
	private double scroll, scrollTarget, pageScroll, pageScrollTarget;
	private float maxScroll, maxPageScroll;
	private long lastFrameNanos = 0;

	// slider drag / keybind capture / text field (the proven machinery, ported)
	private String dragMod, dragKey;
	private ModOption dragOpt;
	private float dragX0, dragX1;
	private boolean sliderUnsaved = false;
	private String capMod, capKey;
	private String textMod, textKey, textBuf = "";
	private static final int TEXT_MAX = 128;

	// profiles: the dropdown, its name field, the delete confirmation, the toast
	private boolean profOpen = false;
	private String profileInput = "";
	private boolean profileFocused = false;
	private String confirmDelete = null;
	private long toastAt = -1;
	private String toastText = "";

	// tooltip for this frame + the card-hover delay
	private String tip;
	private String hoverCard;
	private long hoverCardSince;

	// entrance stagger whenever the visible set changes
	private String lastLayoutKey = "";
	private long rowsChangedAt = 0;

	// last frame's mapping, for input between frames
	private final List<OriginDeck.Hit> hits = new ArrayList<>();
	private float lastS = 1, lastOx, lastOy;

	public OriginModMenuScreen() {
		this(null, null);
	}

	/**
	 * Opens the menu from another screen. `returnTo` is where Close lands;
	 * `startPage` (a Mods id, or null for the library) opens straight onto that
	 * mod's page -- ignored if this build has no such mod.
	 */
	public OriginModMenuScreen(Screen returnTo, String startPage) {
		super(Component.literal("Origin Mods"));
		this.returnTo = returnTo;
		if (startPage != null && Mods.byId(startPage) != null) {
			this.page = startPage;
		}
	}

	/** Opens on the Settings tab (the Right Shift screen's gear). */
	public OriginModMenuScreen settingsTab() {
		tab = Tab.SETTINGS;
		return this;
	}

	/** Dev-shots only: open on the list view or the Settings tab. */
	public OriginModMenuScreen preview(boolean list, boolean settings) {
		Mods.setMetaBool("menuListView", list);
		if (settings) {
			tab = Tab.SETTINGS;
		}
		return this;
	}

	@Override
	protected void init() {
		super.init();
		HudElements.editorPreview = true;
		HudElements.suppressScoreboard = true;
	}

	@Override
	public void removed() {
		flushSlider();
		commitText();
		HudElements.editorPreview = false;
		HudElements.suppressScoreboard = false;
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	// ---- categories ----
	// Static by id: the registry has no category field, and this is a design
	// call about how a PLAYER thinks of each mod. Unmapped ids land in Gameplay
	// so a new mod never disappears.
	private static final Map<String, Cat> CATS = new HashMap<>();

	static {
		for (String id : new String[]{"fps", "cps", "armorhud", "keystrokes", "coords", "potionhud", "scoreboard",
				"tablist", "clock", "stopwatch", "daycounter", "ping", "memory", "saturation", "packdisplay",
				"itemcounter", "directionhud", "combo", "reach", "bossbar", "titles", "cooldowns", "minimap", "portalcalc"}) {
			CATS.put(id, Cat.HUD);
		}
		for (String id : new String[]{"zoom", "fullbright", "blockoverlay", "crosshair", "chunkborders", "hitboxes",
				"nametags", "itemsize", "weather", "timechanger", "motionblur", "colorsaturation", "particles",
				"hurtcam", "hitcolor", "glintcolorizer", "menublur", "shinypots", "tntcountdown", "itemphysics",
				"worldeditcui"}) {
			CATS.put(id, Cat.VISUAL);
		}
		for (String id : new String[]{"togglesprint", "freelook", "chat", "waypoints", "jei", "snaplook",
				"soundchanger", "screenshot", "fpscoach"}) {
			CATS.put(id, Cat.GAMEPLAY);
		}
		// Things that only matter on a server (Hypixel, lobbies, teams, chat macros).
		for (String id : new String[]{"serveraddress", "hypixel", "hypixelbedwars", "teamview", "autogg",
				"quickplay", "nickhider", "discord", "autotext", "friends"}) {
			CATS.put(id, Cat.SERVER);
		}
	}

	// The NEW filter + badge: this release's additions. The badge clears once the
	// player has opened that mod's page (meta "seen:<id>").
	private static final Set<String> NEW_IDS = Set.of("portalcalc", "fpscoach", "hypixelbedwars", "friends");

	private static Cat catOf(Mods.Mod m) {
		return CATS.getOrDefault(m.id(), Cat.GAMEPLAY);
	}

	private static boolean showNewBadge(String id) {
		return NEW_IDS.contains(id) && !Mods.metaBool("seen:" + id, false);
	}

	private static boolean inCat(Cat c, Mods.Mod m) {
		return switch (c) {
			case ALL -> true;
			case NEW -> NEW_IDS.contains(m.id());
			default -> catOf(m) == c;
		};
	}

	// ---- value backing (JEI keeps its own store) ----

	private static boolean isJei(String id) {
		return "jei".equals(id);
	}

	private List<ModOption> optionsFor(Mods.Mod mod) {
		return isJei(mod.id()) ? JeiSettings.options() : mod.options();
	}

	private boolean vBool(String id, String key) {
		return isJei(id) ? JeiSettings.getBool(key) : Mods.bool(id, key);
	}

	private void vSetBool(String id, String key, boolean v) {
		if (isJei(id)) {
			JeiSettings.setBool(key, v);
		} else {
			Mods.set(id, key, v);
		}
	}

	private double vNum(String id, String key) {
		return isJei(id) ? JeiSettings.getNum(key) : Mods.num(id, key);
	}

	// Slider drags write memory only; flushSlider() saves once on release (a
	// per-event JSON write was dozens of synchronous writes a second). Time
	// Changer's slider also pushes straight into the live sky.
	private void vDragNum(String id, String key, double v) {
		if (isJei(id)) {
			JeiSettings.setNum(key, v);
			return;
		}
		Mods.setUnsaved(id, key, v);
		sliderUnsaved = true;
		if ("timechanger".equals(id) && "time".equals(key)) {
			com.origin.client.client.OriginClientMod.previewTimeSlider(v);
		}
	}

	private void flushSlider() {
		if (sliderUnsaved) {
			sliderUnsaved = false;
			Mods.save();
		}
	}

	private String vMode(String id, String key) {
		return isJei(id) ? JeiSettings.getMode(key) : Mods.mode(id, key);
	}

	private void vSetMode(String id, String key, String v) {
		if (isJei(id)) {
			JeiSettings.setMode(key, v);
		} else {
			Mods.set(id, key, v);
		}
	}

	private String vMulti(String id, String key) {
		return isJei(id) ? JeiSettings.getMulti(key) : Mods.mode(id, key);
	}

	private void vSetMulti(String id, String key, String csv) {
		if (isJei(id)) {
			JeiSettings.setMulti(key, csv);
		} else {
			Mods.set(id, key, csv);
		}
	}

	/** Differs from its schema default? (drives the reset arrow; JEI has none) */
	private boolean changed(String id, ModOption o) {
		if (isJei(id)) {
			return false;
		}
		return switch (o.kind) {
			case TOGGLE -> Mods.bool(id, o.key) != o.defBool;
			case SLIDER -> Math.abs(Mods.num(id, o.key) - o.defNum) > Math.max(1e-6, o.step / 2);
			case COLOR -> Mods.color(id, o.key) != o.defColor || OriginColorPicker.chromaOn(id, o.key);
			case KEYBIND -> Mods.keyCode(id, o.key) != o.defKey;
			case DROPDOWN -> o.modes != null && o.modes.length > 0 && !Mods.mode(id, o.key).equals(o.modes[0]);
			case TEXT -> !Mods.text(id, o.key).equals(o.defText == null ? "" : o.defText);
			default -> false;
		};
	}

	// Item Counter's "Items" row opens the shared item grid (Item Size's layout).
	private static boolean isItemPicker(String modId, String key) {
		return modId.equals("itemcounter") && key.equals("items");
	}

	private static String itemPickerSummary(String csv) {
		List<String> names = new ArrayList<>();
		for (String t : com.origin.client.client.mods.ItemCounter.tokens(csv)) {
			var e = com.origin.client.client.mods.ItemCounter.resolve(t);
			if (e != null) {
				names.add(e.label());
			}
		}
		return names.isEmpty() ? "None" : String.join(", ", names);
	}

	private static List<String> splitCsv(String csv) {
		List<String> out = new ArrayList<>();
		if (csv != null) {
			for (String p : csv.split(",")) {
				String t = p.trim();
				if (!t.isEmpty()) {
					out.add(t);
				}
			}
		}
		return out;
	}

	// ---- navigation ----

	private void openMod(Mods.Mod mod) {
		Mods.setMetaBool("seen:" + mod.id(), true);
		switch (mod.id()) {
			case "waypoints" -> Minecraft.getInstance().setScreen(new com.origin.client.client.waypoints.WaypointScreen());
			case "itemsize" -> Minecraft.getInstance().setScreen(new OriginItemSizeScreen());
			case "fpscoach" -> Minecraft.getInstance().setScreen(new FpsCoachScreen(this));
			default -> goPage(mod.id());
		}
	}

	private void goPage(String id) {
		commitText();
		page = id;
		pageChangedAt = System.currentTimeMillis();
		optSearch = "";
		pageScroll = pageScrollTarget = 0;
		profOpen = false;
	}

	private void goTab(Tab t) {
		commitText();
		tab = t;
		page = null;
		pageChangedAt = System.currentTimeMillis();
		optSearch = "";
		pageScroll = pageScrollTarget = 0;
		searchFocused = false;
		profOpen = false;
	}

	public void beginClose() {
		if (closingAt < 0) {
			closingAt = System.currentTimeMillis();
		}
	}

	private static String activeProfile() {
		String p = Mods.metaText("activeProfile", "");
		return p.isEmpty() || !Profiles.exists(p) ? "Default" : p;
	}

	private void toast(String text) {
		toastText = text;
		toastAt = System.currentTimeMillis();
	}

	// ---- render ----

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		Gfx g = new Gfx(poseStack);
		// The HUD is drawn once per frame BEHIND every screen (GuiHudMixin), so
		// toggles preview live under the menu -- never draw it from here (Will's
		// rule: the HUD always sits behind menus). Out of a world nothing paints
		// behind this screen, so draw the title backdrop.
		if (Minecraft.getInstance().level == null) {
			com.origin.client.client.render.OriginScreenRenderer.renderTitleBackground(g);
		} else {
			com.origin.client.client.mods.MenuBlur.renderBehind(g, this, partialTick);
		}
		long now = System.currentTimeMillis();
		long nanos = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 16.7 : Math.min(50.0, (nanos - lastFrameNanos) / 1_000_000.0);
		lastFrameNanos = nanos;
		double ease = Math.min(1.0, dt / 60.0);
		scroll += (scrollTarget - scroll) * ease;
		pageScroll += (pageScrollTarget - pageScroll) * ease;

		float p = (float) OriginTheme.easeOut(Math.min(1.0, (now - openedAt) / (double) OPEN_MS));
		if (closingAt > 0) {
			double cp = Math.min(1.0, (now - closingAt) / (double) OPEN_MS);
			p = (float) OriginTheme.easeOut(1.0 - cp);
			if (cp >= 1.0) {
				Minecraft.getInstance().setScreen(returnTo);
				return;
			}
		}

		var win = Minecraft.getInstance().getWindow();
		float s = OriginDeck.scale(win.getWidth(), win.getHeight(), W, H);
		float ox = (win.getWidth() - W * s) / 2f;
		float oy = (win.getHeight() - H * s) / 2f + (1 - p) * 12 * s;
		lastS = s;
		lastOx = ox;
		lastOy = oy;
		OriginDeck d = new OriginDeck(g, s, ox, oy, mouseX, mouseY, hits);
		d.alpha = p;
		// "Solid Menu Background" off: no panel, so the kit lifts the text over the world
		d.seeThrough = OriginDeck.menuSeeThrough();
		tip = null;

		drawShell(d);
		float t = pageChangedAt == 0 ? 1f
				: (float) OriginTheme.easeOut(Math.min(1.0, (now - pageChangedAt) / (double) PAGE_MS));
		d.alpha = p * t;
		Mods.Mod mod = page == null ? null : Mods.byId(page);
		if (page != null && mod == null) {
			page = null;
		}
		if (mod != null) {
			drawModPage(d, mod);
		} else if (tab == Tab.SETTINGS) {
			drawSettingsTab(d);
		} else {
			drawLibrary(d, now);
		}
		d.alpha = p;
		if (profOpen && tab == Tab.MODS && page == null) {
			drawProfiles(d);
		}
		if (confirmDelete != null) {
			drawConfirm(d);
		}
		drawToast(d, now);
		if (tip != null && confirmDelete == null && !OriginColorPicker.isOpen() && !OriginMultiSelect.isOpen()) {
			drawTip(d, tip, win.getWidth(), win.getHeight());
		}
		d.end();

		OriginMultiSelect.render(g, mouseX, mouseY);
		OriginColorPicker.render(g, mouseX, mouseY);
	}

	// ---- the shell: panel, header, tabs ----

	private void drawShell(OriginDeck d) {
		double op = Mods.metaNum("menuBgOpacity", 1.0);
		if (op > 0.02) {
			d.shadow(0, 0, W, H, 8);
			d.rect(0, 0, W, H, 8, mul(PANEL, (float) op), 0x12FFFFFF);
		}
		d.fill(1, HEAD_H, W - 2, 1, LINE);

		// brand: the real Origin mark + ORIGIN (bold) CLIENT (light)
		d.logo(M + 16, HEAD_H / 2, 32, 0.12f);
		float bx = M + 32 + 10;
		bx += d.textMid("ORIGIN", bx, HEAD_H / 2, 18, true, 0.4f, TEXT);
		float brandEnd = bx + 7 + d.textMid("CLIENT", bx + 7, HEAD_H / 2, 18, false, 0.4f, DIM);

		// close, then Edit HUD to its left (measured first: the tabs centre between it and the brand)
		float cx = W - M - 32;
		String eh = "EDIT HUD";
		float ew = 14 + 14 + 8 + d.textW(eh, 11, true, CAPS_TRACK) + 14;
		float ex = cx - 8 - ew;

		// Five tabs (Friends joined 2026-10-12): 12 px padding each side and a 4 px
		// gap so the row fits between the brand and Edit HUD with equal room on
		// both sides -- centred in that free space, not on the panel, which would
		// leave a cramped left gap and a wide right one.
		String[] tabs = {"Mods", "Settings", "Waypoints", "Cosmetics", "Friends"};
		float[] tw = new float[tabs.length];
		float total = 4 * (tabs.length - 1);
		for (int i = 0; i < tabs.length; i++) {
			tw[i] = d.textW(tabs[i].toUpperCase(Locale.ROOT), 11, true, CAPS_TRACK) + 24;
			total += tw[i];
		}
		float tx = (brandEnd + ex) / 2f - total / 2f;
		int attention = com.origin.client.client.social.SocialClient.attention();
		for (int i = 0; i < tabs.length; i++) {
			boolean active = (i == 0 && tab == Tab.MODS) || (i == 1 && tab == Tab.SETTINGS);
			boolean hov = d.over(tx, 17, tw[i], 30);
			float hp = OriginUi.hover("deck-tab:" + i, hov);
			if (active) {
				d.rect(tx, 17, tw[i], 30, 6, 0x12FFFFFF);
			} else if (hp > 0.01f) {
				d.rect(tx, 17, tw[i], 30, 6, mul(0x0AFFFFFF, hp));
			}
			d.textCenter(tabs[i].toUpperCase(Locale.ROOT), tx + tw[i] / 2, 32, 11, true, CAPS_TRACK,
					active ? TEXT : lerp(MUTE, DIM, hp));
			if (i == 4 && attention > 0) {
				// unread messages / requests / invites waiting
				d.circle(tx + tw[i] - 6, 22, 3.5f, ACC);
			}
			final int idx = i;
			d.hit(tx, 17, tw[i], 30, () -> {
				switch (idx) {
					case 0 -> goTab(Tab.MODS);
					case 1 -> goTab(Tab.SETTINGS);
					case 2 -> Minecraft.getInstance().setScreen(new com.origin.client.client.waypoints.WaypointScreen());
					case 3 -> Minecraft.getInstance().setScreen(new CosmeticsScreen(this));
					default -> Minecraft.getInstance().setScreen(new FriendsScreen(this));
				}
			});
			tx += tw[i] + 4;
		}

		boolean ch = d.over(cx, 16, 32, 32);
		d.rect(cx, 16, 32, 32, 6, ch ? 0x1AFFFFFF : 0x0AFFFFFF);
		d.icon("@close", cx + 9, 25, 14, ch ? TEXT : DIM);
		d.hit(cx, 16, 32, 32, this::beginClose);

		boolean ehov = d.over(ex, 16, ew, 32);
		d.rect(ex, 16, ew, 32, 6, ehov ? 0xFF83E2B5 : ACC);
		d.icon("@hudeditor", ex + 14, 25, 14, ON_ACC);
		d.textMid(eh, ex + 36, 32, 11, true, CAPS_TRACK, ON_ACC);
		d.hit(ex, 16, ew, 32, () -> Minecraft.getInstance().setScreen(new HudEditorScreen(false, this)));
	}

	// ---- the library: toolbar + cards / list ----

	private final List<Mods.Mod> visible = new ArrayList<>();

	private void filter() {
		visible.clear();
		String q = search.toLowerCase(Locale.ROOT).trim();
		for (Mods.Mod m : Mods.ALL) {
			if (inCat(cat, m) && (q.isEmpty() || matches(m, q))) {
				visible.add(m);
			}
		}
		// pinned first, then A-Z (an alphabetical library is the fastest to scan)
		visible.sort((a, b) -> {
			int f = Boolean.compare(Mods.metaBool("fav:" + b.id(), false), Mods.metaBool("fav:" + a.id(), false));
			return f != 0 ? f : a.name().compareToIgnoreCase(b.name());
		});
		String key = cat + "|" + q + "|" + Mods.metaBool("menuListView", false);
		if (!key.equals(lastLayoutKey)) {
			lastLayoutKey = key;
			rowsChangedAt = System.currentTimeMillis();
		}
	}

	/** Search hits the name, the description AND every option label. */
	private static boolean matches(Mods.Mod m, String q) {
		String desc = m.description() == null ? "" : m.description();
		if (m.name().toLowerCase(Locale.ROOT).contains(q) || desc.toLowerCase(Locale.ROOT).contains(q)) {
			return true;
		}
		for (ModOption o : m.options()) {
			if (o.kind != ModOption.Kind.HEADER && o.label.toLowerCase(Locale.ROOT).contains(q)) {
				return true;
			}
		}
		return false;
	}

	private int columns() {
		String layout = Mods.mode(Mods.GENERAL_ID, "menuLayout");
		if (layout == null) {
			return 4;
		}
		return switch (layout) {
			case "3 across (compact)" -> 3;
			case "2 across (large)" -> 2;
			default -> 4;
		};
	}

	private void drawLibrary(OriginDeck d, long now) {
		filter();
		boolean list = Mods.metaBool("menuListView", false);

		// toolbar: profile dropdown | chips ... grid/list | search
		float x = M;
		String pn = activeProfile();
		float pw = 12 + 14 + 8 + d.textW(pn, 13, true, 0) + 8 + 14 + 10;
		boolean ph = d.over(x, BAR_Y, pw, 32);
		d.rect(x, BAR_Y, pw, 32, 6, profOpen || ph ? 0x14FFFFFF : 0x0DFFFFFF);
		d.icon("@user", x + 12, BAR_Y + 9, 14, MUTE);
		d.textMid(pn, x + 34, BAR_Y + 16, 13, true, 0, TEXT);
		d.icon("@chev-down", x + pw - 24, BAR_Y + 9, 14, MUTE);
		d.hit(x, BAR_Y, pw, 32, () -> {
			profOpen = !profOpen;
			profileFocused = profOpen;
			searchFocused = false;
		});
		x += pw + 12;
		d.fill(x, BAR_Y + 6, 1, 20, LINE2);
		x += 13;

		for (Cat c : Cat.values()) {
			String label = c.label.toUpperCase(Locale.ROOT);
			String count = c == Cat.ALL ? Integer.toString(Mods.ALL.size()) : null;
			float w = 24 + d.textW(label, 11, true, CAPS_TRACK) + (count == null ? 0 : 7 + d.textW(count, 11, true, 0));
			boolean on = cat == c;
			boolean hov = d.over(x, BAR_Y, w, 32);
			float hp = OriginUi.hover("deck-chip:" + c, hov);
			d.rect(x, BAR_Y, w, 32, 6, on ? ACC_SOFT : lerp(0x0AFFFFFF, 0x14FFFFFF, hp));
			int col = on ? ACC : c == Cat.NEW ? GOLD : lerp(DIM, TEXT, hp);
			float lw = d.textMid(label, x + 12, BAR_Y + 16, 11, true, CAPS_TRACK, col);
			if (count != null) {
				d.textMid(count, x + 12 + lw + 7, BAR_Y + 16, 11, true, 0, on ? mul(ACC, 0.8f) : MUTE);
			}
			d.hit(x, BAR_Y, w, 32, () -> {
				cat = c;
				scroll = scrollTarget = 0;
			});
			x += w + 8;
		}

		// search box: 176 wide, shrunk only if the chips need the room
		float toolsW = 30 + 2 + 30;
		float searchW = Math.max(120, Math.min(SEARCH_W, W - M - (x - 8 + 12 + toolsW + 8)));
		float sx = W - M - searchW;
		float tx = sx - 8 - toolsW;
		drawTool(d, "@grid", tx, !list, () -> Mods.setMetaBool("menuListView", false));
		drawTool(d, "@list", tx + 32, list, () -> Mods.setMetaBool("menuListView", true));
		drawSearch(d, sx, BAR_Y + 1, searchW, search, "Search mods", searchFocused, true, () -> {
			searchFocused = true;
			profOpen = false;
		});

		// body
		int cols = columns();
		float colW = (CW - (cols - 1) * GAP) / cols;
		float itemH = list ? ROW_H : CARD_H;
		float rowGap = list ? 8 : GAP;
		int rows = (visible.size() + cols - 1) / cols;
		float contentH = rows == 0 ? 0 : rows * itemH + (rows - 1) * rowGap + 24;
		maxScroll = Math.max(0, contentH - (BOTTOM - BODY_Y));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);

		d.clip(0, BODY_Y - 8, W, BOTTOM - BODY_Y + 8);
		String hovered = null;
		for (int i = 0; i < visible.size(); i++) {
			Mods.Mod m = visible.get(i);
			float cx = M + (i % cols) * (colW + GAP);
			float cy = BODY_Y + (i / cols) * (itemH + rowGap) - (float) scroll;
			if (cy + itemH < BODY_Y - 8 || cy > BOTTOM) {
				continue;
			}
			// entrance stagger: each item fades + rises a beat after the previous
			float rt = (float) OriginTheme.easeOut(Math.max(0, Math.min(1, (now - rowsChangedAt - i * 14) / 170.0)));
			float base = d.alpha;
			d.alpha = base * rt;
			cy += (1 - rt) * 6;
			if (list) {
				drawRow(d, m, cx, cy, colW);
			} else {
				drawCard(d, m, cx, cy, colW);
			}
			if (d.over(cx, cy, colW, itemH)) {
				hovered = m.id();
			}
			d.alpha = base;
		}
		if (visible.isEmpty()) {
			d.textCenter("No mods match \"" + search + "\"", W / 2, BODY_Y + 60, 14, false, 0, MUTE);
		}
		d.unclip();

		// a card hovered for a moment explains itself (its description)
		if (hovered == null || !hovered.equals(hoverCard)) {
			hoverCard = hovered;
			hoverCardSince = now;
		} else if (now - hoverCardSince > 650 && !profOpen) {
			Mods.Mod m = Mods.byId(hovered);
			if (m != null && m.description() != null && !m.description().isEmpty()) {
				tip = m.description();
			}
		}

		if (Mods.metaNum("menuBgOpacity", 1.0) > 0.02) {
			float a = (float) Mods.metaNum("menuBgOpacity", 1.0);
			d.gradient(1, BOTTOM - 40, W - 2, 40, mul(PANEL, 0), mul(PANEL, a));
		}
		drawScrollbar(d, scroll, maxScroll, BODY_Y, BOTTOM);
	}

	private void drawTool(OriginDeck d, String icon, float x, boolean on, Runnable action) {
		boolean hov = d.over(x, BAR_Y + 2, 30, 28);
		if (on || hov) {
			d.rect(x, BAR_Y + 2, 30, 28, 6, on ? 0x0FFFFFFF : 0x0AFFFFFF);
		}
		d.icon(icon, x + 7.5f, BAR_Y + 8.5f, 15, on ? TEXT : hov ? DIM : MUTE);
		d.hit(x, BAR_Y + 2, 30, 28, action);
	}

	/** The search field (also used on the mod page and the Settings tab). */
	private void drawSearch(OriginDeck d, float x, float y, float w, String value, String placeholder,
							boolean focused, boolean slashHint, Runnable onClick) {
		d.rect(x, y, w, 30, 6, FIELD, focused ? mul(ACC, 0.6f) : LINE2);
		d.icon("@find", x + 10, y + 8, 14, MUTE);
		float tx = x + 32;
		if (value.isEmpty()) {
			d.textMid(placeholder, tx, y + 15, 13, false, 0, MUTE);
		} else {
			value = fitTail(d, value, w - 44);
			tx += d.textMid(value, tx, y + 15, 13, false, 0, TEXT);
		}
		if (focused) {
			caret(d, value.isEmpty() ? x + 32 : tx + 1, y + 8, 14);
		} else if (slashHint && value.isEmpty()) {
			d.rect(x + w - 26, y + 7, 16, 16, 4, 0, LINE2);
			d.textCenter("/", x + w - 18, y + 15, 11, true, 0, MUTE);
		}
		d.hit(x, y, w, 30, onClick);
	}

	private static String fitTail(OriginDeck d, String v, float maxW) {
		while (v.length() > 1 && d.textW(v, 13, false, 0) > maxW) {
			v = v.substring(1);
		}
		return v;
	}

	private void caret(OriginDeck d, float x, float y, float h) {
		float pulse = 0.35f + 0.65f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 350.0));
		d.fill(x, y, 1, h, mul(TEXT, pulse));
	}

	private void drawCard(OriginDeck d, Mods.Mod m, float x, float y, float w) {
		String id = m.id();
		boolean on = Mods.on(id);
		boolean hov = d.over(x, y, w, CARD_H);
		float hp = OriginUi.hover("deck-card:" + id, hov);
		d.rect(x, y, w, CARD_H, 8, lerp(CARD, CARD_H_FILL, hp), lerp(LINE, 0x29F1E9D2, hp));
		d.hit(x, y, w, CARD_H, () -> openMod(m));

		float k = on ? 1f : 0.55f;
		d.icon(id, x + w / 2 - 20, y + 24, 40, mul(TEXT, k));
		d.textCenter(d.fit(m.name(), w - 24, 15, true, 0), x + w / 2, y + 86, 15, true, 0, mul(TEXT, k));

		// OPTIONS bar
		float oy = y + CARD_H - 48 - 30;
		boolean oh = d.over(x + 8, oy, w - 16, 30);
		d.rect(x + 8, oy, w - 16, 30, 5, oh ? 0x1AFFFFFF : lerp(0x0AFFFFFF, 0x14FFFFFF, hp));
		String ol = "OPTIONS";
		float olw = 13 + 8 + d.textW(ol, 10.5f, true, CAPS_TRACK);
		float olx = x + (w - olw) / 2;
		int oc = lerp(DIM, TEXT, Math.max(hp, oh ? 1 : 0));
		d.icon("@title-options", olx, oy + 8.5f, 13, oc);
		d.textMid(ol, olx + 21, oy + 15, 10.5f, true, CAPS_TRACK, oc);
		d.hit(x + 8, oy, w - 16, 30, () -> openMod(m));

		// ENABLED / DISABLED bar -- the toggle
		float sy = y + CARD_H - 8 - 30;
		boolean sh = d.over(x + 8, sy, w - 16, 30);
		float sk = OriginUi.anim("deck-state:" + id, on, 140.0);
		int fillOff = sh ? OFF_SOFT_H : OFF_SOFT, fillOn = sh ? ACC_D_H : ACC_D;
		d.rect(x + 8, sy, w - 16, 30, 5, lerp(fillOff, fillOn, sk));
		d.textCenter(on ? "ENABLED" : "DISABLED", x + w / 2, sy + 15, 11, true, CAPS_TRACK, on ? ON_TEXT : OFF);
		d.hit(x + 8, sy, w - 16, 30, () -> Mods.setOn(id, !on));

		// pin (shown when pinned, offered on hover) + NEW badge
		boolean fav = Mods.metaBool("fav:" + id, false);
		float px = x + w - 8 - 14, py = y + 8;
		if (fav || hov) {
			boolean pinHov = d.over(px - 4, py - 4, 22, 22);
			d.icon("@pin", px, py, 14, pinHov ? TEXT : fav ? DIM : 0x40F1E9D2);
			d.hit(px - 4, py - 4, 22, 22, () -> Mods.setMetaBool("fav:" + id, !fav));
		}
		if (showNewBadge(id)) {
			float bw = 12 + d.textW("NEW", 10, true, 1.4f);
			d.rect(x + 8, y + 8, bw, 16, 4, GOLD_BG);
			d.textMid("NEW", x + 14, y + 16, 10, true, 1.4f, GOLD);
		}
	}

	private static final int CARD_H_FILL = OriginDeck.CARD_H;

	private void drawRow(OriginDeck d, Mods.Mod m, float x, float y, float w) {
		String id = m.id();
		boolean on = Mods.on(id);
		boolean hov = d.over(x, y, w, ROW_H);
		float hp = OriginUi.hover("deck-row:" + id, hov);
		d.rect(x, y, w, ROW_H, 7, lerp(CARD, CARD_H_FILL, hp), mul(0x29F1E9D2, hp));
		d.hit(x, y, w, ROW_H, () -> openMod(m));
		d.icon(id, x + 12, y + 13, 18, on ? TEXT : mul(TEXT, 0.6f));
		boolean isNew = showNewBadge(id);
		float nameMax = w - 40 - 12 - 36 - 10 - (isNew ? 14 : 0);
		float nw = d.textMid(d.fit(m.name(), nameMax, 13.5f, true, 0), x + 40, y + ROW_H / 2, 13.5f, true, 0, on ? TEXT : DIM);
		if (isNew) {
			d.circle(x + 40 + nw + 9, y + ROW_H / 2, 3, GOLD);
		}
		d.toggle(id, x + w - 12 - 36, y + 12, on);
		d.hit(x + w - 12 - 40, y + 6, 44, 32, () -> Mods.setOn(id, !on));
	}

	private void drawScrollbar(OriginDeck d, double sc, float max, float top, float bottom) {
		if (max <= 0) {
			return;
		}
		float view = bottom - top;
		float thumb = Math.max(40, view * view / (view + max));
		float ty = top + (float) (sc / max) * (view - thumb);
		d.rect(W - 9 - 3, ty, 3, thumb, 1.5f, 0x38F1E9D2);
	}

	// ---- a mod's own page ----

	private void drawModPage(OriginDeck d, Mods.Mod mod) {
		String id = mod.id();
		// header row: back | icon title > crumb ............ search  switch
		boolean bh = d.over(M, BAR_Y, 32, 32);
		d.rect(M, BAR_Y, 32, 32, 6, bh ? 0x17FFFFFF : 0x0DFFFFFF);
		d.icon("@chev-left", M + 8.5f, BAR_Y + 8.5f, 15, bh ? TEXT : DIM);
		d.hit(M, BAR_Y, 32, 32, this::back);

		d.icon(id, M + 44, BAR_Y + 5, 22, TEXT);
		float tx = M + 78;
		float tw = d.textMid(mod.name(), tx, BAR_Y + 16, 17, true, 0.3f, TEXT);
		d.textMid("Mods  ›  " + catOf(mod).label, tx + tw + 12, BAR_Y + 17, 12, false, 0, MUTE);

		float swx = W - M - 36;
		boolean on = Mods.on(id);
		d.toggle("page:" + id, swx, BAR_Y + 6, on);
		d.hit(swx - 4, BAR_Y, 44, 32, () -> Mods.setOn(id, !on));
		drawSearch(d, swx - 8 - SEARCH_W, BAR_Y + 1, SEARCH_W, optSearch, "Search options", !optSearch.isEmpty(), false, () -> {
		});

		// scrolling body: description, live preview, options
		float top = BAR_Y + 32 + 12;
		d.clip(0, top - 4, W, BOTTOM - top + 4);
		float y = top - (float) pageScroll;
		String desc = mod.description() == null || mod.description().isEmpty() ? null : mod.description();
		if (desc != null) {
			for (String line : d.wrap(desc, CW, 13, false)) {
				d.textMid(line, M, y + 10, 13, false, 0, DIM);
				y += 20;
			}
		}
		List<HudElements.Element> els = previewElements(id);
		if (!els.isEmpty()) {
			y += 16;
			drawPreview(d, els, y);
			y += 128;
		}
		List<ModOption> opts = optionsFor(mod);
		if (opts.isEmpty()) {
			y += 24;
			d.textMid(isJei(id) ? "JEI settings load once you're in a world."
					: "No extra settings: the switch is everything.", M, y + 8, 13, false, 0, MUTE);
			y += 16;
		} else {
			y = drawOptions(d, id, opts, y);
		}
		d.unclip();
		float contentH = y + (float) pageScroll - top + 16;
		maxPageScroll = Math.max(0, contentH - (BOTTOM - top));
		pageScrollTarget = Math.min(pageScrollTarget, maxPageScroll);
		drawScrollbar(d, pageScroll, maxPageScroll, top, BOTTOM);
	}

	private static List<HudElements.Element> previewElements(String modId) {
		List<HudElements.Element> out = new ArrayList<>();
		if (Minecraft.getInstance().player == null) {
			return out;   // element renderers read the player; no world, no preview
		}
		for (HudElements.Element e : HudElements.ALL) {
			if (e.modId().equals(modId)) {
				try {
					int[] sz = e.measure().apply(Minecraft.getInstance());
					if (sz[0] > 0 && sz[1] > 0) {
						out.add(e);
					}
				} catch (Throwable ignored) {
				}
			}
		}
		return out;
	}

	/** The live HUD element, drawn at a readable size in a 128-tall strip. */
	private void drawPreview(OriginDeck d, List<HudElements.Element> els, float y) {
		d.rect(M, y, CW, 128, 8, 0x59000000, LINE);
		Minecraft mc = Minecraft.getInstance();
		int totalW = 0, maxH = 0;
		List<int[]> sizes = new ArrayList<>();
		for (HudElements.Element e : els) {
			int[] sz;
			try {
				sz = e.measure().apply(mc);
			} catch (Throwable t) {
				sz = new int[]{0, 0};
			}
			sizes.add(sz);
			totalW += sz[0];
			maxH = Math.max(maxH, Math.max(1, sz[1]));
		}
		float gapGui = 12;
		totalW += (int) (gapGui * (els.size() - 1));
		// k = element GUI px -> physical px; fit the strip with room for the button
		float physPerGui = (float) d.gs;
		float k = Math.min(2.0f * physPerGui, Math.min(80 * d.s / maxH, (CW - 64) * d.s / Math.max(1, totalW)));
		float startX = d.ox + (M + CW / 2f) * d.s - totalW * k / 2f;
		float midY = d.oy + (y + 64) * d.s;
		d.clip(M, y, CW, 128);
		var pose = d.g.pose();
		float cx = startX;
		for (int i = 0; i < els.size(); i++) {
			int[] sz = sizes.get(i);
			pose.pushPose();
			pose.translate(cx, midY - sz[1] * k / 2f, 0);
			pose.scale(k, k, 1f);
			try {
				els.get(i).renderer().render(d.g, mc, sz[0], sz[1]);
			} catch (Throwable ignored) {
				// a preview must never take the menu down
			}
			pose.popPose();
			cx += (sz[0] + gapGui) * k;
		}
		d.unclip();

		String l = "EDIT POSITION";
		float bw = 14 + 14 + 8 + d.textW(l, 10.5f, true, CAPS_TRACK) + 14;
		float bx = M + CW - 10 - bw, by = y + 128 - 10 - 30;
		boolean hov = d.over(bx, by, bw, 30);
		d.rect(bx, by, bw, 30, 6, hov ? 0xF0232A26 : 0xD10E1110, LINE2);
		d.icon("@hudeditor", bx + 14, by + 8, 14, TEXT);
		d.textMid(l, bx + 36, by + 15, 10.5f, true, CAPS_TRACK, TEXT);
		d.hit(bx, by, bw, 30, () -> Minecraft.getInstance().setScreen(new HudEditorScreen(false, this)));
	}

	// ---- the two-column option grid (mod page + Settings tab) ----

	/** One section: an optional header + its visible rows. */
	private record Section(String header, List<ModOption> rows) {
	}

	private List<Section> sections(String id, List<ModOption> opts, String fallbackHeader) {
		List<Section> out = new ArrayList<>();
		String q = optSearch.toLowerCase(Locale.ROOT).trim();
		Section cur = new Section(fallbackHeader, new ArrayList<>());
		for (ModOption o : opts) {
			if (o.kind == ModOption.Kind.HEADER) {
				if (!q.isEmpty()) {
					continue;     // search results read as one flat list
				}
				if (!cur.rows().isEmpty()) {
					out.add(cur);
				}
				cur = new Section(o.label, new ArrayList<>());
				continue;
			}
			if (o.dependsOn != null && !vBool(id, o.dependsOn)) {
				continue;
			}
			if (!q.isEmpty() && !o.label.toLowerCase(Locale.ROOT).contains(q)) {
				continue;
			}
			cur.rows().add(o);
		}
		out.add(cur);
		out.removeIf(sec -> sec.rows().isEmpty());
		return out;
	}

	/** Draws the sections from `y`; returns the y below the last row. */
	private float drawOptions(OriginDeck d, String id, List<ModOption> opts, float y) {
		return drawOptions(d, id, opts, y, null);
	}

	private float drawOptions(OriginDeck d, String id, List<ModOption> opts, float y, String fallbackHeader) {
		float colW = (CW - 48) / 2f;
		List<Section> secs = sections(id, opts, fallbackHeader);
		if (secs.isEmpty()) {
			y += 24;
			d.textMid("No settings match \"" + optSearch + "\"", M, y + 8, 13, false, 0, MUTE);
			return y + 16;
		}
		for (Section sec : secs) {
			if (sec.header() != null) {
				y += 24;
				d.textMid(sec.header().toUpperCase(Locale.ROOT), M, y + 8, 10, true, CAPS_TRACK, MUTE);
				y += 16 + 8;
			} else {
				y += 16;
			}
			List<ModOption> rows = sec.rows();
			for (int i = 0; i < rows.size(); i++) {
				float rx = M + (i % 2) * (colW + 48);
				float ry = y + (i / 2) * OPT_H;
				drawOption(d, id, rows.get(i), rx, ry, colW);
			}
			y += ((rows.size() + 1) / 2) * OPT_H;
		}
		return y;
	}

	private void drawOption(OriginDeck d, String id, ModOption o, float x, float y, float w) {
		boolean hov = d.over(x - 12, y, w + 24, OPT_H);
		if (hov) {
			d.rect(x - 12, y, w + 24, OPT_H, 6, 0x0AFFFFFF);
			if (o.tooltip != null) {
				tip = o.tooltip;
			}
		}
		float cy = y + OPT_H / 2;
		float indent = o.dependsOn != null ? 14 : 0;
		if (indent > 0) {
			d.fill(x + 3, cy - 7, 1, 14, LINE2);
		}
		float right = x + w;
		float left = drawControl(d, id, o, right, cy, x, y, w);
		boolean changed = changed(id, o);
		float labelMax = left - (changed ? 24 : 0) - 16 - x - indent;
		d.textMid(d.fit(o.label, labelMax, 14, false, 0), x + indent, cy, 14, false, 0, TEXT);
		if (changed) {
			float rx = left - 10 - 14;
			boolean rh = d.over(rx - 4, cy - 11, 22, 22);
			d.icon("@reset", rx, cy - 7, 14, rh ? TEXT : MUTE);
			d.hit(rx - 4, cy - 11, 22, 22, () -> {
				Mods.reset(id, o.key);
				if ("timechanger".equals(id) && "time".equals(o.key)) {
					com.origin.client.client.OriginClientMod.previewTimeSlider(Mods.num(id, o.key));
				}
			});
		}
	}

	/** Draws the row's control right-aligned to `right`; returns its left edge. */
	private float drawControl(OriginDeck d, String id, ModOption o, float right, float cy, float rowX, float rowY, float rowW) {
		switch (o.kind) {
			case TOGGLE -> {
				boolean v = vBool(id, o.key);
				d.toggle(id + ":" + o.key, right - 36, cy - 10, v);
				// the whole row flips a toggle
				d.hit(rowX - 12, rowY, rowW + 24, OPT_H, () -> flipToggle(id, o));
				return right - 36;
			}
			case SLIDER -> {
				double v = vNum(id, o.key);
				boolean pctOfFraction = o.format.contains("%%") && o.max <= 1.0;
				String val = pctOfFraction ? String.format(o.format, v * 100) : String.format(o.format, v);
				d.textRight(val, right, cy, 13, false, 0, DIM);
				float valCol = Math.max(40, d.textW(val, 13, false, 0));
				float tx = right - valCol - 12 - 110;
				boolean active = id.equals(dragMod) && o.key.equals(dragKey);
				d.slider(tx, cy, 110, (float) ((v - o.min) / (o.max - o.min)), active);
				d.hit(tx - 8, cy - 12, 126, 24, () -> {
					dragMod = id;
					dragKey = o.key;
					dragOpt = o;
					dragX0 = tx;
					dragX1 = tx + 110;
				});
				return tx - 8;
			}
			case COLOR -> {
				int cur = Mods.color(id, o.key);
				d.rect(right - 20, cy - 10, 20, 20, 4, cur | 0xFF000000, LINE2);
				d.hit(rowX - 12, rowY, rowW + 24, OPT_H, () -> {
					String k = id + ":" + o.key;
					if (OriginColorPicker.isOpen() && k.equals(OriginColorPicker.openKey())) {
						OriginColorPicker.close();
					} else {
						OriginColorPicker.open(id, o.key, o.label, d.guiX(rowX), d.guiY(rowY + OPT_H));
					}
				});
				return right - 20;
			}
			case KEYBIND -> {
				boolean capturing = id.equals(capMod) && o.key.equals(capKey);
				String name = capturing ? "Press a key" : keyName(Mods.keyCode(id, o.key));
				float bw = Math.max(56, d.textW(name, 13, false, 0) + 20);
				d.rect(right - bw, cy - 13, bw, 26, 6, 0x0DFFFFFF, capturing ? ACC : LINE2);
				d.textCenter(name, right - bw / 2, cy, 13, false, 0, capturing ? ACC : TEXT);
				d.hit(right - bw, cy - 13, bw, 26, () -> {
					capMod = id;
					capKey = o.key;
				});
				return right - bw;
			}
			case DROPDOWN -> {
				String v = vMode(id, o.key);
				float vw = d.textW(v, 13, false, 0);
				d.icon("@chev-right", right - 12, cy - 6, 12, MUTE);
				d.textMid(v, right - 12 - 10 - vw, cy, 13, false, 0, TEXT);
				float left = right - 12 - 10 - vw - 10 - 12;
				d.icon("@chev-left", left, cy - 6, 12, MUTE);
				float mid = left + (right - left) / 2;
				d.hit(left - 6, cy - 13, mid - left + 6, 26, () -> cycle(id, o, -1));
				d.hit(mid, cy - 13, right - mid + 6, 26, () -> cycle(id, o, 1));
				return left;
			}
			case MULTISELECT -> {
				String csv = vMulti(id, o.key);
				List<String> sel = splitCsv(csv);
				String summary = sel.isEmpty() ? "None" : isItemPicker(id, o.key) ? itemPickerSummary(csv)
						: String.join(", ", sel.stream().map(JeiSettings::prettify).toList());
				float bw = Math.min(200, rowW / 2);
				d.rect(right - bw, cy - 13, bw, 26, 6, 0x0DFFFFFF, LINE2);
				d.textMid(d.fit(summary, bw - 40, 13, false, 0), right - bw + 10, cy, 13, false, 0, TEXT);
				d.icon("@chev-down", right - 22, cy - 6, 12, MUTE);
				d.hit(right - bw, cy - 13, bw, 26, () -> {
					if (isItemPicker(id, o.key)) {
						Minecraft.getInstance().setScreen(new ItemCounterPickerScreen());
					} else {
						OriginMultiSelect.open(o.label, java.util.Arrays.asList(o.modes), sel,
								chosen -> vSetMulti(id, o.key, String.join(", ", chosen)));
					}
				});
				return right - bw;
			}
			case TEXT -> {
				boolean editing = id.equals(textMod) && o.key.equals(textKey);
				String v = editing ? textBuf : Mods.text(id, o.key);
				float bw = Math.min(200, rowW / 2);
				d.rect(right - bw, cy - 13, bw, 26, 6, FIELD, editing ? mul(ACC, 0.6f) : LINE2);
				if (v.isEmpty() && !editing) {
					d.textMid(o.secret ? "Not set" : "Click to type", right - bw + 10, cy, 13, false, 0, MUTE);
				} else {
					String shown = o.secret ? maskSecret(v) : v;
					shown = editing ? fitTail(d, shown, bw - 24) : d.fit(shown, bw - 20, 13, false, 0);
					float w = d.textMid(shown, right - bw + 10, cy, 13, false, 0, TEXT);
					if (editing) {
						caret(d, right - bw + 11 + w, cy - 7, 14);
					}
				}
				d.hit(right - bw, cy - 13, bw, 26, () -> {
					textMod = id;
					textKey = o.key;
					textBuf = Mods.text(id, o.key);
				});
				return right - bw;
			}
			default -> {
				return right;
			}
		}
	}

	private void flipToggle(String id, ModOption o) {
		vSetBool(id, o.key, !vBool(id, o.key));
		if (Mods.PERFORMANCE_ID.equals(id) && "shaderPerformanceMode".equals(o.key)) {
			com.origin.client.client.shaders.IrisBridge.reloadIfPackActive();
		}
		if ("fullbright".equals(id) && "fullBright".equals(o.key) && Mods.bool(id, o.key)
				&& com.origin.client.client.shaders.IrisBridge.currentPack() != null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal(
						"Full Bright needs shaders OFF — your shaderpack does its own lighting."), false);
			}
		}
	}

	private void cycle(String id, ModOption o, int dir) {
		String cur = vMode(id, o.key);
		int idx = 0;
		for (int i = 0; i < o.modes.length; i++) {
			if (o.modes[i].equals(cur)) {
				idx = i;
			}
		}
		vSetMode(id, o.key, o.modes[(idx + dir + o.modes.length) % o.modes.length]);
	}

	// ---- the Settings tab ----

	private void drawSettingsTab(OriginDeck d) {
		float x = M;
		for (SubTab st : SubTab.values()) {
			String label = st.label.toUpperCase(Locale.ROOT);
			float w = 24 + d.textW(label, 11, true, CAPS_TRACK);
			boolean on = subTab == st;
			boolean hov = d.over(x, BAR_Y, w, 32);
			float hp = OriginUi.hover("deck-sub:" + st, hov);
			d.rect(x, BAR_Y, w, 32, 6, on ? ACC_SOFT : lerp(0x0AFFFFFF, 0x14FFFFFF, hp));
			d.textMid(label, x + 12, BAR_Y + 16, 11, true, CAPS_TRACK, on ? ACC : lerp(DIM, TEXT, hp));
			d.hit(x, BAR_Y, w, 32, () -> {
				subTab = st;
				optSearch = "";
				pageScroll = pageScrollTarget = 0;
			});
			x += w + 8;
		}
		drawSearch(d, W - M - SEARCH_W, BAR_Y + 1, SEARCH_W, optSearch, "Search settings", !optSearch.isEmpty(), false, () -> {
		});

		float top = BAR_Y + 32 + 8;
		d.clip(0, top, W, BOTTOM - top);
		float y = BAR_Y + 32 - (float) pageScroll;
		if (subTab == SubTab.MENU) {
			y += 24;
			d.textMid("MENU", M, y + 8, 10, true, CAPS_TRACK, MUTE);
			y += 24;
			float colW = (CW - 48) / 2f;
			boolean solid = Mods.metaNum("menuBgOpacity", 1.0) > 0.5;
			boolean hov = d.over(M - 12, y, colW + 24, OPT_H);
			if (hov) {
				d.rect(M - 12, y, colW + 24, OPT_H, 6, 0x0AFFFFFF);
				tip = "On: a solid panel behind the menu. Off: see-through.";
			}
			d.textMid("Solid Menu Background", M, y + 20, 14, false, 0, TEXT);
			d.toggle("@bgop", M + colW - 36, y + 10, solid);
			d.hit(M - 12, y, colW + 24, OPT_H, () -> Mods.setMetaNum("menuBgOpacity", solid ? 0.0 : 1.0));
			y += OPT_H;
		} else {
			String sid = subTab == SubTab.GENERAL ? Mods.GENERAL_ID : Mods.PERFORMANCE_ID;
			List<ModOption> opts = subTab == SubTab.GENERAL ? Mods.GENERAL_SETTINGS : Mods.PERFORMANCE_SETTINGS;
			y = drawOptions(d, sid, opts, y, subTab.label);
		}
		d.unclip();
		float contentH = y + (float) pageScroll - (BAR_Y + 32) + 16;
		maxPageScroll = Math.max(0, contentH - (BOTTOM - top));
		pageScrollTarget = Math.min(pageScrollTarget, maxPageScroll);
		drawScrollbar(d, pageScroll, maxPageScroll, top, BOTTOM);
	}

	// ---- profiles dropdown + confirm + toast ----

	private void drawProfiles(OriginDeck d) {
		// a click anywhere outside closes it
		d.hit(-10000, -10000, 20000, 20000, () -> {
			profOpen = false;
			profileFocused = false;
		});
		List<String> names = Profiles.names();
		float x = M, y = BAR_Y + 32 + 6, w = 264;
		float listH = names.isEmpty() ? 34 : names.size() * 34;
		float h = 12 + 16 + 8 + listH + 8 + 1 + 8 + 30 + 10;
		d.rect(x - 4, y + 4, w + 8, h + 8, 12, 0x33000000);
		d.rect(x, y, w, h, 8, 0xFA181D1A, LINE2);
		d.hit(x, y, w, h, () -> profileFocused = false);
		d.textMid("PROFILES", x + 14, y + 20, 10, true, CAPS_TRACK, MUTE);
		float ry = y + 36;
		String active = activeProfile();
		if (names.isEmpty()) {
			d.textMid("No saved profiles yet", x + 14, ry + 17, 13, false, 0, MUTE);
		}
		for (String nm : names) {
			boolean hov = d.over(x + 6, ry, w - 12, 34);
			if (hov) {
				d.rect(x + 6, ry, w - 12, 34, 6, 0x0DFFFFFF);
			}
			if (nm.equals(active)) {
				d.icon("@check", x + 14, ry + 10, 14, ACC);
			}
			d.textMid(d.fit(nm, w - 80, 13, false, 0), x + 36, ry + 17, 13, false, 0, TEXT);
			d.hit(x + 6, ry, w - 12, 34, () -> {
				if (Profiles.apply(nm)) {
					Mods.setMetaText("activeProfile", nm);
					toast("Profile loaded: " + nm);
				}
				profOpen = false;
			});
			float tx = x + w - 14 - 14;
			boolean th = d.over(tx - 5, ry + 5, 24, 24);
			if (hov) {
				d.icon("@trash", tx, ry + 10, 14, th ? RED : MUTE);
			}
			d.hit(tx - 5, ry + 5, 24, 24, () -> confirmDelete = nm);
			ry += 34;
		}
		if (names.isEmpty()) {
			ry += 34;
		}
		ry += 8;
		d.fill(x + 1, ry, w - 2, 1, LINE);
		ry += 9;
		float fw = w - 16 - 38;
		d.rect(x + 8, ry, fw, 30, 6, FIELD, profileFocused ? mul(ACC, 0.6f) : LINE2);
		if (profileInput.isEmpty()) {
			d.textMid("Save current as…", x + 18, ry + 15, 13, false, 0, MUTE);
			if (profileFocused) {
				caret(d, x + 18, ry + 8, 14);
			}
		} else {
			String shown = fitTail(d, profileInput, fw - 20);
			float tw = d.textMid(shown, x + 18, ry + 15, 13, false, 0, TEXT);
			if (profileFocused) {
				caret(d, x + 19 + tw, ry + 8, 14);
			}
		}
		final float fy = ry;
		d.hit(x + 8, ry, fw, 30, () -> profileFocused = true);
		boolean can = !profileInput.trim().isEmpty();
		boolean ph = can && d.over(x + w - 8 - 30, ry, 30, 30);
		d.rect(x + w - 8 - 30, ry, 30, 30, 6, can ? (ph ? 0xFF83E2B5 : ACC) : 0x14FFFFFF);
		d.icon("@plus", x + w - 8 - 30 + 8, fy + 8, 14, can ? ON_ACC : MUTE);
		d.hit(x + w - 8 - 30, ry, 30, 30, this::saveProfile);
	}

	private void saveProfile() {
		String nm = profileInput.trim();
		if (nm.isEmpty()) {
			return;
		}
		Profiles.save(nm);
		Mods.setMetaText("activeProfile", nm);
		profileInput = "";
		toast("Profile saved: " + nm);
	}

	private void drawConfirm(OriginDeck d) {
		d.hit(-10000, -10000, 20000, 20000, () -> confirmDelete = null);
		d.rect(0, 0, W, H, 8, 0x8C000000);
		float w = 340, h = 148, x = (W - w) / 2, y = (H - h) / 2;
		d.rect(x, y, w, h, 8, 0xFA181D1A, LINE2);
		d.hit(x, y, w, h, () -> {
		});
		d.textMid("Delete this profile?", x + 20, y + 30, 15, true, 0, TEXT);
		d.textMid(d.fit("\"" + confirmDelete + "\" will be removed for good.", w - 40, 13, false, 0),
				x + 20, y + 56, 13, false, 0, DIM);
		float bw = (w - 40 - 12) / 2, by = y + h - 20 - 34;
		boolean ch = d.over(x + 20, by, bw, 34), dh = d.over(x + 20 + bw + 12, by, bw, 34);
		d.rect(x + 20, by, bw, 34, 6, ch ? 0x1AFFFFFF : 0x0FFFFFFF);
		d.textCenter("CANCEL", x + 20 + bw / 2, by + 17, 11, true, CAPS_TRACK, TEXT);
		d.hit(x + 20, by, bw, 34, () -> confirmDelete = null);
		d.rect(x + 20 + bw + 12, by, bw, 34, 6, dh ? 0x47D9675A : RED_SOFT);
		d.textCenter("DELETE", x + 20 + bw + 12 + bw / 2, by + 17, 11, true, CAPS_TRACK, RED);
		d.hit(x + 20 + bw + 12, by, bw, 34, () -> {
			if (confirmDelete.equals(Mods.metaText("activeProfile", ""))) {
				Mods.setMetaText("activeProfile", null);
			}
			Profiles.delete(confirmDelete);
			confirmDelete = null;
		});
	}

	private void drawToast(OriginDeck d, long now) {
		if (toastAt < 0) {
			return;
		}
		long dt = now - toastAt, life = 1400;
		if (dt >= life) {
			toastAt = -1;
			return;
		}
		float a = dt < 120 ? dt / 120f : dt > life - 300 ? (life - dt) / 300f : 1f;
		float base = d.alpha;
		d.alpha = base * a;
		float w = 14 + 14 + 8 + d.textW(toastText, 13, false, 0) + 16;
		float x = (W - w) / 2, y = H - 24 - 36;
		d.rect(x, y, w, 36, 8, 0xFA181D1A, LINE2);
		d.icon("@check", x + 14, y + 11, 14, ACC);
		d.textMid(toastText, x + 36, y + 18, 13, false, 0, TEXT);
		d.alpha = base;
	}

	private void drawTip(OriginDeck d, String text, int winW, int winH) {
		List<String> lines = d.wrap(text, 260, 12, false);
		float tw = 0;
		for (String l : lines) {
			tw = Math.max(tw, d.textW(l, 12, false, 0));
		}
		float w = tw + 20, h = lines.size() * 17 + 14;
		float x = d.mx + 14, y = d.my + 18;
		float maxX = (winW - d.ox) / d.s - 8, maxY = (winH - d.oy) / d.s - 8;
		if (x + w > maxX) {
			x = d.mx - 14 - w;
		}
		if (y + h > maxY) {
			y = d.my - 10 - h;
		}
		d.rect(x, y, w, h, 6, 0xF50E1110, LINE2);
		float ly = y + 7;
		for (String l : lines) {
			d.textMid(l, x + 10, ly + 8.5f, 12, false, 0, DIM);
			ly += 17;
		}
	}

	// ---- input ----

	private float dX(double gui) {
		return OriginDeck.toDesign(gui, Minecraft.getInstance().getWindow().getGuiScale(), lastOx, lastS);
	}

	private float dY(double gui) {
		return OriginDeck.toDesign(gui, Minecraft.getInstance().getWindow().getGuiScale(), lastOy, lastS);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (OriginMultiSelect.isOpen()) {
			return OriginMultiSelect.mouseClicked(mx, my, button);
		}
		if (OriginColorPicker.isOpen()) {
			return OriginColorPicker.mouseClicked(mx, my, button);
		}
		commitText();     // any click ends typing (clicking the field re-opens it)
		capMod = null;    // and any click abandons a half-done key capture
		capKey = null;
		if (closingAt > 0) {
			return true;
		}
		if (button != 0) {
			return super.mouseClicked(mx, my, button);
		}
		searchFocused = false;   // the search box's own hit turns it back on
		OriginDeck.click(hits, dX(mx), dY(my));
		if (dragOpt != null) {     // a press on a slider track jumps the thumb there
			applySlider(mx);
		}
		return true;
	}

	private void applySlider(double guiMx) {
		float x = dX(guiMx);
		double t = Math.max(0, Math.min(1, (x - dragX0) / (dragX1 - dragX0)));
		double v = dragOpt.min + t * (dragOpt.max - dragOpt.min);
		vDragNum(dragMod, dragOpt.key, Math.round(v / dragOpt.step) * dragOpt.step);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (OriginColorPicker.mouseDragged(mx, my, button)) {
			return true;
		}
		if (dragOpt != null) {
			applySlider(mx);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (OriginColorPicker.mouseReleased()) {
			return true;
		}
		dragMod = null;
		dragKey = null;
		dragOpt = null;
		flushSlider();
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (OriginColorPicker.isOpen() || OriginMultiSelect.isOpen() || profOpen || confirmDelete != null) {
			return true;
		}
		if (page != null || tab == Tab.SETTINGS) {
			pageScrollTarget = Math.max(0, Math.min(maxPageScroll, pageScrollTarget - amount * 48));
		} else {
			scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - amount * 48));
		}
		return true;
	}

	/** Esc / Back: exactly one level up. */
	private void back() {
		if (page != null) {
			commitText();
			page = null;
			pageChangedAt = System.currentTimeMillis();
			optSearch = "";
			pageScroll = pageScrollTarget = 0;
		} else {
			beginClose();
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (OriginMultiSelect.isOpen()) {
			return OriginMultiSelect.keyPressed(keyCode);
		}
		if (OriginColorPicker.isOpen()) {
			return OriginColorPicker.keyPressed(keyCode);
		}
		if (textMod != null) {
			return textKeyPressed(keyCode);
		}
		if (capMod != null) {
			Mods.set(capMod, capKey, keyCode == GLFW.GLFW_KEY_ESCAPE ? -1 : keyCode);
			capMod = null;
			capKey = null;
			return true;
		}
		boolean esc = keyCode == GLFW.GLFW_KEY_ESCAPE;
		if (confirmDelete != null) {
			if (esc) {
				confirmDelete = null;
			}
			return true;
		}
		if (profOpen) {
			if (esc) {
				profOpen = false;
				profileFocused = false;
			} else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				saveProfile();
			} else if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !profileInput.isEmpty()) {
				profileInput = hasControlDown() ? "" : profileInput.substring(0, profileInput.length() - 1);
			}
			return true;
		}
		if (esc) {
			if (searchFocused) {
				searchFocused = false;
			} else {
				back();
			}
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			beginClose();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_F && hasControlDown() && page == null && tab == Tab.MODS) {
			searchFocused = true;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
			if (page == null && tab == Tab.MODS && !search.isEmpty()) {
				search = hasControlDown() ? "" : search.substring(0, search.length() - 1);
				scrollTarget = 0;
				return true;
			}
			if ((page != null || tab == Tab.SETTINGS) && !optSearch.isEmpty()) {
				optSearch = hasControlDown() ? "" : optSearch.substring(0, optSearch.length() - 1);
				pageScrollTarget = 0;
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (capMod != null) {
			return true;
		}
		if (chr < 32 || chr == 127) {
			return super.charTyped(chr, modifiers);
		}
		if (textMod != null) {
			if (textBuf.length() < TEXT_MAX) {
				textBuf += chr;
			}
			return true;
		}
		if (profOpen) {
			if (profileInput.length() < 28) {
				profileInput += chr;
				profileFocused = true;
			}
			return true;
		}
		if (page != null || tab == Tab.SETTINGS) {
			if (optSearch.length() < 24) {
				optSearch += chr;
				pageScrollTarget = 0;
			}
			return true;
		}
		// Library: typing anywhere searches; "/" just focuses the box (the hint on it).
		if (chr == '/' && !searchFocused) {
			searchFocused = true;
			return true;
		}
		if (search.length() < 24) {
			search += chr;
			searchFocused = true;
			scrollTarget = 0;
		}
		return true;
	}

	// ---- TEXT rows (free-text fields, e.g. the Hypixel API key) ----

	// A secret is never drawn in clear: stars, plus the last 4 characters once
	// it's long enough that those alone can't give it away.
	private static String maskSecret(String v) {
		if (v.length() <= 8) {
			return "*".repeat(v.length());
		}
		return "*".repeat(Math.min(16, v.length() - 4)) + v.substring(v.length() - 4);
	}

	private boolean textKeyPressed(int keyCode) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			textMod = null;      // cancel: the saved value stays as it was
			textKey = null;
			textBuf = "";
		} else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			commitText();
		} else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
			if (!textBuf.isEmpty()) {
				textBuf = hasControlDown() ? "" : textBuf.substring(0, textBuf.length() - 1);
			}
		} else if (isPaste(keyCode)) {
			String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
			if (clip != null) {
				// one line only; control characters (newlines from a copied block) dropped
				String clean = clip.replaceAll("[\\p{Cntrl}]", "").trim();
				textBuf = (textBuf + clean).substring(0, Math.min(TEXT_MAX, textBuf.length() + clean.length()));
			}
		}
		return true;   // every other key is swallowed while a field has focus
	}

	private void commitText() {
		if (textMod == null) {
			return;
		}
		String mod = textMod, key = textKey, v = textBuf.trim();
		textMod = null;
		textKey = null;
		textBuf = "";
		if (!v.equals(Mods.text(mod, key))) {
			Mods.set(mod, key, v);
		}
	}

	private static String keyName(int code) {
		if (code < 0) {
			return "None";
		}
		try {
			return InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
		} catch (Throwable t) {
			return "Key " + code;
		}
	}
}
