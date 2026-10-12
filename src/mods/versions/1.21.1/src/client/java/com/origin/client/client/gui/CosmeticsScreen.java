package com.origin.client.client.gui;

import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.Cosmetics;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.cosmetics.CosmeticsService.Look;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Cosmetics: pick which of your owned capes and head items you wear. A
 * turning preview of your player sits on the left (drag to spin), your items
 * on the right, one row per slot with "None" first. Picking applies straight
 * away on your side and saves to the cosmetics service so other Origin
 * players see it too. Opened from the mod menu's rail; Esc goes back there.
 * Look copied from OriginItemSizeScreen (same panel, header and tiles).
 */
public class CosmeticsScreen extends Screen {

	private static final int TILE_W = 66, TILE_H = 62, GAP = 6;

	private float yaw = 200;              // degrees; the preview starts showing the back
	private Double dragFrom = null;
	private float dragYaw = 0;
	private long lastNanos = 0;
	private long idleUntil = 0;

	private volatile boolean saving = false;
	private volatile String message = null;
	private volatile boolean messageIsError = false;

	public CosmeticsScreen() {
		super(Component.literal("Cosmetics"));
	}

	@Override
	protected void init() {
		CosmeticsService.refreshMine();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---- geometry (same panel as OriginItemSizeScreen) ----
	private int pw() {
		return (int) (width * 0.78);
	}

	private int ph() {
		return (int) (height * 0.80);
	}

	private int px() {
		return (width - pw()) / 2;
	}

	private int py() {
		return (height - ph()) / 2;
	}

	private int previewW() {
		return Math.max(90, Math.min(170, pw() / 3));
	}

	private int bodyTop() {
		return py() + 44;
	}

	private int bodyBottom() {
		return py() + ph() - 40;
	}

	private int listX() {
		return px() + 14 + previewW() + 14;
	}

	private int listRight() {
		return px() + pw() - 14;
	}

	// ---- what's on offer ----
	private record Tile(Cosmetics.Slot slot, String id, String label) {
	}

	private List<Tile> tiles(Cosmetics.Slot slot, CosmeticsService.Mine mine) {
		List<Tile> out = new ArrayList<>();
		out.add(new Tile(slot, null, "None"));
		for (String id : mine.owned()) {
			Cosmetics.Item item = Cosmetics.get(id);
			if (item != null && item.slot() == slot) {
				// The row already says "Capes": "Orbit Cape" reads as "Orbit".
				out.add(new Tile(slot, id, item.name().replace(" Cape", "")));
			}
		}
		return out;
	}

	private Look wearing() {
		var user = Minecraft.getInstance().getUser();
		return user == null ? Look.NONE : CosmeticsService.lookOf(user.getProfileId());
	}

	// ---- drawing ----
	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (dragFrom == null && System.currentTimeMillis() > idleUntil) {
			yaw += dt * 20;                      // slow showroom turn
		}

		g.fill(0, 0, width, height, 0x88000000);
		OriginUi.panel(g, px(), py(), pw(), ph(), 12, 0xF00E0E0E, OriginTheme.STROKE);

		// header: back + title
		int hy = py() + 16;
		boolean backHover = in(mouseX, mouseY, px() + 14, hy, px() + 38, hy + 20);
		float backHp = OriginUi.hover("cosmetics:back", backHover);
		OriginUi.panel(g, px() + 14, hy, 24, 20, 6,
				OriginTheme.lerpColor(0x16FFFFFF, 0x2EFFFFFF, backHp),
				OriginTheme.lerpColor(OriginTheme.STROKE, OriginTheme.STROKE_HOVER, backHp));
		OriginUi.iconChevron(g, px() + 21, hy + 5, 10, OriginTheme.TEXT, true);
		OriginText.drawBold(g, font, "Cosmetics", px() + 46, hy + 2, OriginTheme.TEXT, false);
		OriginText.draw(g, font, "Choose what you wear. Other Origin players see it too.", px() + 46, hy + 13, OriginTheme.MUTED, false);

		renderPreview(g, mouseX, mouseY);

		CosmeticsService.Mine mine = CosmeticsService.mine();
		String blocker = switch (CosmeticsService.status()) {
			case STARTING -> "Loading your cosmetics...";
			case OFF -> "Cosmetics are coming soon.";
			case UNREACHABLE -> "Couldn't reach Origin. Check your connection.";
			case READY -> mine == null ? "Loading your cosmetics..." : null;
		};
		int x = listX();
		int y = bodyTop();
		if (blocker != null) {
			OriginText.draw(g, font, blocker, x, y + 4, OriginTheme.TEXT_DIM, false);
		} else if (mine.owned().isEmpty()) {
			OriginText.drawBold(g, font, "You don't own any cosmetics yet.", x, y + 4, OriginTheme.TEXT, false);
			OriginText.draw(g, font, "Capes, halos and crowns are in the Origin Store.", x, y + 16, OriginTheme.TEXT_DIM, false);
		} else {
			Look look = wearing();
			y = renderRow(g, "Capes", tiles(Cosmetics.Slot.CAPE, mine), look.cape(), x, y, mouseX, mouseY);
			renderRow(g, "Hats & halos", tiles(Cosmetics.Slot.HEAD, mine), look.head(), x, y + 10, mouseX, mouseY);
		}

		// foot: status message + store button
		int fy = py() + ph() - 30;
		String msg = saving ? "Saving..." : message;
		if (msg != null) {
			OriginText.draw(g, font, OriginText.ellipsize(font, msg, listRight() - px() - 140), px() + 18, fy + 6,
					messageIsError && !saving ? OriginTheme.DANGER : OriginTheme.TEXT_DIM, false);
		}
		int bw = 110, bx = listRight() - bw;
		boolean storeHover = in(mouseX, mouseY, bx, fy, bx + bw, fy + 20);
		float shp = OriginUi.hover("cosmetics:store", storeHover);
		OriginUi.panel(g, bx, fy, bw, 20, 6,
				OriginTheme.lerpColor(OriginTheme.ACCENT_SOFT, OriginTheme.withAlpha(OriginTheme.ACCENT, 0x55), shp),
				OriginTheme.ACCENT_BORDER);
		String label = "Open the store";
		OriginText.drawBold(g, font, label, bx + (bw - OriginText.widthBold(font, label)) / 2, fy + 6, OriginTheme.TEXT, false);
	}

	/** One slot's row: a label, then its tiles (wrapping). Returns the y below it. */
	private int renderRow(GuiGraphics g, String title, List<Tile> tiles, String worn, int x, int y, int mx, int my) {
		OriginText.drawBold(g, font, title, x, y, OriginTheme.TEXT_DIM, false);
		y += 12;
		int cx = x;
		for (Tile t : tiles) {
			if (cx + TILE_W > listRight()) {
				cx = x;
				y += TILE_H + GAP;
			}
			boolean selected = t.id() == null ? worn == null : t.id().equals(worn);
			boolean hover = in(mx, my, cx, y, cx + TILE_W, y + TILE_H) && !saving;
			float hp = OriginUi.hover("cosmetics:tile:" + t.slot() + ":" + t.id(), hover);
			OriginUi.panel(g, cx, y, TILE_W, TILE_H, 6,
					selected ? OriginTheme.withAlpha(OriginTheme.SUCCESS, 0x55)
							: OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, hp),
					selected ? 0xB37ACF9E : OriginTheme.lerpColor(OriginTheme.BOX_BORDER, OriginTheme.STROKE_HOVER, hp));
			drawIcon(g, t, cx + TILE_W / 2, y + 22);
			String name = OriginText.ellipsize(font, t.label(), TILE_W - 8);
			OriginText.draw(g, font, name, cx + (TILE_W - OriginText.width(font, name)) / 2, y + TILE_H - 13,
					selected ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, false);
			cx += TILE_W + GAP;
		}
		return y + TILE_H + GAP;
	}

	/** A small picture for a tile, centred on (cx, cy). */
	private void drawIcon(GuiGraphics g, Tile t, int cx, int cy) {
		if (t.id() == null) {
			g.fill(cx - 8, cy, cx + 8, cy + 2, OriginTheme.MUTED);
			return;
		}
		if (t.slot() == Cosmetics.Slot.CAPE) {
			ResourceLocation tex = CapeTextures.current(t.id());
			if (tex != null) {
				// the cape's outside face, 40x64 texels at (4,4), drawn 20x32
				g.blit(tex, cx - 10, cy - 16, 20, 32, CapeTextures.FACE_U, CapeTextures.FACE_V,
						CapeTextures.FACE_W, CapeTextures.FACE_H, CapeTextures.FRAME_W, CapeTextures.FRAME_H);
			}
			return;
		}
		switch (t.id()) {
			case "halo-orbit" -> OriginUi.logo(g, cx, cy, 26, 1f);
			case "halo-gold" -> {
				OriginUi.panel(g, cx - 13, cy - 4, 26, 8, 4, 0x00000000, 0xFFFFD56B);
				OriginUi.panel(g, cx - 12, cy - 3, 24, 6, 3, 0x00000000, 0xFFFFD56B);
			}
			case "crown-pixel" -> {
				g.fill(cx - 10, cy, cx + 10, cy + 6, 0xFFF2C14E);
				for (int i = -1; i <= 1; i++) {
					g.fill(cx + i * 8 - 2, cy - (i == 0 ? 7 : 5), cx + i * 8 + 2, cy, 0xFFF2C14E);
				}
				g.fill(cx - 1, cy + 2, cx + 1, cy + 4, 0xFFE0485B);
			}
			default -> {
			}
		}
	}

	/** Your player, turning slowly (drag to spin). Needs a world to stand in. */
	private void renderPreview(GuiGraphics g, int mx, int my) {
		int x0 = px() + 14, y0 = bodyTop(), x1 = x0 + previewW(), y1 = bodyBottom();
		OriginUi.panel(g, x0, y0, x1 - x0, y1 - y0, 8, OriginTheme.BOX_FILL, OriginTheme.BOX_BORDER);
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			String a = "Join a world to", b = "see your preview";
			OriginText.draw(g, font, a, x0 + (x1 - x0 - OriginText.width(font, a)) / 2, (y0 + y1) / 2 - 8, OriginTheme.MUTED, false);
			OriginText.draw(g, font, b, x0 + (x1 - x0 - OriginText.width(font, b)) / 2, (y0 + y1) / 2 + 4, OriginTheme.MUTED, false);
			return;
		}
		// Same steps as InventoryScreen.renderEntityInInventoryFollowsMouse, but
		// turned by our own yaw instead of the mouse so the back (capes) shows.
		float bodyRot = player.yBodyRot, yRot = player.getYRot(), xRot = player.getXRot();
		float headRotO = player.yHeadRotO, headRot = player.yHeadRot;
		g.enableScissor(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
		try {
			Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
			Quaternionf camera = new Quaternionf().rotateX(-0.12f);
			pose.mul(camera);
			player.yBodyRot = 180 + yaw;
			player.setYRot(180 + yaw);
			player.setXRot(0);
			player.yHeadRot = player.getYRot();
			player.yHeadRotO = player.getYRot();
			// ~55% of the box tall, leaving headroom for halos
			float size = Math.min((x1 - x0) * 0.9f, (y1 - y0) * 0.55f) / player.getBbHeight();
			Vector3f offset = new Vector3f(0, player.getBbHeight() / 2, 0);
			InventoryScreen.renderEntityInInventory(g, (x0 + x1) / 2f, (y0 + y1) / 2f + 14, size, offset, pose, camera, player);
		} finally {
			player.yBodyRot = bodyRot;
			player.setYRot(yRot);
			player.setXRot(xRot);
			player.yHeadRotO = headRotO;
			player.yHeadRot = headRot;
			g.disableScissor();
		}
	}

	// ---- input ----
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
		int fy = py() + ph() - 30, bw = 110, bx = listRight() - bw;
		if (in(mx, my, bx, fy, bx + bw, fy + 20)) {
			Util.getPlatform().openUri(Cosmetics.STORE_URL);
			return true;
		}
		if (in(mx, my, px() + 14, bodyTop(), px() + 14 + previewW(), bodyBottom())) {
			dragFrom = mx;
			dragYaw = yaw;
			return true;
		}
		Tile hit = tileAt(mx, my);
		if (hit != null && !saving) {
			pick(hit);
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragFrom != null) {
			yaw = dragYaw + (float) (mx - dragFrom) * 1.6f;
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

	/** Which tile is under the mouse - walks the same layout as renderRow. */
	private Tile tileAt(double mx, double my) {
		CosmeticsService.Mine mine = CosmeticsService.mine();
		if (CosmeticsService.status() != CosmeticsService.Status.READY || mine == null || mine.owned().isEmpty()) {
			return null;
		}
		int y = bodyTop();
		for (Cosmetics.Slot slot : Cosmetics.Slot.values()) {
			y += 12;
			int cx = listX();
			for (Tile t : tiles(slot, mine)) {
				if (cx + TILE_W > listRight()) {
					cx = listX();
					y += TILE_H + GAP;
				}
				if (in(mx, my, cx, y, cx + TILE_W, y + TILE_H)) {
					return t;
				}
				cx += TILE_W + GAP;
			}
			y += TILE_H + GAP + 10;
		}
		return null;
	}

	private void pick(Tile t) {
		Look now = wearing();
		Look next = t.slot() == Cosmetics.Slot.CAPE ? new Look(t.id(), now.head()) : new Look(now.cape(), t.id());
		if (next.equals(now)) {
			return;
		}
		saving = true;
		message = null;
		CosmeticsService.wear(next).thenAccept(error -> {
			saving = false;
			messageIsError = error != null;
			message = error != null ? error : "Saved. Other Origin players will see it within a few minutes.";
		});
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(new OriginModMenuScreen());
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}
}
