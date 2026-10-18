package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.OriginDevShots;
import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.CosmeticCatalog;
import com.origin.client.client.cosmetics.CosmeticDef;
import com.origin.client.client.cosmetics.Cosmetics;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.cosmetics.CosmeticsService.Look;
import com.origin.client.client.cosmetics.MenuPlayerPreview;
import com.origin.client.client.cosmetics.ThemeColors;
import com.origin.client.client.mods.Era;
import com.origin.client.client.mods.Mods;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * Cosmetics: pick what you wear from your owned items: capes, wings and
 * backpacks, hats and masks, menu themes. The tabs, "None" tiles, captions
 * and thumbnails all come from the catalog (cosmetics/CosmeticCatalog): a new
 * item or kind needs no change here. Built on
 * the shared OriginWindow frame (the redesigned mod menu's panel, header and
 * cards). A turning preview of your character fills the left card (drag to
 * spin); your items sit in a card grid on the right under type tabs, "None"
 * first. Picking applies straight away on your side and saves to the
 * cosmetics service so other Origin players see it too (a theme is local: it
 * recolours your own menus, so this very screen repaints when you pick one).
 * Thumbnails are the real model (MenuPlayerPreview), the cape's own texture,
 * or the theme's preview picture. Opened from the mod
 * menu's Cosmetics tab and the title / pause buttons; Esc goes back there.
 *
 * The preview: in a world it is your real player (armour, held item, cape
 * physics); from the title screen there is no player entity, so
 * MenuPlayerPreview draws your skin + equipped cosmetics itself (Will,
 * 2026-10-12). Both turn the same way.
 *
 * Pre-1.20 port: drawn through the module's Gfx shim; the in-world preview is
 * renderEntity below (this version's own InventoryScreen entity steps, with
 * our pose instead of the mouse-follow angles), and the account is read
 * through User.getGameProfile().
 */
public class CosmeticsScreen extends OriginWindow {

	// ---- layout (design px, inside OriginWindow's body rect) ----
	private static final float PREV_X = BODY_X, PREV_Y = BODY_Y, PREV_W = 264, PREV_H = BODY_H;
	private static final float LIST_X = PREV_X + PREV_W + M, LIST_W = W - M - LIST_X;
	private static final float TABS_H = 32;
	private static final float GRID_TOP = BODY_Y + TABS_H + 16, GRID_BOTTOM = BODY_Y + BODY_H;
	private static final int COLS = 4;
	private static final float GAP = 12, TILE_H = 164;
	private static final float TILE_W = (LIST_W - (COLS - 1) * GAP) / COLS;

	/** Degrees the preview turns per design px dragged. */
	private static final float DRAG_DEG_PER_PX = 0.8f;

	/**
	 * Dev shots only (ORIGIN_SHOTS set): the offline dev account owns nothing,
	 * so the harness's screenshots show every item, wearing a cape and a halo,
	 * to put the grid and the preview's cosmetics on camera. Never on in play.
	 */
	private static final boolean DEMO = OriginDevShots.enabled();
	private Look demoLook = null;

	/** Dev shots: the first catalog item of the cape, head and back slots, worn. */
	private Look demoLook() {
		if (demoLook == null) {
			Look l = Look.NONE;
			for (Cosmetics.Slot slot : new Cosmetics.Slot[] {Cosmetics.Slot.CAPE, Cosmetics.Slot.HEAD, Cosmetics.Slot.BACK}) {
				for (Cosmetics.Item it : Cosmetics.all()) {
					if (it.slot() == slot) {
						l = l.with(slot, it.id());
						break;
					}
				}
			}
			demoLook = l;
		}
		return demoLook;
	}

	private float yaw = 200;              // degrees; the preview starts showing the back
	private Double dragFrom = null;       // design px where a preview drag began
	private float dragYaw = 0;
	private long lastNanos = 0;
	private long idleUntil = 0;

	private volatile boolean saving = false;
	private volatile String message = null;
	private volatile boolean messageIsError = false;
	private volatile long messageAt = 0;

	public CosmeticsScreen(Screen parent) {
		super(Component.literal("Cosmetics"), parent);
	}

	@Override
	protected void init() {
		CosmeticsService.refreshMine();
	}

	@Override
	protected String heading() {
		return "Cosmetics";
	}

	@Override
	protected String subheading() {
		return "Choose what you wear. Other Origin players see it too.";
	}

	@Override
	protected String headingIcon() {
		return "@shirt";
	}

	@Override
	protected void drawHeaderActions(OriginDeck d, float right) {
		String label = "Open the store";
		float bw = buttonW(d, label, null);
		button(d, label, null, right - bw, 16, bw, 32, Style.PRIMARY, true,
				() -> Util.getPlatform().openUri(Cosmetics.STORE_URL));
	}

	// ---- what's on offer ----
	private record Tile(Cosmetics.Slot slot, String id, String label) {
	}

	/** The type tabs across the top of the list; one is shown only if the catalog has items for it. */
	private enum Tab {
		ALL("All"),
		CAPES("Capes", Cosmetics.Slot.CAPE),
		BACK("Wings & backpacks", Cosmetics.Slot.BACK),
		HEAD("Hats & masks", Cosmetics.Slot.HEAD, Cosmetics.Slot.FACE),
		THEMES("Themes", Cosmetics.Slot.THEME);

		final String label;
		final Cosmetics.Slot[] slots;

		Tab(String label, Cosmetics.Slot... slots) {
			this.label = label;
			this.slots = slots;
		}

		boolean covers(Cosmetics.Slot slot) {
			if (this == ALL) {
				return true;
			}
			for (Cosmetics.Slot s : slots) {
				if (s == slot) {
					return true;
				}
			}
			return false;
		}
	}

	/** The order slots are listed in (and get their "None" tile in). */
	private static final Cosmetics.Slot[] SLOT_ORDER = {
			Cosmetics.Slot.CAPE, Cosmetics.Slot.BACK, Cosmetics.Slot.HEAD, Cosmetics.Slot.FACE, Cosmetics.Slot.THEME };

	private Tab tab = Tab.ALL;

	private CosmeticsService.Status status() {
		return DEMO ? CosmeticsService.Status.READY : CosmeticsService.status();
	}

	private CosmeticsService.Mine mine() {
		if (DEMO) {
			List<String> all = new ArrayList<>();
			Cosmetics.all().forEach(i -> all.add(i.id()));
			return new CosmeticsService.Mine(all, demoLook());
		}
		return CosmeticsService.mine();
	}

	private Look wearing() {
		if (DEMO) {
			return demoLook();
		}
		var user = Minecraft.getInstance().getUser();
		return user == null || user.getGameProfile() == null ? Look.NONE
				: CosmeticsService.lookOf(user.getGameProfile().getId());
	}

	/** Does the catalog have any item for this slot at all (whether or not you own one)? */
	private static boolean catalogHas(Cosmetics.Slot slot) {
		for (Cosmetics.Item it : Cosmetics.all()) {
			if (it.slot() == slot) {
				return true;
			}
		}
		return false;
	}

	private static boolean tabShown(Tab t) {
		if (t == Tab.ALL) {
			return true;
		}
		for (Cosmetics.Slot s : t.slots) {
			if (catalogHas(s)) {
				return true;
			}
		}
		return false;
	}

	private static String noneLabel(Cosmetics.Slot slot) {
		return switch (slot) {
			case CAPE -> "No cape";
			case BACK -> "No wings or pack";
			case HEAD -> "No hat";
			case FACE -> "No mask";
			case THEME -> "Origin menu";
		};
	}

	/** What a tab lists: the "take it off" tiles first, then your owned items for that tab, in shop order. */
	private List<Tile> tilesFor(Tab tab, CosmeticsService.Mine mine) {
		List<Tile> out = new ArrayList<>();
		if (mine == null) {
			return out;
		}
		for (Cosmetics.Slot slot : SLOT_ORDER) {
			if (tab.covers(slot) && catalogHas(slot)) {
				out.add(new Tile(slot, null, noneLabel(slot)));
			}
		}
		for (Cosmetics.Item item : Cosmetics.all()) {
			if (!mine.owned().contains(item.id()) || !tab.covers(item.slot())) {
				continue;
			}
			// The card's caption already says "Cape": "Orbit Cape" reads as "Orbit".
			out.add(new Tile(item.slot(), item.id(), "cape".equals(item.kind()) ? item.name().replace(" Cape", "") : item.name()));
		}
		return out;
	}

	// ---- drawing ----
	@Override
	protected void drawBody(OriginDeck d) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (dragFrom == null && System.currentTimeMillis() > idleUntil) {
			yaw += dt * 20;                      // slow showroom turn
		}

		drawPreview(d);

		CosmeticsService.Mine mine = mine();
		String blocker = switch (status()) {
			case STARTING -> "Loading your cosmetics...";
			case OFF -> "Cosmetics are coming soon.";
			case UNREACHABLE -> "Couldn't reach Origin. Check your connection.";
			case READY -> mine != null ? null
				: !DEMO && CosmeticsService.mineFailed() ? "Couldn't load your cosmetics. Close and reopen this screen to try again."
				: "Loading your cosmetics...";
		};
		maxScroll = 0;
		if (blocker != null) {
			emptyState(d, blocker, null);
		} else if (mine.owned().isEmpty()) {
			emptyState(d, "You don't own any cosmetics yet.", "Capes, wings and hats are in the Origin Store. Menu themes come with Origin+.");
		} else {
			drawTabs(d, mine);
			drawGrid(d, mine, wearing());
		}
		drawToast(d);
	}

	/** A message centred in the list column: the shirt mark, a title and an optional line. */
	private void emptyState(OriginDeck d, String title, String line) {
		float cx = LIST_X + LIST_W / 2, cy = BODY_Y + BODY_H / 2 - 24;
		d.icon("@shirt", cx - 16, cy - 56, 32, MUTE);
		d.textCenter(title, cx, cy, 16, true, 0, TEXT);
		if (line != null) {
			float y = cy + 26;
			for (String l : d.wrap(line, LIST_W - 96, 13, false)) {
				d.textCenter(l, cx, y, 13, false, 0, DIM);
				y += 20;
			}
		}
	}

	private void drawTabs(OriginDeck d, CosmeticsService.Mine mine) {
		float x = LIST_X;
		if (!tabShown(tab)) {
			tab = Tab.ALL;   // the catalog changed under us
		}
		for (Tab t : Tab.values()) {
			if (!tabShown(t)) {
				continue;
			}
			String label = t.label.toUpperCase(Locale.ROOT);
			String count = t == Tab.ALL ? Integer.toString(mine.owned().size()) : null;
			float w = 24 + d.textW(label, 11, true, CAPS_TRACK) + (count == null ? 0 : 7 + d.textW(count, 11, true, 0));
			boolean on = tab == t;
			float hp = OriginUi.hover("cosmetics:tab:" + t.name(), d.over(x, BODY_Y, w, TABS_H));
			d.rect(x, BODY_Y, w, TABS_H, 6, on ? ACC_SOFT : lerp(0x0AFFFFFF, 0x14FFFFFF, hp));
			float lw = d.textMid(label, x + 12, BODY_Y + TABS_H / 2, 11, true, CAPS_TRACK, on ? ACC : lerp(DIM, TEXT, hp));
			if (count != null) {
				d.textMid(count, x + 12 + lw + 7, BODY_Y + TABS_H / 2, 11, true, 0, on ? mul(ACC, 0.8f) : MUTE);
			}
			d.hit(x, BODY_Y, w, TABS_H, () -> {
				tab = t;
				scroll = scrollTarget = 0;
			});
			x += w + 8;
		}
	}

	/** Four across, scrolling down; cards outside the grid are clipped. */
	private void drawGrid(OriginDeck d, CosmeticsService.Mine mine, Look look) {
		List<Tile> tiles = tilesFor(tab, mine);
		int rows = (tiles.size() + COLS - 1) / COLS;
		float contentH = rows == 0 ? 0 : rows * TILE_H + (rows - 1) * GAP + 24;
		maxScroll = Math.max(0, contentH - (GRID_BOTTOM - GRID_TOP));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);
		if (tiles.isEmpty()) {
			d.textCenter("Nothing here yet.", LIST_X + LIST_W / 2, GRID_TOP + 60, 14, false, 0, MUTE);
			return;
		}
		d.clip(LIST_X - 4, GRID_TOP - 4, LIST_W + 8, GRID_BOTTOM - GRID_TOP + 4);
		for (int i = 0; i < tiles.size(); i++) {
			float x = LIST_X + (i % COLS) * (TILE_W + GAP);
			float y = GRID_TOP + (i / COLS) * (TILE_H + GAP) - (float) scroll;
			if (y + TILE_H < GRID_TOP - 4 || y > GRID_BOTTOM) {
				continue;
			}
			drawTile(d, tiles.get(i), look, x, y);
		}
		d.unclip();
		if (tab == Tab.THEMES && !mine.plus()) {
			// Themes are Origin+ menu unlocks, never sold: a non-member sees only "None" here, so say why.
			d.textCenter("Menu themes come with Origin+.", LIST_X + LIST_W / 2, GRID_TOP + TILE_H + 44 - (float) scroll, 13, false, 0, DIM);
		}
		double op = Mods.metaNum("menuBgOpacity", 1.0);
		if (op > 0.02 && maxScroll > 0 && scroll < maxScroll - 1) {
			d.gradient(LIST_X - 4, GRID_BOTTOM - 40, LIST_W + 8, 40, mul(PANEL, 0), mul(PANEL, (float) op));
		}
		scrollbar(d, GRID_TOP, GRID_BOTTOM);
	}

	private void drawTile(OriginDeck d, Tile t, Look look, float x, float y) {
		String worn = look.get(t.slot());
		boolean selected = t.id() == null ? worn == null : t.id().equals(worn);
		boolean hov = !saving && d.over(x, y, TILE_W, TILE_H);
		float hp = OriginUi.hover("cosmetics:tile:" + t.slot() + ":" + t.id(), hov);
		int fill = lerp(CARD, OriginDeck.CARD_H, hp);
		d.rect(x, y, TILE_W, TILE_H, 8, fill, selected ? mul(ACC, 0.85f) : lerp(LINE, 0x29F1E9D2, hp));
		if (!saving) {
			d.hit(x, y, TILE_W, TILE_H, () -> pick(t));
		}

		drawArt(d, t, x + TILE_W / 2, y + 62, fill);
		d.textCenter(d.fit(t.label(), TILE_W - 20, 13.5f, true, 0), x + TILE_W / 2, y + 126, 13.5f, true, 0,
				selected || hp > 0.5f ? TEXT : DIM);
		d.textCenter(caption(t), x + TILE_W / 2, y + 146, 10, true, 1.6f, MUTE);

		if (t.id() != null && Cosmetics.isPlusOnly(t.id())) {
			// members-only: the Origin mark in the corner (+ the caption below)
			d.logo(x + TILE_W - 18, y + 16, 16, 0.2f);
		}
		if (selected) {
			String badge = t.id() == null ? "ON" : "WORN";
			float bw = 12 + d.textW(badge, 10, true, 1.4f);
			d.rect(x + 8, y + 8, bw, 16, 4, ACC_SOFT);
			d.textMid(badge, x + 14, y + 16, 10, true, 1.4f, ACC);
		}
	}

	private static String caption(Tile t) {
		if (t.id() == null) {
			return "NONE";
		}
		if (Cosmetics.isPlusOnly(t.id())) {
			return Cosmetics.Slot.THEME == t.slot() ? "ORIGIN+ THEME" : "ORIGIN+ MEMBERS";
		}
		Cosmetics.Item item = Cosmetics.get(t.id());
		String kind = item == null || item.kind() == null ? "" : item.kind();
		return switch (kind) {
			case "cape" -> Cosmetics.isAnimated(t.id()) ? "ANIMATED CAPE" : "CAPE";
			case "pack" -> "BACKPACK";
			case "theme" -> "MENU THEME";
			default -> kind.toUpperCase(Locale.ROOT);
		};
	}

	/** A card's picture, centred on (cx, cy) design px. `bg` is the card's fill. */
	private void drawArt(OriginDeck d, Tile t, float cx, float cy, int bg) {
		if (t.id() == null) {
			d.icon("@close", cx - 14, cy - 14, 28, MUTE);
			return;
		}
		try {
			switch (t.slot()) {
				case CAPE -> drawCapeArt(d, t, cx, cy);
				case THEME -> drawThemeArt(d, t, cx, cy);
				default -> {
					if (!drawModelThumb(d, t, cx - TILE_W / 2 + 6, cy - 54, TILE_W - 12, 104)) {
						d.icon("@shirt", cx - 16, cy - 16, 32, MUTE);
					}
				}
			}
		} catch (Throwable ex) {
			d.icon("@shirt", cx - 16, cy - 16, 32, MUTE);   // a picture must never break the screen
		}
	}

	/** The cape's outside face from its own texture. */
	private void drawCapeArt(OriginDeck d, Tile t, float cx, float cy) {
		CapeTextures.Face face = CapeTextures.face(t.id());
		if (face == null) {
			d.icon("@shirt", cx - 16, cy - 16, 32, MUTE);
			return;
		}
		// 42x67 design px (10:16), in physical px like everything the deck draws
		int x0 = Math.round(d.ox + (cx - 21) * d.s), y0 = Math.round(d.oy + (cy - 33.5f) * d.s);
		int w = Math.round(42 * d.s), h = Math.round(67 * d.s);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1f, 1f, 1f, d.alpha);
		d.g.blit(face.tex(), x0, y0, w, h, face.u(), face.v(), face.w(), face.h(), face.texW(), face.texH());
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	/** Sizes of theme preview pictures (0,0 = unreadable), read once. */
	private static final Map<ResourceLocation, int[]> PICTURE_SIZE = new HashMap<>();

	private static int[] pictureSize(ResourceLocation rl) {
		return PICTURE_SIZE.computeIfAbsent(rl, k -> {
			try {
				// this era's resource lookup differs by version; Era.openResource hides it (null = missing)
				Era.Opened res = Era.openResource(k);
				if (res == null) {
					return new int[] {0, 0};
				}
				try (InputStream in = res.in(); NativeImage img = NativeImage.read(in)) {
					return new int[] {img.getWidth(), img.getHeight()};
				}
			} catch (Exception e) {
				return new int[] {0, 0};
			}
		});
	}

	/** A theme's preview picture (the mod menu in its palette), or three colour swatches if there isn't one. */
	private void drawThemeArt(OriginDeck d, Tile t, float cx, float cy) {
		float boxW = TILE_W - 28, boxH = 96;
		ResourceLocation rl = CosmeticCatalog.texture(t.id());
		int[] size = pictureSize(rl);
		if (size[0] > 0 && size[1] > 0) {
			float k = Math.min(boxW / size[0], boxH / size[1]);
			float w = size[0] * k, h = size[1] * k;
			int x0 = Math.round(d.ox + (cx - w / 2) * d.s), y0 = Math.round(d.oy + (cy - h / 2) * d.s);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1f, 1f, 1f, d.alpha);
			d.g.blit(rl, x0, y0, Math.round(w * d.s), Math.round(h * d.s), 0f, 0f, size[0], size[1], size[0], size[1]);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			return;
		}
		CosmeticDef def = CosmeticCatalog.def(t.id());
		if (def == null || def.theme == null) {
			d.icon("@shirt", cx - 16, cy - 16, 32, MUTE);
			return;
		}
		float w = Math.min(boxW, 150), h = 84, x = cx - w / 2, y = cy - h / 2;
		ThemeColors th = def.theme;
		d.rect(x, y, w, h, 8, th.deck("PANEL", PANEL), th.deck("LINE2", LINE2));
		d.rect(x + 10, y + 10, w - 20, 34, 6, th.deck("CARD", CARD));
		d.rect(x + 18, y + 22, 52, 10, 5, th.deck("TEXT", TEXT));
		d.rect(x + 10, y + 54, 56, 20, 6, th.deck("ACC", ACC));
		d.rect(x + 74, y + 54, 24, 20, 6, th.deck("ACC_D", ACC_D));
	}

	/**
	 * A hat, mask, wing or backpack: the real model on your character, zoomed to
	 * the part it sits on, swaying a little. Drawn through MenuPlayerPreview with
	 * only this item worn. False if it couldn't be drawn (the caller shows an icon).
	 */
	private boolean drawModelThumb(OriginDeck d, Tile t, float x, float y, float w, float h) {
		boolean back = t.slot() == Cosmetics.Slot.BACK;
		boolean face = t.slot() == Cosmetics.Slot.FACE;
		float blocks = back ? 1.7f : face ? 0.9f : 1.15f;   // how much of the world fits the tile's height
		float focus = back ? 1.15f : face ? 1.64f : 1.72f;  // height above the feet at the tile's centre, blocks
		float sway = (float) Math.sin(Util.getMillis() / 1100.0) * 22f;
		float turn = (back ? 200f : 20f) + sway;           // the back for wings / packs, the front for the rest
		boolean drew = false;
		d.clip(x, y, w, h);
		var pose = d.g.pose();
		pose.pushPose();
		try {
			// Same set-up as the big preview: back to GUI px, in front of the panel.
			float gs = (float) d.gs;
			pose.scale(gs, gs, 1f);
			pose.translate(0, 0, 100);
			float size = h * d.s / gs / blocks;
			float gx = (d.ox + (x + w / 2) * d.s) / gs;
			float gy = (d.oy + (y + h / 2) * d.s) / gs + (focus - MenuPlayerPreview.HEIGHT / 2f) * size;
			drew = MenuPlayerPreview.renderThumb(d.g, gx, gy, size, turn, Look.NONE.with(t.slot(), t.id()));
		} catch (Throwable ex) {
			drew = false;
		} finally {
			pose.popPose();
			d.unclip();
		}
		return drew;
	}

	/** Your character, turning slowly (drag to spin). */
	private void drawPreview(OriginDeck d) {
		float x = PREV_X, y = PREV_Y, w = PREV_W, h = PREV_H;
		boolean hov = d.over(x, y, w, h) || dragFrom != null;
		d.rect(x, y, w, h, 8, CARD, hov ? 0x29F1E9D2 : LINE);
		var user = Minecraft.getInstance().getUser();
		if (user != null && user.getName() != null) {
			d.textCenter(d.fit(user.getName(), w - 40, 15, true, 0), x + w / 2, y + 36, 15, true, 0, TEXT);
		}
		// "drag to spin" hint along the bottom
		String hint = "DRAG TO SPIN";
		float hw = d.textW(hint, 10, true, 1.6f);
		float hx = x + (w - hw) / 2, hy = y + h - 28;
		d.icon("@chev-left", hx - 20, hy - 6, 12, MUTE);
		d.textMid(hint, hx, hy, 10, true, 1.6f, MUTE);
		d.icon("@chev-right", hx + hw + 8, hy - 6, 12, MUTE);

		// the player: feet a little above the hint, ~half the card tall, so a halo
		// (it rises ~1.4x the body height off the floor) stays clear of the name
		float cx = x + w / 2;
		float tall = Math.min(w * 0.9f, h * 0.5f);
		float feet = y + h - 76;
		float cy = feet - tall / 2;
		d.rect(cx - 52, feet - 4, 104, 10, 5, mul(0xFF000000, 0.35f));   // floor shadow
		OriginUi.softGlow(d.g, d.ox + cx * d.s, d.oy + cy * d.s, 340 * d.s, mul(0xFFFFFFFF, 0.06f * d.alpha));

		boolean drew;
		d.clip(x + 1, y + 1, w - 2, h - 2);
		var pose = d.g.pose();
		pose.pushPose();
		try {
			// Back to GUI px (the deck draws in physical px) so the model's lighting
			// normals and depth range match vanilla's inventory preview; +100 z keeps
			// every part in front of the panel already drawn at z 0.
			float gs = (float) d.gs;
			pose.scale(gs, gs, 1f);
			pose.translate(0, 0, 100);
			float gx = (d.ox + cx * d.s) / gs, gy = (d.oy + cy * d.s) / gs;
			float size = tall * d.s / gs / MenuPlayerPreview.HEIGHT;
			LocalPlayer player = Minecraft.getInstance().player;
			drew = player != null ? drawWorldPlayer(d, player, gx, gy, size)
					: MenuPlayerPreview.render(d.g, gx, gy, size, yaw, wearing());
		} catch (Throwable t) {
			drew = false;
		} finally {
			pose.popPose();
			d.unclip();
		}
		if (!drew) {
			d.textCenter("Join a world to", cx, cy - 10, 13, false, 0, MUTE);
			d.textCenter("see your preview", cx, cy + 10, 13, false, 0, MUTE);
		}
	}

	/**
	 * In a world: your real player, through this version's own inventory-entity
	 * steps (renderEntity below), turned by our own yaw instead of the mouse so
	 * the back (capes) shows. (x, y) is the model's centre in GUI px.
	 */
	private boolean drawWorldPlayer(OriginDeck d, LocalPlayer player, float x, float y, float size) {
		float bodyRot = player.yBodyRot, yRot = player.getYRot(), xRot = player.getXRot();
		float headRotO = player.yHeadRotO, headRot = player.yHeadRot;
		try {
			Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
			Quaternionf camera = new Quaternionf().rotateX(-0.12f);
			pose.mul(camera);
			player.yBodyRot = 180 + yaw;
			player.setYRot(180 + yaw);
			player.setXRot(0);
			player.yHeadRot = player.getYRot();
			player.yHeadRotO = player.getYRot();
			float scale = size * MenuPlayerPreview.HEIGHT / player.getBbHeight();
			// 1.21.1 lifts the model by half its height to centre it; this era's
			// entity steps stand it on its feet, so they go half a model lower.
			float feetY = y + scale * player.getBbHeight() / 2f;
			renderEntity(d.g.pose(), x, feetY, scale, pose, camera, player);
			return true;
		} finally {
			player.yBodyRot = bodyRot;
			player.setYRot(yRot);
			player.setXRot(xRot);
			player.yHeadRotO = headRotO;
			player.yHeadRot = headRot;
		}
	}

	/**
	 * InventoryScreen.renderEntityInInventory's own steps on 1.19.4 (GUI
	 * PoseStack, feet at (x, y)), with the caller's pose instead of mouse-follow
	 * angles -- so the slow turn and drag-to-spin show the back (capes).
	 */
	private static void renderEntity(PoseStack ps, float x, float y, float scale, Quaternionf pose, Quaternionf camera,
									 LivingEntity e) {
		ps.pushPose();
		ps.translate(x, y, 50.0);
		ps.mulPoseMatrix(new Matrix4f().scaling(scale, scale, -scale));
		ps.mulPose(pose);
		Lighting.setupForEntityInInventory();
		EntityRenderDispatcher d = Minecraft.getInstance().getEntityRenderDispatcher();
		camera.conjugate();
		d.overrideCameraOrientation(camera);
		d.setRenderShadow(false);
		MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
		RenderSystem.runAsFancy(() -> d.render(e, 0.0, 0.0, 0.0, 0.0F, 1.0F, ps, buffers, 15728880));
		buffers.endBatch();
		d.setRenderShadow(true);
		ps.popPose();
		Lighting.setupFor3DItems();
	}

	/** "Saving..." while a pick is in flight, then the result for a few seconds. */
	private void drawToast(OriginDeck d) {
		String text;
		float a;
		if (saving) {
			text = "Saving...";
			a = 1f;
		} else {
			String m = message;
			long age = System.currentTimeMillis() - messageAt;
			long life = messageIsError ? 5000 : 3200;
			if (m == null || age >= life) {
				return;
			}
			text = m;
			a = age > life - 300 ? (life - age) / 300f : 1f;
		}
		boolean error = !saving && messageIsError;
		float base = d.alpha;
		d.alpha = base * a;
		boolean icon = !saving;
		float tw = d.textW(text, 13, false, 0);
		float w = 14 + (icon ? 22 : 0) + tw + 16;
		float x = LIST_X + (LIST_W - w) / 2, y = H - M - 36 - 8;
		d.rect(x, y, w, 36, 8, 0xFA181D1A, error ? mul(RED, 0.5f) : LINE2);
		float tx = x + 14;
		if (icon) {
			d.icon(error ? "@close" : "@check", tx, y + 11, 14, error ? RED : ACC);
			tx += 22;
		}
		d.textMid(text, tx, y + 18, 13, false, 0, TEXT);
		d.alpha = base;
	}

	// ---- input ----
	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		float x = dX(mx), y = dY(my);
		if (button == 0 && x >= PREV_X && x < PREV_X + PREV_W && y >= PREV_Y && y < PREV_Y + PREV_H) {
			dragFrom = (double) x;
			dragYaw = yaw;
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragFrom != null) {
			// Will (2026-10-12): the turn follows the opposite way to the first build.
			yaw = dragYaw - (float) (dX(mx) - dragFrom) * DRAG_DEG_PER_PX;
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (dragFrom != null) {
			dragFrom = null;
			idleUntil = System.currentTimeMillis() + 2500;
			return true;
		}
		return super.mouseReleased(mx, my, button);
	}

	private void pick(Tile t) {
		Look now = wearing();
		Look next = now.with(t.slot(), t.id());
		if (next.equals(now) || saving) {
			return;
		}
		if (DEMO) {
			demoLook = next;
			showMessage(null);
			return;
		}
		saving = true;
		message = null;
		CosmeticsService.wear(next).thenAccept(error -> {
			saving = false;
			showMessage(error);
		});
	}

	private void showMessage(String error) {
		messageIsError = error != null;
		message = error != null ? error : "Saved. Other Origin players will see it within a few minutes.";
		messageAt = System.currentTimeMillis();
	}
}
