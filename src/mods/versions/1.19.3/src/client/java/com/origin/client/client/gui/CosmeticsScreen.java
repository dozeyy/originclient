package com.origin.client.client.gui;

import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.Cosmetics;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.cosmetics.CosmeticsService.Look;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
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
 *
 * Pre-1.20 port: drawn through Gfx, 3-arg mouseScrolled, and the turning
 * preview is renderEntity below -- this version's own InventoryScreen
 * entity steps with our pose instead of the mouse-follow angles.
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

	/** Where Esc / Back return to: the title screen, the pause menu, or the mod menu. */
	private final Screen parent;

	public CosmeticsScreen(Screen parent) {
		super(Component.literal("Cosmetics"));
		this.parent = parent;
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

	/** The four type tabs across the top of the list. */
	private enum Tab {
		ALL("All"), CAPES("Capes"), ANIMATED("Animated"), HEAD("Hats & halos");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final int COLS = 3, TABS_H = 16, TAB_GAP = 4;
	private Tab tab = Tab.ALL;
	private double scroll = 0, scrollTarget = 0;

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
			out.add(new Tile(Cosmetics.Slot.CAPE, null, "None"));
		} else if (tab == Tab.HEAD) {
			out.add(new Tile(Cosmetics.Slot.HEAD, null, "None"));
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
			// The tabs already say "Capes": "Orbit Cape" reads as "Orbit".
			if (inTab) {
				out.add(new Tile(item.slot(), id, item.name().replace(" Cape", "")));
			}
		}
		return out;
	}

	private Look wearing() {
		var user = Minecraft.getInstance().getUser();
		return user == null || user.getGameProfile() == null ? Look.NONE
				: CosmeticsService.lookOf(user.getGameProfile().getId());
	}

	// ---- drawing ----
	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		Gfx g = new Gfx(poseStack);
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
			renderTabs(g, mouseX, mouseY);
			renderGrid(g, mine, wearing(), mouseX, mouseY, dt);
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

	// ---- list geometry: shared by drawing and clicking ----
	private int gridTop() {
		return bodyTop() + TABS_H + 10;
	}

	private int gridBottom() {
		return bodyBottom();
	}

	private int gridLeft() {
		return listX();
	}

	private int gridRight() {
		return listRight();
	}

	private int tileW() {
		return (gridRight() - gridLeft() - (COLS - 1) * GAP) / COLS;
	}

	private int rowStep() {
		return TILE_H + GAP;
	}

	private int maxScroll() {
		CosmeticsService.Mine mine = CosmeticsService.mine();
		int rows = (tilesFor(tab, mine).size() + COLS - 1) / COLS;
		return Math.max(0, rows * rowStep() - (gridBottom() - gridTop()));
	}

	private void renderTabs(Gfx g, int mx, int my) {
		int tx = listX(), ty = bodyTop();
		for (Tab t : Tab.values()) {
			int tw = OriginText.widthBold(font, t.label) + 16;
			boolean active = tab == t;
			boolean hover = in(mx, my, tx, ty, tx + tw, ty + TABS_H);
			float hp = OriginUi.hover("cosmetics:tab:" + t.name(), hover);
			OriginUi.panel(g, tx, ty, tw, TABS_H, 6,
					active ? OriginTheme.BOX_FILL_HOVER
							: OriginTheme.lerpColor(0x00000000, OriginTheme.BOX_FILL, hp),
					active ? OriginTheme.STROKE_STRONG : 0);
			OriginText.drawBold(g, font, t.label, tx + 8, ty + 4,
					active ? OriginTheme.TEXT : OriginTheme.lerpColor(OriginTheme.MUTED, OriginTheme.TEXT_DIM, hp), false);
			tx += tw + TAB_GAP;
		}
	}

	private Tab tabAt(double mx, double my) {
		int tx = listX(), ty = bodyTop();
		for (Tab t : Tab.values()) {
			int tw = OriginText.widthBold(font, t.label) + 16;
			if (in(mx, my, tx, ty, tx + tw, ty + TABS_H)) {
				return t;
			}
			tx += tw + TAB_GAP;
		}
		return null;
	}

	/** Three across, scrolling down. Tiles outside the grid are clipped. */
	private void renderGrid(Gfx g, CosmeticsService.Mine mine, Look look, int mx, int my, float dt) {
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget));
		scroll += (scrollTarget - scroll) * Math.min(1.0, dt * 14);
		List<Tile> tiles = tilesFor(tab, mine);
		if (tiles.isEmpty()) {
			OriginText.draw(g, font, "Nothing here yet.", gridLeft(), gridTop() + 4, OriginTheme.TEXT_DIM, false);
			return;
		}
		int tw = tileW();
		g.enableScissor(gridLeft(), gridTop(), gridRight(), gridBottom());
		try {
			for (int i = 0; i < tiles.size(); i++) {
				Tile t = tiles.get(i);
				int x = gridLeft() + (i % COLS) * (tw + GAP);
				int y = gridTop() + (i / COLS) * rowStep() - (int) scroll;
				if (y + TILE_H < gridTop() || y > gridBottom()) {
					continue;
				}
				String worn = t.slot() == Cosmetics.Slot.CAPE ? look.cape() : look.head();
				boolean selected = t.id() == null ? worn == null : t.id().equals(worn);
				boolean hover = !saving && in(mx, my, x, y, x + tw, y + TILE_H)
						&& my >= gridTop() && my < gridBottom();
				float hp = OriginUi.hover("cosmetics:tile:" + t.slot() + ":" + t.id(), hover);
				OriginUi.panel(g, x, y, tw, TILE_H, 6,
						selected ? OriginTheme.withAlpha(OriginTheme.SUCCESS, 0x55)
								: OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, hp),
						selected ? 0xB37ACF9E : OriginTheme.lerpColor(OriginTheme.BOX_BORDER, OriginTheme.STROKE_HOVER, hp));
				drawIcon(g, t, x + tw / 2, y + 22);
				String name = OriginText.ellipsize(font, t.label(), tw - 8);
				OriginText.draw(g, font, name, x + (tw - OriginText.width(font, name)) / 2, y + TILE_H - 13,
						selected ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, false);
			}
		} finally {
			g.disableScissor();
		}
	}

	private Tile gridTileAt(double mx, double my) {
		CosmeticsService.Mine mine = CosmeticsService.mine();
		if (CosmeticsService.status() != CosmeticsService.Status.READY || mine == null) {
			return null;
		}
		if (mx < gridLeft() || mx >= gridRight() || my < gridTop() || my >= gridBottom()) {
			return null;
		}
		List<Tile> tiles = tilesFor(tab, mine);
		int tw = tileW();
		for (int i = 0; i < tiles.size(); i++) {
			int x = gridLeft() + (i % COLS) * (tw + GAP);
			int y = gridTop() + (i / COLS) * rowStep() - (int) scroll;
			if (in(mx, my, x, y, x + tw, y + TILE_H)) {
				return tiles.get(i);
			}
		}
		return null;
	}

	/** A small picture for a tile, centred on (cx, cy). */
	private void drawIcon(Gfx g, Tile t, int cx, int cy) {
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
	private void renderPreview(Gfx g, int mx, int my) {
		int x0 = px() + 14, y0 = bodyTop(), x1 = x0 + previewW(), y1 = bodyBottom();
		OriginUi.panel(g, x0, y0, x1 - x0, y1 - y0, 8, OriginTheme.BOX_FILL, OriginTheme.BOX_BORDER);
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			String a = "Join a world to", b = "see your preview";
			OriginText.draw(g, font, a, x0 + (x1 - x0 - OriginText.width(font, a)) / 2, (y0 + y1) / 2 - 8, OriginTheme.MUTED, false);
			OriginText.draw(g, font, b, x0 + (x1 - x0 - OriginText.width(font, b)) / 2, (y0 + y1) / 2 + 4, OriginTheme.MUTED, false);
			return;
		}
		// Same steps as InventoryScreen's mouse-follow preview, but turned by our
		// own yaw instead of the mouse so the back (capes) shows.
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
			// 1.21.1 lifts the model by half its height to centre it; here the
			// entity stands on its feet, so they go half a model below the centre.
			float feetY = (y0 + y1) / 2f + 14 + size * player.getBbHeight() / 2f;
			renderEntity((x0 + x1) / 2f, feetY, size, pose, camera, player);
		} finally {
			player.yBodyRot = bodyRot;
			player.setYRot(yRot);
			player.setXRot(xRot);
			player.yHeadRotO = headRotO;
			player.yHeadRot = headRot;
			g.disableScissor();
		}
	}

	/**
	 * InventoryScreen.renderEntityInInventory's own steps on this version (it
	 * positions through RenderSystem's model-view stack, feet at (x, y)), with
	 * the caller's pose instead of the mouse-follow angles vanilla computes --
	 * so the slow turn and drag-to-spin show the back (capes).
	 */
	private static void renderEntity(float x, float y, float scale, Quaternionf pose, Quaternionf camera, LivingEntity e) {
		PoseStack modelView = RenderSystem.getModelViewStack();
		modelView.pushPose();
		modelView.translate(x, y, 1050.0);
		modelView.scale(1.0F, 1.0F, -1.0F);
		RenderSystem.applyModelViewMatrix();
		PoseStack ps = new PoseStack();
		ps.translate(0.0, 0.0, 1000.0);
		ps.scale(scale, scale, scale);
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
		modelView.popPose();
		RenderSystem.applyModelViewMatrix();
		Lighting.setupFor3DItems();
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
		Tab clicked = tabAt(mx, my);
		if (clicked != null) {
			tab = clicked;
			scrollTarget = scroll = 0;
			return true;
		}
		Tile hit = gridTileAt(mx, my);
		if (hit != null && !saving) {
			pick(hit);
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (mx >= gridLeft() && mx < gridRight() && my >= gridTop() && my < gridBottom()) {
			scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - amount * rowStep()));
			return true;
		}
		return super.mouseScrolled(mx, my, amount);
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
		Minecraft.getInstance().setScreen(parent);
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}
}
