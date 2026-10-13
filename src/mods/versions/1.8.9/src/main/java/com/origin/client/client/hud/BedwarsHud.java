package com.origin.client.client.hud;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.mods.HypixelBedwars;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The Hypixel Bed Wars mod's two HUD elements (Lunar lists both with the mod):
 *  - Resource Counter: how much iron, gold, diamond and emerald you're carrying
 *    -- the four Bed Wars currencies -- each as its item icon and a count, the
 *    same row shape as Item Counter.
 *  - Teammates: every teammate with their face, name (in your team colour) and
 *    health, or OUT / OFFLINE once they're gone.
 *
 * Both draw only in a Bed Wars game on Hypixel (HypixelBedwars.inGame) and only
 * with their own toggle on; in the HUD editor they show a sample so they can be
 * placed before a game. Background / border / text colour come from the mod's
 * shared HUD rows, background transparent by default like every Origin HUD.
 *
 * 1.8.9: no PlayerFaceRenderer / PlayerSkin -- a face is the skin texture's
 * face (8,8) + hat (40,8) cells blitted from the 64x64 skin, like this era's own
 * GuiPlayerTabOverlay (and OriginTabList here); items are matched by getItem(),
 * and an empty inventory slot is null (no ItemStack.EMPTY before 1.11). Skins come
 * from NetworkPlayerInfo.getLocationSkin (NickHider's skin hook applies there).
 */
final class BedwarsHud {
	private static final String MOD = HypixelBedwars.MOD;

	private BedwarsHud() {
	}

	// Default placements keep clear of Hypixel's sidebar (right middle), the chat
	// (bottom left) and the existing readout columns: the Resource Counter sits in
	// the bottom-right corner above where Pack Display goes, Teammates at the left
	// edge just above Combo / Reach.
	static void register() {
		HudElements.add("bwresources", MOD, "Resource Counter", new HudPos(8, -6, -24, 1.0),
				BedwarsHud::resourceSize, BedwarsHud::renderResources);
		HudElements.add("bwteammates", MOD, "Teammates", new HudPos(3, 6, -40, 1.0),
				BedwarsHud::teammatesSize, BedwarsHud::renderTeammates);
	}

	private static boolean shown(String toggle) {
		return Mods.bool(MOD, toggle) && (HudElements.editorPreview || HypixelBedwars.inGame());
	}

	private static void panel(Gfx g, int w, int h) {
		boolean bg = Mods.bool(MOD, "showBackground");
		boolean border = Mods.bool(MOD, "border");
		if (bg || border) {
			OriginUi.panel(g, -3, -3, w + 6, h + 6, 5,
					bg ? OriginColorPicker.liveColor(MOD, "bgColor") : 0,
					border ? OriginColorPicker.liveColor(MOD, "borderColor") : 0);
		}
	}

	// ---- Resource Counter ----

	private static final Item[] RESOURCES = {Items.iron_ingot, Items.gold_ingot, Items.diamond, Items.emerald};
	private static final int ROW = 16, ROW_GAP = 2;

	private static int count(Minecraft mc, Item item) {
		if (mc.thePlayer == null) {
			return 0;
		}
		int n = 0;
		for (ItemStack s : mc.thePlayer.inventory.mainInventory) {
			if (s != null && s.getItem() == item) {
				n += s.stackSize;
			}
		}
		return n;
	}

	private static int[] resourceSize(Minecraft mc) {
		if (!shown("resourceCounter")) {
			return new int[]{0, 0};
		}
		int w = 0;
		for (Item item : RESOURCES) {
			w = Math.max(w, 18 + mc.fontRendererObj.getStringWidth(String.valueOf(count(mc, item))) + 1);
		}
		return new int[]{w, RESOURCES.length * (ROW + ROW_GAP) - ROW_GAP};
	}

	private static void renderResources(Gfx g, Minecraft mc, int w, int h) {
		if (!shown("resourceCounter")) {
			return;
		}
		panel(g, w, h);
		boolean shadow = Mods.bool(MOD, "textShadow");
		int color = OriginColorPicker.liveColor(MOD, "color");
		int y = 0;
		for (Item item : RESOURCES) {
			g.renderItem(new ItemStack(item), 0, y);
			g.drawString(mc.fontRendererObj, String.valueOf(count(mc, item)), 18, y + 4, color, shadow);
			y += ROW + ROW_GAP;
		}
	}

	// ---- Teammates ----

	private static final int LINE = 11, FACE = 8;
	private static final int HEALTH_COLOR = 0xFFFF5555, OUT_COLOR = 0xFF8A8A8A;

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Row {
		private final ResourceLocation skin;
		private final String name;
		private final int nameColor;
		private final String status;
		private final int statusColor;

		private Row(ResourceLocation skin, String name, int nameColor, String status, int statusColor) {
			this.skin = skin;
			this.name = name;
			this.nameColor = nameColor;
			this.status = status;
			this.statusColor = statusColor;
		}

		public ResourceLocation skin() {
			return skin;
		}

		public String name() {
			return name;
		}

		public int nameColor() {
			return nameColor;
		}

		public String status() {
			return status;
		}

		public int statusColor() {
			return statusColor;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Row)) {
				return false;
			}
			Row other = (Row) obj;
			return java.util.Objects.equals(this.skin, other.skin)
					&& java.util.Objects.equals(this.name, other.name)
					&& this.nameColor == other.nameColor
					&& java.util.Objects.equals(this.status, other.status)
					&& this.statusColor == other.statusColor;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(skin, name, nameColor, status, statusColor);
		}
	}

	private static List<Row> rows(Minecraft mc) {
		List<Row> out = new ArrayList<>();
		if (!shown("teammatesHud")) {
			return out;
		}
		for (HypixelBedwars.Teammate t : HypixelBedwars.teammates(mc)) {
			ResourceLocation skin = t.info() != null ? t.info().getLocationSkin() : DefaultPlayerSkin.getDefaultSkin(t.id());
			String status;
			int statusColor;
			if (!t.online()) {
				status = "OFFLINE";
				statusColor = OUT_COLOR;
			} else if (!t.alive()) {
				status = "OUT";
				statusColor = OUT_COLOR;
			} else {
				status = t.health() < 0 ? "?" : t.health() + "\u2764";
				statusColor = HEALTH_COLOR;
			}
			out.add(new Row(skin, t.name(), t.color(), status, statusColor));
		}
		if (out.isEmpty() && HudElements.editorPreview && mc.thePlayer != null) {
			ResourceLocation skin = mc.thePlayer.getLocationSkin();
			out.add(new Row(skin, "Teammate", 0xFFFF5555, "20\u2764", HEALTH_COLOR));
			out.add(new Row(skin, "Teammate", 0xFFFF5555, "OUT", OUT_COLOR));
		}
		return out;
	}

	// face (8,8) + hat layer (40,8) of the 64x64 skin at FACE x FACE.
	private static void face(Gfx g, ResourceLocation skin, int x, int y) {
		g.blit(skin, x, y, FACE, FACE, 8f, 8f, 8, 8, 64, 64);
		GlStateManager.enableBlend();
		g.blit(skin, x, y, FACE, FACE, 40f, 8f, 8, 8, 64, 64);
		GlStateManager.disableBlend();
	}

	private static int rowWidth(Minecraft mc, Row r) {
		return FACE + 3 + mc.fontRendererObj.getStringWidth(r.name()) + 6 + mc.fontRendererObj.getStringWidth(r.status());
	}

	private static int[] teammatesSize(Minecraft mc) {
		List<Row> rows = rows(mc);
		if (rows.isEmpty()) {
			return new int[]{0, 0};
		}
		int w = 0;
		for (Row r : rows) {
			w = Math.max(w, rowWidth(mc, r));
		}
		return new int[]{w, rows.size() * LINE - 2};
	}

	private static void renderTeammates(Gfx g, Minecraft mc, int w, int h) {
		List<Row> rows = rows(mc);
		if (rows.isEmpty()) {
			return;
		}
		panel(g, w, h);
		boolean shadow = Mods.bool(MOD, "textShadow");
		int y = 0;
		for (Row r : rows) {
			face(g, r.skin(), 0, y);
			g.drawString(mc.fontRendererObj, r.name(), FACE + 3, y, r.nameColor(), shadow);
			// health right-aligned so the column lines up
			g.drawString(mc.fontRendererObj, r.status(), w - mc.fontRendererObj.getStringWidth(r.status()), y, r.statusColor(), shadow);
			y += LINE;
		}
	}
}
