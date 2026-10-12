package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.OriginDevShots;
import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.Cosmetics;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.cosmetics.CosmeticsService.Look;
import com.origin.client.client.cosmetics.MenuPlayerPreview;
import com.origin.client.client.mods.Mods;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * Cosmetics: pick which of your owned capes and head items you wear. Built on
 * the shared OriginWindow frame (the redesigned mod menu's panel, header and
 * cards). A turning preview of your character fills the left card (drag to
 * spin); your items sit in a card grid on the right under type tabs, "None"
 * first. Picking applies straight away on your side and saves to the
 * cosmetics service so other Origin players see it too. Opened from the mod
 * menu's Cosmetics tab and the title / pause buttons; Esc goes back there.
 *
 * The preview: in a world it is your real player (armour, held item, cape
 * physics); from the title screen there is no player entity, so
 * MenuPlayerPreview draws your skin + equipped cosmetics itself (Will,
 * 2026-10-12). Both turn the same way.
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
	private Look demoLook = new Look("cape-orbit", "halo-orbit");

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

	/** The four type tabs across the top of the list. */
	private enum Tab {
		ALL("All"), CAPES("Capes"), ANIMATED("Animated"), HEAD("Hats & halos");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private Tab tab = Tab.ALL;

	private CosmeticsService.Status status() {
		return DEMO ? CosmeticsService.Status.READY : CosmeticsService.status();
	}

	private CosmeticsService.Mine mine() {
		if (DEMO) {
			List<String> all = new ArrayList<>();
			Cosmetics.ALL.forEach(i -> all.add(i.id()));
			return new CosmeticsService.Mine(all, demoLook);
		}
		return CosmeticsService.mine();
	}

	private Look wearing() {
		if (DEMO) {
			return demoLook;
		}
		var user = Minecraft.getInstance().getUser();
		return user == null ? Look.NONE : CosmeticsService.lookOf(user.getProfileId());
	}

	/** What a tab lists: the "take it off" tiles first, then your owned items for that tab. */
	private List<Tile> tilesFor(Tab tab, CosmeticsService.Mine mine) {
		List<Tile> out = new ArrayList<>();
		if (mine == null) {
			return out;
		}
		if (tab == Tab.ALL) {
			out.add(new Tile(Cosmetics.Slot.CAPE, null, "No cape"));
			out.add(new Tile(Cosmetics.Slot.HEAD, null, "No hat"));
		} else if (tab == Tab.CAPES) {
			out.add(new Tile(Cosmetics.Slot.CAPE, null, "No cape"));
		} else if (tab == Tab.HEAD) {
			out.add(new Tile(Cosmetics.Slot.HEAD, null, "No hat"));
		}
		for (String id : mine.owned()) {
			Cosmetics.Item item = Cosmetics.get(id);
			if (item == null) {
				continue;
			}
			boolean inTab = switch (tab) {
				case ALL -> true;
				case CAPES -> item.slot() == Cosmetics.Slot.CAPE;
				case HEAD -> item.slot() == Cosmetics.Slot.HEAD;
				case ANIMATED -> Cosmetics.isAnimated(id);
			};
			// The card's caption already says "Cape": "Orbit Cape" reads as "Orbit".
			if (inTab) {
				out.add(new Tile(item.slot(), id, item.name().replace(" Cape", "")));
			}
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
			case READY -> mine == null ? "Loading your cosmetics..." : null;
		};
		maxScroll = 0;
		if (blocker != null) {
			emptyState(d, blocker, null);
		} else if (mine.owned().isEmpty()) {
			emptyState(d, "You don't own any cosmetics yet.", "Capes, halos and crowns are in the Origin Store.");
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
		for (Tab t : Tab.values()) {
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
		double op = Mods.metaNum("menuBgOpacity", 1.0);
		if (op > 0.02 && maxScroll > 0 && scroll < maxScroll - 1) {
			d.gradient(LIST_X - 4, GRID_BOTTOM - 40, LIST_W + 8, 40, mul(PANEL, 0), mul(PANEL, (float) op));
		}
		scrollbar(d, GRID_TOP, GRID_BOTTOM);
	}

	private void drawTile(OriginDeck d, Tile t, Look look, float x, float y) {
		String worn = t.slot() == Cosmetics.Slot.CAPE ? look.cape() : look.head();
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
		if (t.slot() == Cosmetics.Slot.CAPE) {
			return Cosmetics.isAnimated(t.id()) ? "ANIMATED CAPE" : "CAPE";
		}
		return t.id().startsWith("crown") ? "CROWN" : "HALO";
	}

	/** A card's picture, centred on (cx, cy) design px. `bg` is the card's fill (for ring holes). */
	private void drawArt(OriginDeck d, Tile t, float cx, float cy, int bg) {
		if (t.id() == null) {
			d.icon("@close", cx - 14, cy - 14, 28, MUTE);
			return;
		}
		if (t.slot() == Cosmetics.Slot.CAPE) {
			ResourceLocation tex = CapeTextures.current(t.id());
			if (tex == null) {
				d.icon("@shirt", cx - 16, cy - 16, 32, MUTE);
				return;
			}
			// the cape's outside face (40x64 texels at (4,4)) at 42x67 design px,
			// in physical px like everything the deck draws
			int x0 = Math.round(d.ox + (cx - 21) * d.s), y0 = Math.round(d.oy + (cy - 33.5f) * d.s);
			int w = Math.round(42 * d.s), h = Math.round(67 * d.s);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1f, 1f, 1f, d.alpha);
			d.g.blit(tex, x0, y0, w, h, CapeTextures.FACE_U, CapeTextures.FACE_V,
					CapeTextures.FACE_W, CapeTextures.FACE_H, CapeTextures.FRAME_W, CapeTextures.FRAME_H);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			return;
		}
		switch (t.id()) {
			case "halo-orbit" -> d.logo(cx, cy, 50, 0.35f);
			case "halo-gold" -> {
				OriginUi.softGlow(d.g, d.ox + cx * d.s, d.oy + cy * d.s, 90 * d.s, mul(0xFFFFD56B, 0.35f * d.alpha));
				d.rect(cx - 26, cy - 9, 52, 18, 9, 0xFFFFD56B);
				d.rect(cx - 21, cy - 4.5f, 42, 9, 4.5f, bg);
			}
			case "crown-pixel" -> {
				int gold = 0xFFF2C14E;
				d.rect(cx - 22, cy - 2, 44, 14, 2, gold);
				for (int i = -1; i <= 1; i++) {
					float ph = i == 0 ? 16 : 12;
					d.rect(cx + i * 18 - 4, cy - 2 - ph, 8, ph + 2, 2, gold);
				}
				d.rect(cx - 3, cy + 2, 6, 6, 1.5f, 0xFFE0485B);
			}
			default -> d.icon("@shirt", cx - 16, cy - 16, 32, MUTE);
		}
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
	 * In a world: your real player, through InventoryScreen.renderEntityInInventory,
	 * turned by our own yaw instead of the mouse so the back (capes) shows.
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
			// 1.20 / 1.20.1: int position + scale and no centring offset -- (x, y) is
			// where the feet go. Translate to the exact (fractional) feet point first
			// and draw at 0,0, so the body is centred on (x, y) like 1.21.1's.
			int scale = Math.max(1, Math.round(size * MenuPlayerPreview.HEIGHT / player.getBbHeight()));
			d.g.pose().pushPose();
			try {
				d.g.pose().translate(x, y + player.getBbHeight() / 2 * scale, 0);
				InventoryScreen.renderEntityInInventory(d.g, 0, 0, scale, pose, camera, player);
			} finally {
				d.g.pose().popPose();
			}
			return true;
		} finally {
			player.yBodyRot = bodyRot;
			player.setYRot(yRot);
			player.setXRot(xRot);
			player.yHeadRotO = headRotO;
			player.yHeadRot = headRot;
		}
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
		Look next = t.slot() == Cosmetics.Slot.CAPE ? new Look(t.id(), now.head()) : new Look(now.cape(), t.id());
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
