package com.origin.client.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * Every mod-menu icon, drawn as a REAL Minecraft item wherever one fits — the same
 * icon set every 1.21.x version uses, ported to 26.2 so the mod menu looks identical.
 *
 * Rendering live ItemStacks means zero art to maintain and the icons match whatever
 * resource pack the player runs. Items are full-colour and 3D-lit — a deliberate,
 * Will-approved break of the theme's no-hue rule. Four ideas have no item that says
 * them, so they ship as baked Origin textures (textures/ui/modicon/); "fps" draws the
 * live frame count as text.
 *
 * PER-VERSION DELTA (26.2): GuiGraphics → GuiGraphicsExtractor, renderFakeItem →
 * fakeItem, drawString → text. The player-face path (Tab Editor's icon) is dropped —
 * 26.2 removed the client skin API (PlayerFaceRenderer.draw / SkinManager.getInsecureSkin),
 * and tablist isn't a 26.2 card anyway; FACE falls back to the PLAYER_HEAD item.
 */
public final class ModIcons {

	/** Mod id -> the vanilla item that represents it. */
	private static final Map<String, Item> ITEMS = new HashMap<>();

	/** Mod id -> baked Origin texture, for the ideas no item expresses. */
	private static final Map<String, Identifier> CUSTOM = new HashMap<>();

	/** Native size of each baked texture, so the blit's UV source is right. */
	private static final Map<String, Integer> CUSTOM_SIZE = new HashMap<>();

	/** Drawn as live text rather than any icon. */
	public static final String FPS = "fps";

	/** Tab Editor — no skin API on 26.2, so it falls back to the PLAYER_HEAD item. */
	public static final String FACE = "tablist";

	static {
		ITEMS.put("togglesprint", Items.FEATHER);
		ITEMS.put("zoom", Items.SPYGLASS);
		ITEMS.put("armorhud", Items.DIAMOND_CHESTPLATE);
		ITEMS.put("coords", Items.COMPASS);
		ITEMS.put("potionhud", Items.POTION);
		ITEMS.put("serveraddress", Items.COMMAND_BLOCK);
		ITEMS.put("scoreboard", Items.OAK_SIGN);
		ITEMS.put("freelook", Items.ENDER_EYE);
		ITEMS.put("fullbright", Items.GLOWSTONE);
		ITEMS.put("hitboxes", Items.ARMOR_STAND);
		ITEMS.put("nametags", Items.NAME_TAG);
		ITEMS.put("tablist", Items.PLAYER_HEAD);
		ITEMS.put("colorsaturation", Items.BRUSH);
		ITEMS.put("itemsize", Items.DROPPER);
		ITEMS.put("waypoints", Items.LODESTONE);
		ITEMS.put("weather", Items.TRIDENT);
		ITEMS.put("timechanger", Items.CLOCK);
		ITEMS.put("motionblur", Items.PHANTOM_MEMBRANE);
		ITEMS.put("chat", Items.WRITABLE_BOOK);
		ITEMS.put("particles", Items.FIREWORK_STAR);
		ITEMS.put("jei", Items.KNOWLEDGE_BOOK);

		// Settings tab pseudo-ids + the menu's own chrome — not mods, but they draw
		// through the same icon path.
		ITEMS.put("@general", Items.COMPARATOR);
		ITEMS.put("@performance", Items.REDSTONE);
		ITEMS.put("@hudeditor", Items.ITEM_FRAME);
		ITEMS.put("@search", Items.SPYGLASS);
		ITEMS.put("@backing", Items.GLASS);

		custom("cps", 16);
		custom("keystrokes", 16);
		custom("chunkborders", 16);
		custom("blockoverlay", 32);
	}

	private static void custom(String id, int nativeSize) {
		CUSTOM.put(id, Identifier.fromNamespaceAndPath("originclient", "textures/ui/modicon/" + id + ".png"));
		CUSTOM_SIZE.put(id, nativeSize);
	}

	private ModIcons() {
	}

	/** Whether this id draws through the item/texture path at all. */
	public static boolean has(String id) {
		return ITEMS.containsKey(id) || CUSTOM.containsKey(id) || FPS.equals(id);
	}

	/**
	 * Draws the icon for `id` in a size x size box at (x,y). `alpha` (0..1) carries
	 * the mod menu's open/close fade.
	 */
	public static void draw(GuiGraphicsExtractor g, String id, int x, int y, int size, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		if (FPS.equals(id)) {
			drawFps(g, x, y, size, alpha);
			return;
		}
		Identifier tex = CUSTOM.get(id);
		if (tex != null) {
			drawCustom(g, tex, CUSTOM_SIZE.get(id), x, y, size, alpha);
			return;
		}
		Item item = ITEMS.get(id);
		if (item != null) {
			drawItem(g, item, x, y, size, alpha);
		}
	}

	private static void drawCustom(GuiGraphicsExtractor g, Identifier tex, int nativeSize,
								   int x, int y, int size, float alpha) {
		int argb = (Math.max(1, (int) (alpha * 255)) << 24) | 0xFFFFFF;
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, size, size,
				nativeSize, nativeSize, nativeSize, nativeSize, argb);
	}

	private static void drawItem(GuiGraphicsExtractor g, Item item, int x, int y, int size, float alpha) {
		// fakeItem always draws a 16x16 GUI item, so scale the pose to hit the box.
		// Translate first, then scale, so the item lands at (x,y). No alpha fade —
		// 26.2 has no setShaderColor, so items pop in at full opacity past the floor.
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		float s = size / 16f;
		pose.scale(s, s);
		g.fakeItem(new ItemStack(item), 0, 0);
		pose.popMatrix();
	}

	private static void drawFps(GuiGraphicsExtractor g, int x, int y, int size, float alpha) {
		int fps = Minecraft.getInstance().getFps();
		String text = fps > 999 ? "999+" : Integer.toString(fps);
		Font font = Minecraft.getInstance().font;
		float s = size / 24f;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x + size / 2f, y + size / 2f);
		pose.scale(s, s);
		int a = Math.max(4, (int) (alpha * 255)) << 24;
		g.text(font, text, -font.width(text) / 2, -4, a | 0xFFFFFF, false);
		pose.popMatrix();
	}
}
