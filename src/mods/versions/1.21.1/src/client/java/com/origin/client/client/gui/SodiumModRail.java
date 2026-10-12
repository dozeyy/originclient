package com.origin.client.client.gui;

import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The Video Settings MOD RAIL for 1.21.1: a left-hand Origin-styled rail inside
 * Sodium's video settings screen that lists Sodium's own pages AND the mods
 * Origin bundles, so every mod's settings are one click from Video Settings.
 *
 * <p>Why this exists as a GUI mixin (mandate 5, per-version best fit): on the
 * Sodium 0.8 versions the same rail comes from Sodium's own Config API. 1.21.1
 * ships stable Sodium 0.6.13, which has no Config API (and its 0.8 is a
 * shader-breaking alpha), so the rail is built here on top of Sodium 0.6.13's
 * screen ({@code SodiumOptionsGUI}) by {@code SodiumOptionsRailMixin}: the mixin
 * replaces Sodium's top row of page tabs with this widget and slides Sodium's
 * option column right to clear it. Every Sodium member used here was checked
 * against Sodium's source at tag {@code mc1.21.1-0.6.13}.
 *
 * <p>Rows, top to bottom:
 * <ul>
 *   <li>VIDEO -- Sodium's pages (General / Quality / Performance / Advanced);
 *       the open page is the lit row. Any other mod's extra Sodium tab lands
 *       here too.</li>
 *   <li>MODS -- Origin Mods (the Right Shift menu, Close returns here), Iris
 *       Shaders (shader packs), Voice Chat, JEI (Origin's JEI page), Mod Menu
 *       (the full installed-mods list).</li>
 *   <li>OPTIMIZATION -- the bundled performance mods. A mod with its own
 *       settings screen opens it; one without is listed dimmed, with a hover
 *       note that it works automatically.</li>
 * </ul>
 * A mod that isn't installed simply has no row.
 *
 * <p>FAIL-SOFT, never broken (mandate 4): {@link #tryCreate} returns null --
 * leaving Sodium's own tabs in place -- when the screen is too narrow, when
 * Sodium's private fields don't match, or on any error. If the rail is up but
 * the option column did not move (the shift injector failed to bind), the
 * mixin calls {@link #fallBackToSodiumTabs} and the rail is switched off for
 * the session. Opening a mod's screen is guarded so a broken third-party
 * config screen can't take the game down.
 */
public final class SodiumModRail extends AbstractWidget {
	// Gap to the screen edge AND between the rail and Sodium's option column.
	private static final int MARGIN = 6;
	private static final int ROW_H = 18, ROW_STEP = 20;
	private static final int SECTION_H = 14, SECTION_GAP = 6;
	/** Below this screen width the rail would crush Sodium's own 240 px option
	 *  column + its hover tooltip, so Sodium keeps its own top tabs instead.
	 *  400 keeps the rail on Minecraft's default 854x480 window (GUI-scaled
	 *  width 427), where Sodium's tooltip narrows to ~70 px and wraps. */
	private static final int MIN_SCREEN_W = 400;

	// Sodium 0.6.13 has no getter for its page list or open page; both are
	// private fields (SodiumOptionsGUI.pages / .currentPage at the pinned tag).
	// Resolved once; a mismatch disables the rail for the session.
	private static Field pagesField, currentPageField;
	private static boolean disabled = false;
	// Kept across Sodium's per-page rebuilds (each tab click builds a new rail).
	private static double scroll = 0;

	// Bundled performance mods, in display order: {mod id, label}.
	private static final String[][] OPTIMIZERS = {
			{"modernfix", "ModernFix"},
			{"immediatelyfast", "ImmediatelyFast"},
			{"lithium", "Lithium"},
			{"ferritecore", "FerriteCore"},
			{"indium", "Indium"},
	};

	private enum Kind {SECTION, PAGE, LINK, AUTO}

	/** One rail row. `action` is null for SECTION and AUTO rows (not clickable). */
	private record Row(Kind kind, String label, @Nullable Runnable action, boolean active) {
	}

	private final Screen screen;
	private final List<Row> rows;
	// Row geometry, laid out once: y of each row relative to the list top,
	// BEFORE scroll. Render and click both read these -- one geometry.
	private final int[] rowY;
	private final int contentH;
	private final String pageTitle;

	private SodiumModRail(Screen screen, List<Row> rows, String pageTitle) {
		super(MARGIN, MARGIN, railWidth(screen.width), screen.height - MARGIN * 2,
				Component.literal("Origin mod rail"));
		this.screen = screen;
		this.rows = rows;
		this.pageTitle = pageTitle;
		this.rowY = new int[rows.size()];
		int y = 0;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			if (r.kind() == Kind.SECTION) {
				if (i > 0) {
					y += SECTION_GAP;
				}
				rowY[i] = y;
				y += SECTION_H;
			} else {
				rowY[i] = y;
				y += ROW_STEP;
			}
		}
		this.contentH = y;
		scroll = clampScroll(scroll);
	}

	// ---- geometry shared with the mixin ----

	/** Rail width for a screen width: wide enough for "ImmediatelyFast". */
	public static int railWidth(int screenW) {
		return Math.max(96, Math.min(120, screenW / 5));
	}

	/** How far Sodium's option column moves right to clear the rail (Sodium
	 *  draws it at x = 6; it lands one MARGIN right of the rail). */
	public static int contentShift(int screenW) {
		return railWidth(screenW) + MARGIN;
	}

	private int headerH() {
		return getHeight() >= 200 ? 56 : 22;
	}

	private int listTop() {
		return getY() + headerH();
	}

	private int listBottom() {
		return getY() + getHeight() - 6;
	}

	private double maxScroll() {
		return Math.max(0, contentH - (listBottom() - listTop()));
	}

	private double clampScroll(double s) {
		return Math.max(0, Math.min(maxScroll(), s));
	}

	// ---- creation (fail-soft) ----

	/**
	 * Builds the rail for Sodium's screen, or returns null to keep Sodium's own
	 * tabs: rail switched off, screen too narrow, not Sodium's screen, or
	 * Sodium's internals don't match the pinned 0.6.13 layout.
	 */
	@Nullable
	public static SodiumModRail tryCreate(Screen screen) {
		if (disabled || screen.width < MIN_SCREEN_W || !(screen instanceof SodiumOptionsGUI sodium)) {
			return null;
		}
		try {
			List<OptionPage> pages = pages(sodium);
			OptionPage current = currentPage(sodium);
			if (pages == null || pages.isEmpty()) {
				return null;
			}
			String title = current != null ? current.getName().getString() : "";
			return new SodiumModRail(screen, buildRows(sodium, pages, current), title);
		} catch (Throwable t) {
			disable("could not read Sodium's pages", t);
			return null;
		}
	}

	/** True when Sodium's open page has options of its own -- i.e. the option
	 *  column SHOULD have been shifted while the rail was up. */
	public static boolean currentPageHasOptions(Screen screen) {
		try {
			if (screen instanceof SodiumOptionsGUI sodium) {
				OptionPage current = currentPage(sodium);
				return current != null && !current.getOptions().isEmpty();
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	/** The option column didn't move, so the rail would cover it: switch the
	 *  rail off for the session and rebuild Sodium's screen with its own tabs. */
	public static void fallBackToSodiumTabs(Screen screen) {
		disable("Sodium's option column could not be moved (shift injector did not bind)", null);
		try {
			if (screen instanceof SodiumOptionsGUI sodium) {
				OptionPage current = currentPage(sodium);
				if (current != null) {
					sodium.setPage(current);   // public: rebuilds the GUI, now without the rail
				}
			}
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin: could not rebuild Sodium's video settings after disabling the mod rail", t);
		}
	}

	private static void disable(String why, @Nullable Throwable t) {
		if (!disabled) {
			disabled = true;
			if (t != null) {
				OriginClient.LOGGER.warn("Origin: Video Settings mod rail off ({}); Sodium's own tabs stay", why, t);
			} else {
				OriginClient.LOGGER.warn("Origin: Video Settings mod rail off ({}); Sodium's own tabs stay", why);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<OptionPage> pages(SodiumOptionsGUI sodium) throws ReflectiveOperationException {
		if (pagesField == null) {
			Field f = SodiumOptionsGUI.class.getDeclaredField("pages");
			f.setAccessible(true);
			pagesField = f;
		}
		return (List<OptionPage>) pagesField.get(sodium);
	}

	private static OptionPage currentPage(SodiumOptionsGUI sodium) throws ReflectiveOperationException {
		if (currentPageField == null) {
			Field f = SodiumOptionsGUI.class.getDeclaredField("currentPage");
			f.setAccessible(true);
			currentPageField = f;
		}
		return (OptionPage) currentPageField.get(sodium);
	}

	// ---- the row list ----

	private static List<Row> buildRows(SodiumOptionsGUI sodium, List<OptionPage> pages, @Nullable OptionPage current) {
		List<Row> rows = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();

		// VIDEO: Sodium's pages. Iris 1.8.x appends an EMPTY "Shader Packs..."
		// page whose setPage Iris intercepts to open its own screen -- that one
		// is shown under MODS as "Iris Shaders" instead. Any other empty page is
		// another mod's redirect tab: listed here, styled as a link.
		OptionPage irisPage = null;
		rows.add(section("VIDEO"));
		for (OptionPage p : pages) {
			if (isIrisShaderPage(p)) {
				irisPage = p;
				continue;
			}
			boolean redirect = p.getGroups().isEmpty();
			rows.add(new Row(redirect ? Kind.LINK : Kind.PAGE, p.getName().getString(),
					() -> sodium.setPage(p), p == current));
		}

		rows.add(section("MODS"));
		rows.add(link("Origin Mods", () -> mc.setScreen(new OriginModMenuScreen(sodium, null))));
		if (irisPage != null) {
			OptionPage iris = irisPage;
			rows.add(link("Iris Shaders", () -> sodium.setPage(iris)));
		} else {
			addModRow(rows, sodium, "iris", "Iris Shaders");
		}
		addModRow(rows, sodium, "voicechat", "Voice Chat");
		if (loaded("jei") && Mods.byId("jei") != null) {
			// JEI's settings live in Origin's menu (JeiSettings), in the Origin look.
			rows.add(link("JEI", () -> mc.setScreen(new OriginModMenuScreen(sodium, "jei"))));
		} else {
			addModRow(rows, sodium, "jei", "JEI");
		}
		if (loaded("modmenu")) {
			rows.add(link("Mod Menu", () -> ModMenuLink.openModList(sodium)));
		}

		rows.add(section("OPTIMIZATION"));
		for (String[] m : OPTIMIZERS) {
			addModRow(rows, sodium, m[0], m[1]);
		}

		// Drop a section heading that ended up with no rows under it.
		List<Row> out = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			boolean emptySection = r.kind() == Kind.SECTION
					&& (i + 1 >= rows.size() || rows.get(i + 1).kind() == Kind.SECTION);
			if (!emptySection) {
				out.add(r);
			}
		}
		return out;
	}

	/** A bundled mod's row: opens its own config screen when it has one (via Mod
	 *  Menu's registry), otherwise an AUTO row. No row if the mod isn't loaded. */
	private static void addModRow(List<Row> rows, Screen parent, String modId, String label) {
		if (!loaded(modId)) {
			return;
		}
		if (loaded("modmenu") && ModMenuLink.hasConfig(modId)) {
			rows.add(link(label, () -> ModMenuLink.openConfig(modId, parent)));
		} else {
			rows.add(new Row(Kind.AUTO, label, null, false));
		}
	}

	private static boolean isIrisShaderPage(OptionPage p) {
		return p.getName().getContents() instanceof TranslatableContents tc
				&& "options.iris.shaderPackSelection".equals(tc.getKey());
	}

	private static boolean loaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	private static Row section(String label) {
		return new Row(Kind.SECTION, label, null, false);
	}

	private static Row link(String label, Runnable action) {
		return new Row(Kind.LINK, label, action, false);
	}

	// ---- render ----

	@Override
	protected void renderWidget(GuiGraphics g, int mx, int my, float partialTick) {
		Font font = Minecraft.getInstance().font;
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();

		// Rail surface: the mod menu's dark glass panel.
		OriginUi.panel(g, x, y, w, h, 3, OriginTheme.withAlpha(OriginTheme.BG, 0xE6), OriginTheme.STROKE);

		// Brand header: Origin mark + wordmark + "Video Settings"; wordmark only
		// on short screens so the rows keep their room.
		int cx = x + w / 2;
		int brandW = OriginText.widthBold(font, "ORIGIN");
		if (headerH() > 22) {
			OriginUi.logo(g, cx, y + 17, 18, 1f);
			OriginText.drawBold(g, font, "ORIGIN", cx - brandW / 2, y + 30, OriginTheme.TEXT, false);
			String sub = "Video Settings";
			OriginText.draw(g, font, sub, cx - OriginText.width(font, sub) / 2, y + 41, OriginTheme.MUTED, false);
		} else {
			OriginText.drawBold(g, font, "ORIGIN", cx - brandW / 2, y + 8, OriginTheme.TEXT, false);
		}

		// The open page's name over Sodium's option column (its tab row is gone).
		if (!pageTitle.isEmpty()) {
			int tx = x + w + MARGIN;
			OriginText.draw(g, font, "Video", tx, y + 6, OriginTheme.MUTED, false);
			int chevX = tx + OriginText.width(font, "Video") + 3;
			OriginUi.iconChevron(g, chevX, y + 5, 9, OriginTheme.MUTED, false);
			OriginText.drawBold(g, font, pageTitle, chevX + 12, y + 6, OriginTheme.TEXT, false);
		}

		int top = listTop(), bottom = listBottom();
		boolean inList = mx >= x && mx < x + w && my >= top && my < bottom;
		int rx = x + 4, rw = w - 8;
		g.enableScissor(x + 1, top, x + w - 1, bottom);
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			int ry = top + rowY[i] - (int) Math.round(scroll);
			int rh = r.kind() == Kind.SECTION ? SECTION_H : ROW_H;
			if (ry + rh <= top || ry >= bottom) {
				continue;
			}
			boolean hover = inList && my >= ry && my < ry + rh && r.kind() != Kind.SECTION;
			drawRow(g, font, r, rx, ry, rw, hover);
		}
		g.disableScissor();

		// Thin scroll thumb when the list overflows (short windows).
		double max = maxScroll();
		if (max > 0) {
			int viewH = bottom - top;
			int thumbH = Math.max(12, viewH * viewH / contentH);
			int thumbY = top + (int) Math.round((viewH - thumbH) * (scroll / max));
			g.fill(x + w - 3, thumbY, x + w - 1, thumbY + thumbH, OriginTheme.STROKE_STRONG);
		}
	}

	private void drawRow(GuiGraphics g, Font font, Row r, int x, int y, int w, boolean hover) {
		if (r.kind() == Kind.SECTION) {
			String s = OriginText.ellipsize(font, r.label(), w - 12);
			OriginText.draw(g, font, s, x + 6, y + 4, OriginTheme.MUTED, false);
			return;
		}
		if (r.kind() == Kind.AUTO) {
			// Not clickable: dimmed label + a short "auto" tag; hover explains.
			String tag = "auto";
			int tagW = OriginText.width(font, tag);
			String label = OriginText.ellipsize(font, r.label(), w - 22 - tagW);
			OriginText.draw(g, font, label, x + 9, y + 5, OriginTheme.MUTED, false);
			OriginText.draw(g, font, tag, x + w - 6 - tagW, y + 5, OriginTheme.withAlpha(OriginTheme.MUTED, 0x99), false);
			if (hover) {
				screen.setTooltipForNextRenderPass(Component.literal(r.label() + " works automatically. Nothing to set up."));
			}
			return;
		}

		float hp = OriginUi.hover("sodium-rail:" + r.label(), hover);
		if (r.active()) {
			// Same active pill as the mod menu rail: accent wash + aurora bar.
			OriginUi.panel(g, x, y, w, ROW_H, 6, OriginTheme.ACCENT_SOFT, OriginTheme.ACCENT_BORDER);
			int bx = x + 3, by0 = y + 4, bh = ROW_H - 8;
			for (int s = 0; s < 4; s++) {
				g.fill(bx, by0 + bh * s / 4, bx + 2, by0 + bh * (s + 1) / 4, OriginTheme.aurora(s / 3.0));
			}
		} else if (hp > 0.001f) {
			OriginUi.panel(g, x, y, w, ROW_H, 6,
					OriginTheme.lerpColor(0x00000000, OriginTheme.BOX_FILL_HOVER, hp), 0);
		}
		int textCol = r.active() ? OriginTheme.TEXT
				: OriginTheme.lerpColor(OriginTheme.TEXT_DIM, OriginTheme.TEXT, hp);
		int labelMax = w - 9 - (r.kind() == Kind.LINK ? 16 : 6);
		OriginText.drawBold(g, font, OriginText.ellipsize(font, r.label(), labelMax), x + 9, y + 5, textCol, false);
		if (r.kind() == Kind.LINK) {
			// Chevron: this row opens another screen rather than a page here.
			OriginUi.iconChevron(g, x + w - 13, y + 5, 8,
					OriginTheme.lerpColor(OriginTheme.MUTED, OriginTheme.TEXT_DIM, hp), false);
		}
	}

	// ---- input ----

	/** The clickable row under the cursor, or null. Same geometry as render. */
	@Nullable
	private Row rowAt(double mx, double my) {
		int top = listTop(), bottom = listBottom();
		if (mx < getX() || mx >= getX() + getWidth() || my < top || my >= bottom) {
			return null;
		}
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			if (r.action() == null) {
				continue;
			}
			int ry = top + rowY[i] - (int) Math.round(scroll);
			if (my >= ry && my < ry + ROW_H) {
				return r;
			}
		}
		return null;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!active || !visible || button != 0) {
			return false;
		}
		Row r = rowAt(mx, my);
		if (r == null || r.action() == null) {
			return false;
		}
		playDownSound(Minecraft.getInstance().getSoundManager());
		try {
			r.action().run();
		} catch (Throwable t) {
			// A broken third-party settings screen must never crash the game.
			OriginClient.LOGGER.warn("Origin: mod rail could not open '{}'", r.label(), t);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (!isMouseOver(mx, my) || maxScroll() <= 0) {
			return false;
		}
		scroll = clampScroll(scroll - scrollY * ROW_STEP);
		return true;
	}

	/** Mouse-only: Tab focus skips the rail and walks Sodium's own controls. */
	@Override
	@Nullable
	public ComponentPath nextFocusPath(FocusNavigationEvent event) {
		return null;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		defaultButtonNarrationText(out);
	}

	/**
	 * Mod Menu calls, isolated in their own class so nothing touches Mod Menu's
	 * classes unless Mod Menu is loaded (callers check {@code loaded("modmenu")}).
	 */
	private static final class ModMenuLink {
		private ModMenuLink() {
		}

		static boolean hasConfig(String modId) {
			try {
				return com.terraformersmc.modmenu.ModMenu.hasConfigScreen(modId);
			} catch (Throwable t) {
				return false;
			}
		}

		static void openConfig(String modId, Screen parent) {
			Screen s = com.terraformersmc.modmenu.ModMenu.getConfigScreen(modId, parent);
			if (s != null) {
				Minecraft.getInstance().setScreen(s);
			}
		}

		static void openModList(Screen parent) {
			Minecraft.getInstance().setScreen(new com.terraformersmc.modmenu.gui.ModsScreen(parent));
		}
	}
}
